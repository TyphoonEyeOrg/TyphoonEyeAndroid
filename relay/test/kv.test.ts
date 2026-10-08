import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ALERTS_KV_KEY, refreshAlerts, type AlertsState } from "../src/alerts";
import { handleFetch } from "../src/index";
import { clientRequest, FakeKV, FakeUpstream, json, makeDeps, makeEnv } from "./helpers";

const T0 = Date.UTC(2026, 9, 8, 1, 0, 0);

/** KV over the Free-plan write limit: reads work, every put throws. */
class WriteLimitedKV extends FakeKV {
  failedPuts = 0;
  override async put(): Promise<void> {
    this.failedPuts++;
    throw new Error("KV put() limit exceeded for the day.");
  }
}

class BrokenKV extends FakeKV {
  override async get(): Promise<unknown> {
    throw new Error("KV get() failed");
  }
  override async put(): Promise<void> {
    throw new Error("KV put() failed");
  }
}

let warn: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
});
afterEach(() => warn.mockRestore());

describe("best-effort KV on the request path", () => {
  it("returns 200 with the provider body when the KV write throws", async () => {
    const body = { reason: "success", error_code: 0, result: { data: { tfid: "202609", points: [] } } };
    const kv = new WriteLimitedKV();
    const upstream = new FakeUpstream(() => json(body));
    const res = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/detail?tfid=202609"), makeEnv({ RELAY_KV: kv }), makeDeps(upstream, { now: T0 }));

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual(body);
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=1800");
    expect(kv.failedPuts).toBe(1);
    // Minimal log line: nothing about the request.
    expect(warn).toHaveBeenCalledWith("relay: KV write failed");
    expect(JSON.stringify(warn.mock.calls)).not.toContain("202609");
  });

  it("falls back to the edge cache, then the provider, while KV cannot store", async () => {
    const kv = new WriteLimitedKV();
    const upstream = new FakeUpstream(() => json({ reason: "success", error_code: 0, result: { data: [] } }));
    const env = makeEnv({ RELAY_KV: kv });
    const clock = { now: T0 };
    const deps = makeDeps(upstream, clock);
    await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, deps);
    await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, deps); // edge hit
    expect(upstream.requests).toHaveLength(1);
    // Another location (empty edge cache) has nothing in KV: goes to the provider.
    await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, makeDeps(upstream, clock));
    expect(upstream.requests).toHaveLength(2);
  });

  it("treats a failing KV read as a miss and still serves", async () => {
    const upstream = new FakeUpstream(() => json({ code: "200", storm: [] }));
    const env = makeEnv({ RELAY_KV: new BrokenKV() });
    const deps = makeDeps(upstream, { now: T0 }, null);

    const list = await handleFetch(clientRequest("/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026"), env, deps);
    const alerts = await handleFetch(clientRequest("/v1/alerts"), env, deps);
    const health = await handleFetch(clientRequest("/v1/health"), env, deps);

    expect(list.status).toBe(200);
    expect(await list.json()).toEqual({ code: "200", storm: [] });
    expect(await alerts.json()).toEqual({ version: 1, updatedAtMs: 0, points: [] });
    expect(health.status).toBe(200);
  });

  it("defers the KV write with waitUntil when the runtime provides it", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    class SlowKV extends FakeKV {
      override async put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void> {
        await gate;
        return super.put(key, value, options);
      }
    }
    const kv = new SlowKV();
    const pending: Promise<unknown>[] = [];
    const deps = { ...makeDeps(new FakeUpstream(() => json({ code: "200", track: [] })), { now: T0 }, null), waitUntil: (p: Promise<unknown>) => void pending.push(p) };

    const res = await handleFetch(clientRequest("/v1/qweather/v7/tropical/storm-track?stormid=NP_2609"), makeEnv({ RELAY_KV: kv }), deps);

    expect(res.status).toBe(200);
    expect(pending).toHaveLength(1);
    expect(kv.store.size).toBe(0); // response did not wait for KV
    release();
    await Promise.all(pending);
    expect(kv.store.size).toBe(1);
  });
});

describe("best-effort KV in the cron job", () => {
  const previous: AlertsState = {
    version: 1,
    updatedAtMs: T0 - 30 * 60 * 1000,
    cursor: 0,
    activeStorms: 1,
    points: [{ id: "hk", name: "香港", lat: 22.3, lon: 114.17, fetchedAtMs: T0 - 30 * 60 * 1000, alerts: [{ id: "keep", eventType: { name: "台风", code: "1001" } }] }],
  };

  async function seededKv(): Promise<WriteLimitedKV> {
    const kv = new WriteLimitedKV();
    kv.store.set(ALERTS_KV_KEY, JSON.stringify(previous));
    return kv;
  }

  it("keeps the previous alert list when the write fails (storm active)", async () => {
    const kv = await seededKv();
    const upstream = new FakeUpstream((r) =>
      r.url.includes("juhe")
        ? json({ reason: "success", error_code: 0, result: { data: [{ tfid: "202609", lat: "21.5", lng: "116.5" }] } })
        : json({ alerts: [] }),
    );
    const env = makeEnv({ RELAY_KV: kv });

    const summary = await refreshAlerts(env, makeDeps(upstream, { now: T0 }, null));

    expect(summary.skipped).toBe("kv_write_failed");
    expect(JSON.parse(kv.store.get(ALERTS_KV_KEY)!)).toEqual(previous);
    const served = (await (await handleFetch(clientRequest("/v1/alerts"), env, makeDeps(upstream, { now: T0 }, null))).json()) as {
      updatedAtMs: number;
      points: { alerts: { id: string }[] }[];
    };
    expect(served.updatedAtMs).toBe(previous.updatedAtMs);
    expect(served.points[0].alerts[0].id).toBe("keep");
  });

  it("does not throw when the write fails with no storm active", async () => {
    const kv = await seededKv();
    const upstream = new FakeUpstream(() => json({ reason: "success", error_code: 0, result: { data: [] } }));
    const summary = await refreshAlerts(makeEnv({ RELAY_KV: kv }), makeDeps(upstream, { now: T0 }, null));
    expect(summary).toEqual({ skipped: "kv_write_failed", activeStorms: 0 });
    expect(JSON.parse(kv.store.get(ALERTS_KV_KEY)!)).toEqual(previous);
  });

  it("survives a KV that fails reads and writes", async () => {
    const upstream = new FakeUpstream(() => json({ reason: "success", error_code: 0, result: { data: [] } }));
    await expect(refreshAlerts(makeEnv({ RELAY_KV: new BrokenKV() }), makeDeps(upstream, { now: T0 }, null))).resolves.toMatchObject({
      skipped: "kv_write_failed",
    });
  });
});
