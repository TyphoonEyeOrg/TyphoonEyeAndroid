import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ALERTS_KV_KEY, refreshAlerts, WATCH_POINTS, type AlertsState } from "../src/alerts";
import { budgetKey, budgetMemory, DEFAULT_DAILY_BUDGET, dailyLimit, FLUSH_EVERY_CALLS, FLUSH_INTERVAL_MS } from "../src/budget";
import { allowedYears, localDate, localYear, secondsUntilLocalMidnight } from "../src/clock";
import { handleFetch } from "../src/index";
import { matchRoute, RouteError } from "../src/routes";
import {
  cachedKeys,
  clientRequest,
  FakeCache,
  FakeKV,
  FakeUpstream,
  json,
  JUHE_LIST,
  JUHE_LIST_KEY,
  makeDeps,
  makeEnv,
  QW_LIST,
  qwListKey,
  seedList,
} from "./helpers";

/** 2026-10-08 09:00 UTC+8. */
const T0 = Date.UTC(2026, 9, 8, 1, 0, 0);
const MIN = 60 * 1000;
const TRACK = "/v1/qweather/v7/tropical/storm-track?stormid=";
const DETAIL = "/v1/juhe/fapigw/typhoon/detail?tfid=";
const TRACK_OK = { code: "200", isActive: "1", track: [] };
const DETAIL_OK = { reason: "success", error_code: 0, result: { data: { tfid: "202609", points: [] } } };

/** Answers lists from [lists] and everything else with a detail body. */
function provider(lists: { juhe?: unknown; qweather?: Record<number, unknown> } = {}): FakeUpstream {
  return new FakeUpstream((r) => {
    const u = new URL(r.url);
    if (u.pathname.endsWith("/typhoon/active")) return json(lists.juhe ?? JUHE_LIST);
    if (u.pathname.endsWith("/storm-list")) return json(lists.qweather?.[Number(u.searchParams.get("year"))] ?? { code: "200", storm: [] });
    if (u.pathname.includes("weatheralert")) return json({ alerts: [] });
    return json(u.pathname.includes("typhoon/detail") ? DETAIL_OK : TRACK_OK);
  });
}

const paths = (upstream: FakeUpstream) => upstream.requests.map((r) => new URL(r.url).pathname.split("/").pop());
const counter = (kv: FakeKV, source: "juhe" | "qweather", now = T0) => {
  const v = kv.store.get(budgetKey(source, now));
  return v === undefined ? 0 : (JSON.parse(v) as { count: number }).count;
};
const setCounter = (kv: FakeKV, source: "juhe" | "qweather", count: number, now = T0) =>
  kv.store.set(budgetKey(source, now), JSON.stringify({ count }));

let warn: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
});
afterEach(() => warn.mockRestore());

describe("UTC+8 clock", () => {
  it("uses the UTC+8 date and year, and counts down to local midnight", () => {
    expect(localDate(T0)).toBe("2026-10-08");
    expect(localDate(Date.UTC(2026, 9, 8, 16, 30))).toBe("2026-10-09"); // 00:30 UTC+8
    expect(localYear(Date.UTC(2026, 11, 31, 16, 0))).toBe(2027); // 00:00 UTC+8 on 1 Jan
    expect(secondsUntilLocalMidnight(T0)).toBe(15 * 3600);
    expect(secondsUntilLocalMidnight(Date.UTC(2026, 9, 8, 15, 59, 59))).toBe(1);
  });

  it("allows the current and previous year, and next year only on the evening of 31 Dec", () => {
    expect(allowedYears(T0)).toEqual({ min: 2025, max: 2026 });
    expect(allowedYears(Date.UTC(2026, 11, 31, 9, 0))).toEqual({ min: 2025, max: 2026 }); // 17:00 UTC+8
    expect(allowedYears(Date.UTC(2026, 11, 31, 11, 0))).toEqual({ min: 2025, max: 2027 }); // 19:00 UTC+8 = 01:00 UTC+14
    expect(allowedYears(Date.UTC(2027, 0, 1, 0, 0))).toEqual({ min: 2026, max: 2027 });
  });
});

