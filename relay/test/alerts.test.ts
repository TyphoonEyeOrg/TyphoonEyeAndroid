import { describe, expect, it } from "vitest";
import { ALERT_CRON_INTERVAL_MINUTES, ALERTS_KV_KEY, batchOf, isTyphoonRelated, refreshAlerts, selectPoints, WATCH_POINTS, type AlertsState } from "../src/alerts";
import { handleFetch } from "../src/index";
import { clientRequest, FakeUpstream, json, makeDeps, makeEnv } from "./helpers";

const T0 = Date.UTC(2026, 9, 8, 1, 0, 0);
const typhoonAlert = (id: string, expireTime = "2026-10-09T12:00+08:00") => ({
  id,
  headline: `台风预警 ${id}`,
  expireTime,
  eventType: { name: "台风", code: "1001" },
});
const rainAlert = { id: "rain", eventType: { name: "暴雨", code: "1003" } };

function juheActive(storms: { lat: string; lng: string }[]) {
  return { reason: "success", error_code: 0, result: { data: storms.map((s, i) => ({ tfid: `20260${i}`, ...s })) } };
}

describe("watch points", () => {
  it("has a fixed list of unique, plausible coastal points", () => {
    expect(WATCH_POINTS.length).toBe(54);
    expect(new Set(WATCH_POINTS.map((p) => p.id)).size).toBe(WATCH_POINTS.length);
    for (const p of WATCH_POINTS) {
      expect(p.lat).toBeGreaterThan(5);
      expect(p.lat).toBeLessThan(45);
      expect(p.lon).toBeGreaterThan(100);
      expect(p.lon).toBeLessThan(145);
    }
    for (const id of ["hk", "mo", "tw-taipei", "cn-shenzhen", "ph-manila", "jp-naha", "kr-busan", "vn-danang"]) {
      expect(WATCH_POINTS.some((p) => p.id === id)).toBe(true);
    }
  });

  it("filters typhoon-related alerts like the app does", () => {
    expect(isTyphoonRelated(typhoonAlert("a"))).toBe(true);
    expect(isTyphoonRelated({ eventType: { name: "热带风暴关注", code: "9999" } })).toBe(true);
    expect(isTyphoonRelated(rainAlert)).toBe(false);
  });

  it("selects points near storms, or all when positions are unknown", () => {
    const nearShenzhen = selectPoints(WATCH_POINTS, [{ lat: 21.0, lon: 116.0 }], 500);
    expect(nearShenzhen.map((p) => p.id)).toContain("cn-shenzhen");
    expect(nearShenzhen.map((p) => p.id)).not.toContain("jp-tokyo");
    expect(selectPoints(WATCH_POINTS, null, 500)).toHaveLength(WATCH_POINTS.length);
  });

  it("rotates through points when there are more than one run may query", () => {
    const first = batchOf(WATCH_POINTS, 0, 40);
    const second = batchOf(WATCH_POINTS, first.next, 40);
    expect(first.batch).toHaveLength(40);
    expect(second.batch[0].id).toBe(WATCH_POINTS[40].id);
    expect(new Set([...first.batch, ...second.batch].map((p) => p.id)).size).toBe(WATCH_POINTS.length);
  });
});

