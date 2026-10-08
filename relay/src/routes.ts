/**
 * Path allowlist. Each route mirrors exactly one upstream call the app makes; the app keeps
 * the provider's path under a `/v1/<provider>/` prefix so its Retrofit interfaces are reused.
 * Only validated parameters are forwarded and used in cache keys; anything else is dropped.
 */

export type Source = "juhe" | "qweather";

export interface UpstreamRoute {
  kind: "upstream";
  source: Source;
  /** Upstream path (no leading slash). */
  path: string;
  /** Validated query parameters, in a fixed order (also the cache key). */
  params: [string, string][];
  /** Freshness in seconds; matches the app's TYP-51 TTLs (list 10 min, detail 30 min). */
  ttlSeconds: number;
}

export interface AlertsRoute {
  kind: "alerts";
}

export interface HealthRoute {
  kind: "health";
}

export type Route = UpstreamRoute | AlertsRoute | HealthRoute;

export class RouteError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
  ) {
    super(code);
  }
}

export const LIST_TTL_SECONDS = 10 * 60;
export const DETAIL_TTL_SECONDS = 30 * 60;

/** Juhe tfid, e.g. `202609`; tropical depressions may look like `2026D07`. */
const TFID = /^\d{4}[A-Z]?\d{2}$/;
/** QWeather storm id, e.g. `NP_2609`. */
const STORM_ID = /^NP_[0-9A-Z]{4}$/;
const YEAR = /^\d{4}$/;

function required(url: URL, name: string, pattern: RegExp): string {
  const value = url.searchParams.get(name);
  if (value === null || !pattern.test(value)) throw new RouteError(400, `invalid_${name}`);
  return value;
}

export function matchRoute(url: URL, nowMs: number): Route {
  switch (url.pathname) {
    case "/v1/health":
      return { kind: "health" };
    case "/v1/alerts":
      return { kind: "alerts" };
    case "/v1/juhe/fapigw/typhoon/active":
      return { kind: "upstream", source: "juhe", path: "fapigw/typhoon/active", params: [], ttlSeconds: LIST_TTL_SECONDS };
    case "/v1/juhe/fapigw/typhoon/detail":
      return {
        kind: "upstream",
        source: "juhe",
        path: "fapigw/typhoon/detail",
        params: [["tfid", required(url, "tfid", TFID)]],
        ttlSeconds: DETAIL_TTL_SECONDS,
      };
    case "/v1/qweather/v7/tropical/storm-list": {
      const basin = url.searchParams.get("basin") ?? "NP";
      if (basin !== "NP") throw new RouteError(400, "invalid_basin");
      const year = required(url, "year", YEAR);
      const current = new Date(nowMs).getUTCFullYear();
      const y = Number(year);
      if (y < 2000 || y > current + 1) throw new RouteError(400, "invalid_year");
      return {
        kind: "upstream",
        source: "qweather",
        path: "v7/tropical/storm-list",
        params: [["basin", "NP"], ["year", year]],
        ttlSeconds: LIST_TTL_SECONDS,
      };
    }
    case "/v1/qweather/v7/tropical/storm-track":
    case "/v1/qweather/v7/tropical/storm-forecast":
      return {
        kind: "upstream",
        source: "qweather",
        path: url.pathname.slice("/v1/qweather/".length),
        params: [["stormid", required(url, "stormid", STORM_ID)]],
        ttlSeconds: DETAIL_TTL_SECONDS,
      };
    default:
      throw new RouteError(404, "not_found");
  }
}

/** Stable cache key for a route (no secrets in it). */
export function cacheKey(route: UpstreamRoute): string {
  const query = route.params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
  return `v1:${route.source}:${route.path}${query ? `?${query}` : ""}`;
}
