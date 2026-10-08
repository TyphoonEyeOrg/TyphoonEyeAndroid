import { describe, expect, it } from "vitest";
import { cacheKey, DETAIL_TTL_SECONDS, LIST_TTL_SECONDS, matchRoute, RouteError, type UpstreamRoute } from "../src/routes";

const NOW = Date.UTC(2026, 9, 8);
const route = (path: string) => matchRoute(new URL(`https://te-relay.seamain.org${path}`), NOW);

function expectError(path: string, status: number, code: string) {
  try {
    route(path);
  } catch (e) {
    expect(e).toBeInstanceOf(RouteError);
    expect((e as RouteError).status).toBe(status);
    expect((e as RouteError).code).toBe(code);
    return;
  }
  throw new Error(`expected ${code} for ${path}`);
}

describe("path allowlist", () => {
  it("maps exactly the six provider calls the app makes, with TYP-51 TTLs", () => {
    const cases: [string, string, number][] = [
      ["/v1/juhe/fapigw/typhoon/active", "v1:juhe:fapigw/typhoon/active", LIST_TTL_SECONDS],
      ["/v1/juhe/fapigw/typhoon/detail?tfid=202609", "v1:juhe:fapigw/typhoon/detail?tfid=202609", DETAIL_TTL_SECONDS],
      ["/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026", "v1:qweather:v7/tropical/storm-list?basin=NP&year=2026", LIST_TTL_SECONDS],
      ["/v1/qweather/v7/tropical/storm-track?stormid=NP_2609", "v1:qweather:v7/tropical/storm-track?stormid=NP_2609", DETAIL_TTL_SECONDS],
      ["/v1/qweather/v7/tropical/storm-forecast?stormid=NP_2609", "v1:qweather:v7/tropical/storm-forecast?stormid=NP_2609", DETAIL_TTL_SECONDS],
    ];
    for (const [path, key, ttl] of cases) {
      const r = route(path) as UpstreamRoute;
      expect(r.kind).toBe("upstream");
      expect(cacheKey(r)).toBe(key);
      expect(r.ttlSeconds).toBe(ttl);
    }
    expect(LIST_TTL_SECONDS).toBe(600);
    expect(DETAIL_TTL_SECONDS).toBe(1800);
    expect(route("/v1/alerts").kind).toBe("alerts");
    expect(route("/v1/health").kind).toBe("health");
  });

  it("drops unknown parameters (including a client-sent key) from upstream and cache key", () => {
    const r = route("/v1/juhe/fapigw/typhoon/active?key=evil&x=1") as UpstreamRoute;
    expect(r.params).toEqual([]);
    expect(cacheKey(r)).toBe("v1:juhe:fapigw/typhoon/active");
  });

  it("rejects everything else", () => {
    expectError("/", 404, "not_found");
    expectError("/v1/qweather/weatheralert/v1/current/22.54/114.06", 404, "not_found");
    expectError("/v1/qweather/v7/weather/now?location=101010100", 404, "not_found");
    // URL parsing resolves dot segments before matching: still needs a valid tfid.
    expectError("/v1/juhe/fapigw/typhoon/active/../detail", 400, "invalid_tfid");
    expectError("/v1/juhe/fapigw/typhoon/active/", 404, "not_found");
    expectError("/v1/juhe/fapigw/typhoon/detail", 400, "invalid_tfid");
    expectError("/v1/juhe/fapigw/typhoon/detail?tfid=1;drop", 400, "invalid_tfid");
    expectError("/v1/qweather/v7/tropical/storm-track?stormid=EP_2609", 400, "invalid_stormid");
    expectError("/v1/qweather/v7/tropical/storm-list?basin=AT&year=2026", 400, "invalid_basin");
    expectError("/v1/qweather/v7/tropical/storm-list?basin=NP&year=26", 400, "invalid_year");
    expectError("/v1/qweather/v7/tropical/storm-list?basin=NP&year=1999", 400, "invalid_year");
    expectError("/v1/qweather/v7/tropical/storm-list?basin=NP&year=2028", 400, "invalid_year");
  });

  it("accepts tropical-depression style ids", () => {
    expect(route("/v1/juhe/fapigw/typhoon/detail?tfid=2026D07").kind).toBe("upstream");
  });
});