describe("storm-list year and basin", () => {
  const route = (path: string, now = T0) => matchRoute(new URL(`https://te-relay.seamain.org${path}`), now);
  const code = (path: string, now = T0) => {
    try {
      route(path, now);
      return "ok";
    } catch (e) {
      return (e as RouteError).code;
    }
  };

  it("accepts only NP and the current or previous UTC+8 year", () => {
    const list = "/v1/qweather/v7/tropical/storm-list?basin=NP&year=";
    expect(code(`${list}2026`)).toBe("ok");
    expect(code(`${list}2025`)).toBe("ok");
    expect(code(`${list}2024`)).toBe("invalid_year");
    expect(code(`${list}2027`)).toBe("invalid_year");
    expect(code("/v1/qweather/v7/tropical/storm-list?basin=EP&year=2026")).toBe("invalid_basin");
    // 1 Jan 2027, 00:30 UTC+8: last year's list is still served.
    expect(code(`${list}2026`, Date.UTC(2026, 11, 31, 16, 30))).toBe("ok");
    expect(code(`${list}2025`, Date.UTC(2026, 11, 31, 16, 30))).toBe("invalid_year");
  });

  it("rejects storm ids outside those years with 404 unknown_storm, before any lookup", async () => {
    expect(code(`${TRACK}NP_2420`)).toBe("unknown_storm");
    expect(code(`${TRACK}NP_2701`)).toBe("unknown_storm");
    expect(code(`${TRACK}NP_AB01`)).toBe("unknown_storm");
    const upstream = provider();
    const res = await handleFetch(clientRequest(`${TRACK}NP_2420`), makeEnv(), makeDeps(upstream, { now: T0 }));
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "unknown_storm" });
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=60");
    expect(upstream.requests).toHaveLength(0);
  });
});

