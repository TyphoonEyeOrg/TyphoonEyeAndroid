import { yearAllowed } from "./clock";

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
  /**
   * Detail / track / forecast only: the storm id must appear in this source's list
   * ([list]) before the relay calls the provider for it (see guard.ts).
   */
  knownIn?: { list: UpstreamRoute; id: string };
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

/** Juhe's list of active storms (the only Juhe list there is). */
export function juheListRoute(): UpstreamRoute {
  return { kind: "upstream", source: "juhe", path: "fapigw/typhoon/active", params: [], ttlSeconds: LIST_TTL_SECONDS };
}

/** QWeather's NW Pacific storm list for [year] (active and past storms of that year). */
export function qweatherListRoute(year: number): UpstreamRoute {
  return {
    kind: "upstream",
    source: "qweather",
    path: "v7/tropical/storm-list",
    params: [["basin", "NP"], ["year", String(year)]],
    ttlSeconds: LIST_TTL_SECONDS,
  };
}

/** Year encoded in a QWeather storm id (`NP_2609` → 2026), or null. */
export function stormIdYear(stormId: string): number | null {
  const yy = /^NP_(\d{2})/.exec(stormId);
  return yy ? 2000 + Number(yy[1]) : null;
}

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
      return juheListRoute();
    case "/v1/juhe/fapigw/typhoon/detail": {
      const tfid = required(url, "tfid", TFID);
      return {
        kind: "upstream",
        source: "juhe",
        path: "fapigw/typhoon/detail",
        params: [["tfid", tfid]],
        ttlSeconds: DETAIL_TTL_SECONDS,
        knownIn: { list: juheListRoute(), id: tfid },
      };
    }
    case "/v1/qweather/v7/tropical/storm-list": {
      const basin = url.searchParams.get("basin") ?? "NP";
      if (basin !== "NP") throw new RouteError(400, "invalid_basin");
      const year = Number(required(url, "year", YEAR));
      // Current or previous year (UTC+8) only: older lists are not needed by the app.
      if (!yearAllowed(year, nowMs)) throw new RouteError(400, "invalid_year");
      return qweatherListRoute(year);
    }
    case "/v1/qweather/v7/tropical/storm-track":
    case "/v1/qweather/v7/tropical/storm-forecast": {
      const stormId = required(url, "stormid", STORM_ID);
      // Storms of the current or previous year only, and only if that year's list has them.
      const year = stormIdYear(stormId);
      if (year === null || !yearAllowed(year, nowMs)) throw new RouteError(404, "unknown_storm");
      return {
        kind: "upstream",
        source: "qweather",
        path: url.pathname.slice("/v1/qweather/".length),
        params: [["stormid", stormId]],
        ttlSeconds: DETAIL_TTL_SECONDS,
        knownIn: { list: qweatherListRoute(year), id: stormId },
      };
    }
    default:
      throw new RouteError(404, "not_found");
  }
}

/** Stable cache key for a route (no secrets in it). */
export function cacheKey(route: UpstreamRoute): string {
  const query = route.params.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&");
  return `v1:${route.source}:${route.path}${query ? `?${query}` : ""}`;
}