describe("cron refresh", () => {
  it("makes no alert calls when no storm is active", async () => {
    const upstream = new FakeUpstream(() => json(juheActive([])));
    const env = makeEnv();
    const summary = await refreshAlerts(env, makeDeps(upstream, { now: T0 }, null));

    expect(summary.activeStorms).toBe(0);
    expect(upstream.requests).toHaveLength(1);
    const state = (await env.RELAY_KV.get(ALERTS_KV_KEY)) as AlertsState;
    expect(state.updatedAtMs).toBe(T0);
    expect(state.points).toEqual([]);
  });

  it("queries fixed points near the storm only, keeps typhoon alerts, and shares the list cache", async () => {
    const upstream = new FakeUpstream((r) => {
      if (r.url.includes("juhe")) return json(juheActive([{ lat: "21.50", lng: "116.50" }]));
      if (r.url.includes("/22.54/114.06")) return json({ alerts: [typhoonAlert("sz"), rainAlert] });
      return json({ metadata: { zeroResult: true }, alerts: [] });
    });
    const env = makeEnv();
    const summary = await refreshAlerts(env, makeDeps(upstream, { now: T0 }, null));

    const alertCalls = upstream.requests.filter((r) => r.url.includes("weatheralert"));
    expect(summary.queried).toBe(alertCalls.length);
    expect(alertCalls.length).toBeLessThan(WATCH_POINTS.length);
    const fixed = new Set(WATCH_POINTS.map((p) => `${p.lat.toFixed(2)}/${p.lon.toFixed(2)}`));
    for (const r of alertCalls) {
      const u = new URL(r.url);
      expect(u.host).toBe("abc123.re.qweatherapi.com");
      expect(fixed.has(u.pathname.split("/current/")[1])).toBe(true);
      expect(u.searchParams.get("lang")).toBe("zh");
      expect(r.headers.get("X-QW-Api-Key")).toBeTruthy();
    }
    // The storm list went into the shared KV cache, so app requests reuse it.
    expect(env.RELAY_KV.store.has("v1:juhe:fapigw/typhoon/active")).toBe(true);

    const res = await handleFetch(clientRequest("/v1/alerts"), env, makeDeps(upstream, { now: T0 + 60_000 }, null));
    const body = (await res.json()) as { version: number; updatedAtMs: number; points: { id: string; alerts: { id: string }[] }[] };
    expect(body.version).toBe(1);
    expect(body.updatedAtMs).toBe(T0);
    expect(body.points).toEqual([
      expect.objectContaining({ id: "cn-shenzhen", lat: 22.54, lon: 114.06, alerts: [expect.objectContaining({ id: "sz" })] }),
    ]);
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=300");
  });

  it("queries all points (in batches) when only QWeather is configured", async () => {
    const upstream = new FakeUpstream((r) =>
      r.url.includes("storm-list") ? json({ code: "200", storm: [{ id: "NP_2609", isActive: "1" }] }) : json({ alerts: [] }),
    );
    const env = makeEnv({ JUHE_KEY: undefined });
    const clock = { now: T0 };
    const first = await refreshAlerts(env, makeDeps(upstream, clock, null));
    clock.now += ALERT_CRON_INTERVAL_MINUTES * 60 * 1000;
    const second = await refreshAlerts(env, makeDeps(upstream, clock, null));

    expect(first.selected).toBe(WATCH_POINTS.length);
    expect(first.queried).toBe(40);
    expect(second.queried).toBe(40);
    const queried = new Set(upstream.requests.filter((r) => r.url.includes("weatheralert")).map((r) => new URL(r.url).pathname));
    expect(queried.size).toBe(WATCH_POINTS.length);
    const state = (await env.RELAY_KV.get(ALERTS_KV_KEY)) as AlertsState;
    expect(state.points).toHaveLength(WATCH_POINTS.length);
  });

  it("keeps the previous list when every alert query fails", async () => {
    const env = makeEnv();
    const previous: AlertsState = { version: 1, updatedAtMs: T0 - 30 * 60 * 1000, cursor: 0, activeStorms: 1, points: [] };
    await env.RELAY_KV.put(ALERTS_KV_KEY, JSON.stringify(previous));
    const upstream = new FakeUpstream((r) => (r.url.includes("juhe") ? json(juheActive([{ lat: "21.5", lng: "116.5" }])) : json({}, 500)));

    const summary = await refreshAlerts(env, makeDeps(upstream, { now: T0 }, null));

    expect(summary.skipped).toBe("all_alert_queries_failed");
    expect(((await env.RELAY_KV.get(ALERTS_KV_KEY)) as AlertsState).updatedAtMs).toBe(previous.updatedAtMs);
  });

  it("treats redirected alert queries as failed and never follows them", async () => {
    const env = makeEnv();
    const previous: AlertsState = { version: 1, updatedAtMs: T0 - 30 * 60 * 1000, cursor: 0, activeStorms: 1, points: [] };
    await env.RELAY_KV.put(ALERTS_KV_KEY, JSON.stringify(previous));
    const upstream = new FakeUpstream((r) =>
      r.url.includes("juhe")
        ? json(juheActive([{ lat: "21.5", lng: "116.5" }]))
        : new Response(null, { status: 302, headers: { Location: "https://evil.example/" } }),
    );

    const summary = await refreshAlerts(env, makeDeps(upstream, { now: T0 }, null));

    expect(summary.skipped).toBe("all_alert_queries_failed");
    expect(((await env.RELAY_KV.get(ALERTS_KV_KEY)) as AlertsState).updatedAtMs).toBe(previous.updatedAtMs);
    expect(upstream.requests.every((r) => r.redirect === "manual" && !r.url.includes("evil.example"))).toBe(true);
  });

  it("does nothing without QWeather credentials", async () => {
    const upstream = new FakeUpstream(() => json({}));
    const summary = await refreshAlerts(makeEnv({ QWEATHER_API_KEY: undefined }), makeDeps(upstream, { now: T0 }, null));
    expect(summary.skipped).toBe("qweather_not_configured");
    expect(upstream.requests).toHaveLength(0);
  });

  it("serves an empty, never-updated list before the first cron run and hides expired alerts", async () => {
    const env = makeEnv();
    const deps = makeDeps(new FakeUpstream(() => json({})), { now: T0 }, null);
    expect(await (await handleFetch(clientRequest("/v1/alerts"), env, deps)).json()).toEqual({ version: 1, updatedAtMs: 0, points: [] });

    const state: AlertsState = {
      version: 1,
      updatedAtMs: T0,
      cursor: 0,
      activeStorms: 1,
      points: [{ id: "hk", name: "香港", lat: 22.3, lon: 114.17, fetchedAtMs: T0, alerts: [typhoonAlert("old", "2026-10-01T00:00+08:00"), typhoonAlert("new")] }],
    };
    await env.RELAY_KV.put(ALERTS_KV_KEY, JSON.stringify(state));
    const body = (await (await handleFetch(clientRequest("/v1/alerts"), env, deps)).json()) as { points: { alerts: { id: string }[]; fetchedAtMs?: number }[] };
    expect(body.points[0].alerts.map((a) => a.id)).toEqual(["new"]);
    expect(body.points[0].fetchedAtMs).toBeUndefined();
  });
});