describe("known-storm guard", () => {
  it("rejects an id missing from a fresh list with 404, without calling the provider", async () => {
    const env = makeEnv();
    seedList(env.RELAY_KV, JUHE_LIST_KEY, JUHE_LIST, T0 - 5 * MIN);
    seedList(env.RELAY_KV, qwListKey(), QW_LIST, T0 - 5 * MIN);
    const upstream = provider();
    const deps = makeDeps(upstream, { now: T0 });

    for (const path of [`${DETAIL}202699`, `${DETAIL}2026D07`, `${TRACK}NP_2699`, "/v1/qweather/v7/tropical/storm-forecast?stormid=NP_2698"]) {
      const res = await handleFetch(clientRequest(path), env, deps);
      expect(res.status).toBe(404);
      expect(await res.json()).toEqual({ error: "unknown_storm" });
      expect(res.headers.get("Cache-Control")).toBe("public, max-age=60");
    }
    expect(upstream.requests).toHaveLength(0);
    expect(budgetMemory("juhe")?.pending ?? 0).toBe(0);
    expect(budgetMemory("qweather")?.pending ?? 0).toBe(0);
  });

  it("passes ids that are in the cached list, even a stale one", async () => {
    const env = makeEnv();
    seedList(env.RELAY_KV, JUHE_LIST_KEY, JUHE_LIST, T0 - 3 * 60 * MIN);
    seedList(env.RELAY_KV, qwListKey(), QW_LIST, T0 - 5 * MIN);
    const upstream = provider();
    const deps = makeDeps(upstream, { now: T0 }, null);

    expect((await handleFetch(clientRequest(`${DETAIL}202609`), env, deps)).status).toBe(200);
    expect((await handleFetch(clientRequest(`${TRACK}NP_2609`), env, deps)).status).toBe(200);
    expect(paths(upstream)).toEqual(["detail", "storm-track"]);
    expect(budgetMemory("juhe")?.pending).toBe(1);
    expect(budgetMemory("qweather")?.pending).toBe(1);
  });

  it("uses the list from the edge cache (the app fetched it just before)", async () => {
    const env = makeEnv();
    const cache = new FakeCache();
    const upstream = provider({ qweather: { 2026: QW_LIST } });
    const deps = makeDeps(upstream, { now: T0 }, cache);
    await handleFetch(clientRequest("/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026"), env, deps);
    env.RELAY_KV.store.delete(qwListKey()); // e.g. the KV write was refused

    expect((await handleFetch(clientRequest(`${TRACK}NP_2609`), env, deps)).status).toBe(200);
    expect(paths(upstream)).toEqual(["storm-list", "storm-track"]);
  });

  it("refreshes a missing or stale list once per list TTL, then answers unknown ids from it", async () => {
    const env = makeEnv();
    seedList(env.RELAY_KV, qwListKey(), { code: "200", storm: [] }, T0 - 30 * MIN); // stale, storm not yet listed
    const upstream = provider({ qweather: { 2026: QW_LIST } });
    const clock = { now: T0 };
    // No edge cache: every request could be in another location; the KV gate still holds.
    const get = (path: string) => handleFetch(clientRequest(path), env, makeDeps(upstream, clock, null));

    expect((await get(`${TRACK}NP_2609`)).status).toBe(200); // new storm: list refreshed, then track
    expect(paths(upstream)).toEqual(["storm-list", "storm-track"]);
    for (const id of ["NP_2650", "NP_2651", "NP_2652"]) expect((await get(`${TRACK}${id}`)).status).toBe(404);
    expect(upstream.requests).toHaveLength(2); // fresh list: no refresh for unknown ids

    clock.now += 11 * MIN; // list stale again
    expect((await get(`${TRACK}NP_2653`)).status).toBe(404);
    expect((await get(`${TRACK}NP_2654`)).status).toBe(404);
    expect(paths(upstream)).toEqual(["storm-list", "storm-track", "storm-list"]);
  });

  it("does not let failed list refreshes be retried more than once per TTL", async () => {
    const env = makeEnv();
    const upstream = new FakeUpstream(() => json({ code: "500" }, 500));
    const clock = { now: T0 };
    const get = (path: string) => handleFetch(clientRequest(path), env, makeDeps(upstream, clock, null));

    const first = await get(`${TRACK}NP_2609`);
    expect(first.status).toBe(502);
    expect(await first.json()).toEqual({ error: "upstream_unavailable" });
    for (const id of ["NP_2650", "NP_2651"]) expect((await get(`${TRACK}${id}`)).status).toBe(502);
    expect(paths(upstream)).toEqual(["storm-list"]);
  });

  it("allows last year's storms only when last year's list has them", async () => {
    const env = makeEnv();
    const upstream = provider({ qweather: { 2025: { code: "200", storm: [{ id: "NP_2520", isActive: "0" }] } } });
    const deps = makeDeps(upstream, { now: T0 }, null);

    expect((await handleFetch(clientRequest(`${TRACK}NP_2520`), env, deps)).status).toBe(200);
    expect((await handleFetch(clientRequest(`${TRACK}NP_2521`), env, deps)).status).toBe(404);
    expect(upstream.requests.map((r) => new URL(r.url).search)).toEqual(["?basin=NP&year=2025", "?stormid=NP_2520"]);
  });

  it("checks Juhe ids against the Juhe active list", async () => {
    const env = makeEnv();
    const upstream = provider();
    const deps = makeDeps(upstream, { now: T0 }, null);
    expect((await handleFetch(clientRequest(`${DETAIL}202609`), env, deps)).status).toBe(200);
    expect((await handleFetch(clientRequest(`${DETAIL}202610`), env, deps)).status).toBe(404);
    expect(paths(upstream)).toEqual(["active", "detail"]);
  });

  it("treats a Juhe error list (HTTP 200, error_code ≠ 0) as no list", async () => {
    const env = makeEnv();
    const upstream = provider({ juhe: { reason: "错误的请求KEY", error_code: 10001 } });
    const res = await handleFetch(clientRequest(`${DETAIL}202609`), env, makeDeps(upstream, { now: T0 }, null));
    expect(res.status).toBe(502);
    expect(paths(upstream)).toEqual(["active"]);
  });
});

describe("daily upstream budget", () => {
  it("reads limits from vars, with defaults", () => {
    expect(DEFAULT_DAILY_BUDGET).toEqual({ juhe: 400, qweather: 4000 });
    expect(dailyLimit("juhe", makeEnv())).toBe(400);
    expect(dailyLimit("qweather", makeEnv())).toBe(4000);
    expect(dailyLimit("juhe", makeEnv({ JUHE_DAILY_BUDGET: "50" }))).toBe(50);
    expect(dailyLimit("qweather", makeEnv({ QWEATHER_DAILY_BUDGET: "0" }))).toBe(0);
    expect(dailyLimit("juhe", makeEnv({ JUHE_DAILY_BUDGET: "lots" }))).toBe(400);
  });

  it("counts every provider call per source; cache hits are free", async () => {
    const env = makeEnv();
    const upstream = provider();
    const get = (path: string) => handleFetch(clientRequest(path), env, makeDeps(upstream, { now: T0 }, null));
    await get("/v1/juhe/fapigw/typhoon/active");
    await get("/v1/juhe/fapigw/typhoon/active"); // KV hit
    await get(`${DETAIL}202609`);
    expect(budgetMemory("juhe")).toMatchObject({ date: "2026-10-08", pending: 2 });
    expect(budgetMemory("qweather")).toBeUndefined();
  });

  it("over budget: serves the last good answer (stale) without calling the provider", async () => {
    const env = makeEnv({ QWEATHER_DAILY_BUDGET: "100" });
    setCounter(env.RELAY_KV, "qweather", 100);
    seedList(env.RELAY_KV, qwListKey(), QW_LIST, T0 - 2 * 60 * MIN);
    seedList(env.RELAY_KV, "v1:qweather:v7/tropical/storm-track?stormid=NP_2609", TRACK_OK, T0 - 2 * 60 * MIN);
    const upstream = provider();
    const deps = makeDeps(upstream, { now: T0 }, null);

    for (const path of ["/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026", `${TRACK}NP_2609`]) {
      const res = await handleFetch(clientRequest(path), env, deps);
      expect(res.status).toBe(200);
      expect(res.headers.get("X-Relay-Stale")).toBe("1");
      expect(res.headers.get("Cache-Control")).toBe("public, max-age=60");
    }
    expect(upstream.requests).toHaveLength(0);
    expect(counter(env.RELAY_KV, "qweather")).toBe(100);
  });

  it("over budget without a stale copy: 503 budget_exhausted until local midnight", async () => {
    const env = makeEnv({ JUHE_DAILY_BUDGET: "2" });
    const upstream = provider();
    const clock = { now: T0 };
    const get = (path: string) => handleFetch(clientRequest(path), env, makeDeps(upstream, clock, null));
    expect((await get("/v1/juhe/fapigw/typhoon/active")).status).toBe(200);
    expect((await get(`${DETAIL}202609`)).status).toBe(200);
    env.RELAY_KV.store.delete(JUHE_LIST_KEY); // no stale copy left

    const res = await get("/v1/juhe/fapigw/typhoon/active");
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ error: "budget_exhausted" });
    expect(res.headers.get("Retry-After")).toBe(String(15 * 3600));
    expect(res.headers.get("Cache-Control")).toBe("no-store");
    expect(upstream.requests).toHaveLength(2);
    // An unknown id cannot trigger a list refresh either.
    expect((await get(`${DETAIL}202610`)).status).toBe(503);
    expect(upstream.requests).toHaveLength(2);
  });

  it("each source has its own budget", async () => {
    const env = makeEnv();
    setCounter(env.RELAY_KV, "juhe", 400);
    const upstream = provider();
    const deps = makeDeps(upstream, { now: T0 }, null);
    expect((await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, deps)).status).toBe(503);
    expect((await handleFetch(clientRequest("/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026"), env, deps)).status).toBe(200);
  });

  it("fails open when KV is broken: requests are still served, a warning is logged", async () => {
    class BrokenKV extends FakeKV {
      override async get(): Promise<unknown> {
        throw new Error("KV get() failed");
      }
      override async put(): Promise<void> {
        throw new Error("KV put() failed");
      }
    }
    const env = makeEnv({ RELAY_KV: new BrokenKV() });
    const upstream = provider({ qweather: { 2026: QW_LIST } });
    const deps = makeDeps(upstream, { now: T0 }, null);

    const list = await handleFetch(clientRequest("/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026"), env, deps);
    const track = await handleFetch(clientRequest(`${TRACK}NP_2609`), env, deps);
    expect(list.status).toBe(200);
    expect(track.status).toBe(200);
    expect(paths(upstream)).toEqual(["storm-list", "storm-list", "storm-track"]);
    expect(warn).toHaveBeenCalledWith("relay: KV read failed");
    expect(warn).toHaveBeenCalledWith("relay: KV write failed");
  });
});

describe("batched budget counter", () => {
  /** Juhe list with [n] storms 202601… so n distinct detail requests are all cache misses. */
  const manyStorms = (n: number) => ({
    reason: "success",
    error_code: 0,
    result: { data: Array.from({ length: n }, (_, i) => ({ tfid: `2026${String(i + 1).padStart(2, "0")}` })) },
  });
  const budgetPuts = (kv: FakeKV) => kv.puts.filter((p) => p.key.startsWith("budget:"));

  function setup(overrides: Parameters<typeof makeEnv>[0] = {}, kv: FakeKV = new FakeKV()) {
    const env = makeEnv({ RELAY_KV: kv, ...overrides });
    seedList(env.RELAY_KV, JUHE_LIST_KEY, manyStorms(40), T0);
    const upstream = provider();
    const clock = { now: T0 };
    const pending: Promise<unknown>[] = [];
    let next = 1;
    const deps = () => ({ ...makeDeps(upstream, clock, null), waitUntil: (p: Promise<unknown>) => void pending.push(p) });
    const call = async (n = 1) => {
      for (let i = 0; i < n; i++, next++) {
        const res = await handleFetch(clientRequest(`${DETAIL}2026${String(next).padStart(2, "0")}`), env, deps());
        await Promise.all(pending.splice(0));
        if (res.status !== 200) return res.status;
      }
      return 200;
    };
    const ping = async () => {
      await handleFetch(clientRequest("/v1/health"), env, deps());
      await Promise.all(pending.splice(0));
    };
    return { env, upstream, clock, call, ping, pending };
  }

  it("writes nothing to KV before 20 calls, then merges all 20 in one write (after the response)", async () => {
    expect(FLUSH_EVERY_CALLS).toBe(20);
    const { env, upstream, call } = setup();
    await call(19);
    expect(budgetPuts(env.RELAY_KV)).toHaveLength(0);
    expect(budgetMemory("juhe")?.pending).toBe(19);
    await call(1);
    expect(upstream.requests).toHaveLength(20);
    expect(budgetPuts(env.RELAY_KV)).toEqual([{ key: "budget:v1:juhe:2026-10-08", ttl: 2 * 24 * 3600 }]);
    expect(counter(env.RELAY_KV, "juhe")).toBe(20);
    expect(budgetMemory("juhe")?.pending).toBe(0);
  });

  it("flushes 5 minutes after the last flush, on any request", async () => {
    expect(FLUSH_INTERVAL_MS).toBe(5 * MIN);
    const { env, clock, call, ping } = setup();
    await call(3);
    clock.now += 4 * MIN;
    await ping();
    expect(budgetPuts(env.RELAY_KV)).toHaveLength(0);
    clock.now += 1 * MIN;
    await ping();
    expect(counter(env.RELAY_KV, "juhe")).toBe(3);
    expect(budgetPuts(env.RELAY_KV)).toHaveLength(1);
    clock.now += 10 * MIN;
    await ping(); // nothing pending: no write
    expect(budgetPuts(env.RELAY_KV)).toHaveLength(1);
  });

  it("adds the pending calls to the value in KV (merge with other isolates)", async () => {
    const { env, call } = setup();
    await call(5);
    setCounter(env.RELAY_KV, "juhe", 100); // another isolate flushed meanwhile
    await call(15);
    expect(counter(env.RELAY_KV, "juhe")).toBe(120);
  });

  it("checks the budget against last known KV value + pending calls", async () => {
    const kv = new FakeKV();
    setCounter(kv, "juhe", 20);
    const { env, upstream, call } = setup({ JUHE_DAILY_BUDGET: "25" }, kv);
    expect(await call(5)).toBe(200);
    expect(await call(1)).toBe(503); // 20 in KV + 5 pending = 25
    expect(upstream.requests).toHaveLength(5);
    expect(counter(env.RELAY_KV, "juhe")).toBe(20); // not flushed yet
  });

  it("re-reads the KV counter at most once a minute", async () => {
    const { env, clock, call } = setup({ JUHE_DAILY_BUDGET: "100" });
    await call(1);
    setCounter(env.RELAY_KV, "juhe", 100); // other isolates used the rest
    clock.now += 30 * 1000;
    expect(await call(1)).toBe(200); // still the value read 30 s ago
    clock.now += 31 * 1000;
    expect(await call(1)).toBe(503);
  });

  it("keeps the pending calls when KV fails and retries at the next flush, 5 min later", async () => {
    class FlakyKV extends FakeKV {
      down = true;
      override async put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void> {
        if (this.down && key.startsWith("budget:")) throw new Error("KV put() failed");
        return super.put(key, value, options);
      }
    }
    const kv = new FlakyKV();
    const { clock, call } = setup({}, kv);
    await call(20);
    expect(warn).toHaveBeenCalledWith("relay: KV write failed");
    expect(budgetMemory("juhe")?.pending).toBe(20);
    expect(await call(5)).toBe(200); // served (fail open); no new attempt yet
    expect(warn.mock.calls.filter((c: unknown[]) => c[0] === "relay: KV write failed")).toHaveLength(1);
    kv.down = false;
    clock.now += 5 * MIN;
    await call(1);
    expect(counter(kv, "juhe")).toBe(26);
    expect(budgetMemory("juhe")?.pending).toBe(0);
  });

  it("starts a new count at the UTC+8 date change and drops the old day's pending calls", async () => {
    const { env, clock, call } = setup({ JUHE_DAILY_BUDGET: "10" });
    clock.now = Date.UTC(2026, 9, 8, 15, 58); // 23:58 UTC+8
    seedList(env.RELAY_KV, JUHE_LIST_KEY, manyStorms(40), clock.now);
    await call(10);
    expect(await call(1)).toBe(503);
    clock.now = Date.UTC(2026, 9, 8, 16, 1); // 00:01 UTC+8, 9 Oct
    expect(await call(1)).toBe(200);
    expect(budgetMemory("juhe")).toMatchObject({ date: "2026-10-09", pending: 1 });
    expect(env.RELAY_KV.store.has("budget:v1:juhe:2026-10-08")).toBe(false);
  });
});

describe("cron and the budget", () => {
  it("skips the run without any provider call when the QWeather budget is used up", async () => {
    const env = makeEnv();
    setCounter(env.RELAY_KV, "qweather", 4000);
    const previous: AlertsState = { version: 1, updatedAtMs: T0 - 30 * MIN, cursor: 0, activeStorms: 1, points: [] };
    env.RELAY_KV.store.set(ALERTS_KV_KEY, JSON.stringify(previous));
    const upstream = provider();

    const summary = await refreshAlerts(env, makeDeps(upstream, { now: T0 }, null));
    expect(summary).toEqual({ skipped: "budget_exhausted" });
    expect(upstream.requests).toHaveLength(0);
    expect(JSON.parse(env.RELAY_KV.store.get(ALERTS_KV_KEY)!)).toEqual(previous);
    expect(counter(env.RELAY_KV, "qweather")).toBe(4000);
  });

  it("queries no more points than the budget has left and counts them in one write", async () => {
    const env = makeEnv({ JUHE_KEY: undefined, QWEATHER_DAILY_BUDGET: "10" });
    const upstream = provider({ qweather: { 2026: QW_LIST } });

    const summary = await refreshAlerts(env, makeDeps(upstream, { now: T0 }, null));
    expect(summary.selected).toBe(WATCH_POINTS.length);
    expect(summary.queried).toBe(9); // 10 − 1 storm-list call
    expect(counter(env.RELAY_KV, "qweather")).toBe(10);
    expect(env.RELAY_KV.puts.filter((p) => p.key.startsWith("budget:"))).toHaveLength(1);

    const next = await refreshAlerts(env, makeDeps(upstream, { now: T0 + 30 * MIN }, null));
    expect(next).toEqual({ skipped: "budget_exhausted" });
    expect(upstream.requests).toHaveLength(10);
  });

  it("flushes this isolate's pending request counts at the end of every run", async () => {
    const env = makeEnv();
    const upstream = provider({ juhe: { reason: "success", error_code: 0, result: { data: [] } } });
    await handleFetch(clientRequest("/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026"), env, makeDeps(upstream, { now: T0 }, null));
    expect(counter(env.RELAY_KV, "qweather")).toBe(0);

    const later = T0 + 11 * MIN; // Juhe list from KV is stale now
    await refreshAlerts(env, makeDeps(upstream, { now: later }, null));
    expect(counter(env.RELAY_KV, "qweather", later)).toBe(1); // the earlier storm-list call
    expect(counter(env.RELAY_KV, "juhe", later)).toBe(1); // the cron's Juhe list call
    expect(budgetMemory("qweather")?.pending).toBe(0);
    expect(budgetMemory("juhe")?.pending).toBe(0);
  });

  it("falls back to the QWeather list when the Juhe budget is used up", async () => {
    const env = makeEnv();
    setCounter(env.RELAY_KV, "juhe", 400);
    const upstream = provider({ qweather: { 2026: { code: "200", storm: [] } } });
    const summary = await refreshAlerts(env, makeDeps(upstream, { now: T0 }, null));
    expect(summary.activeStorms).toBe(0);
    expect(paths(upstream)).toEqual(["storm-list"]);
    expect(counter(env.RELAY_KV, "juhe")).toBe(400);
  });
});

describe("response cache", () => {
  it("keeps KV copies for 6 h so a stale answer exists when the budget runs out", async () => {
    const env = makeEnv();
    await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, makeDeps(provider(), { now: T0 }, null));
    expect(env.RELAY_KV.puts.find((p) => p.key === JUHE_LIST_KEY)?.ttl).toBe(6 * 3600);
    expect(cachedKeys(env.RELAY_KV)).toEqual([JUHE_LIST_KEY]);
  });
});
