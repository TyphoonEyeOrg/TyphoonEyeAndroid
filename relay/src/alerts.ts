import pointsFile from "../data/coastal-points.json";
import type { Deps, Env } from "./env";
import { jsonResponse } from "./http";
import { cachedJson } from "./proxy";
import { qweatherBaseUrl, qweatherConfigured } from "./qweatherAuth";
import { LIST_TTL_SECONDS, type UpstreamRoute } from "./routes";
import { scrubSecrets, sourceConfigured, upstreamRequest } from "./upstream";

/**
 * Official typhoon warnings without user locations.
 *
 * A Cron Trigger queries QWeather's weather alerts at a FIXED list of coastal points
 * (`data/coastal-points.json`) and stores one merged list in KV. Every app downloads the
 * same list (`GET /v1/alerts`) and picks nearby points on the device, so no location ever
 * reaches the relay and the provider cost does not grow with the number of users.
 *
 * Cost control: when no storm is active in the NW Pacific the job makes one list call and
 * no alert calls; with storm positions known (Juhe), only points within
 * ALERT_STORM_RADIUS_KM of a storm are queried; at most MAX_ALERT_POINTS_PER_RUN points are
 * queried per run (rotating), which also keeps a run under the Workers subrequest limit.
 */

export interface WatchPoint {
  id: string;
  name: string;
  lat: number;
  lon: number;
}

interface PointState extends WatchPoint {
  fetchedAtMs: number;
  alerts: QWeatherAlert[];
}

export interface AlertsState {
  version: 1;
  updatedAtMs: number;
  cursor: number;
  activeStorms: number;
  points: PointState[];
}

interface QWeatherAlert {
  id?: string;
  expireTime?: string;
  eventType?: { name?: string; code?: string } | null;
  [key: string]: unknown;
}

export const ALERTS_KV_KEY = "alerts:v1";
export const WATCH_POINTS: WatchPoint[] = (pointsFile as { points: WatchPoint[] }).points;
const DEFAULT_RADIUS_KM = 1500;
const DEFAULT_MAX_POINTS = 40;
/** A point not re-queried this run keeps its last answer for this long. */
const KEEP_UNREFRESHED_MS = 2 * 60 * 60 * 1000;
const CONCURRENCY = 6;
/** How long a client may cache GET /v1/alerts (the cron runs every 15 min). */
export const ALERTS_MAX_AGE_SECONDS = 5 * 60;

/** QWeather event codes for typhoon-related alerts; same list as the app's WarningModels.kt. */
const TYPHOON_EVENT_CODES = new Set(["1001", "2330", "2331", "2365", "2366", "2615", "2616"]);

export function isTyphoonRelated(alert: QWeatherAlert): boolean {
  const code = alert.eventType?.code ?? "";
  if (TYPHOON_EVENT_CODES.has(code)) return true;
  const name = alert.eventType?.name ?? "";
  return ["台风", "热带风暴", "飓风", "热带气旋"].some((word) => name.includes(word));
}

export function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/** Points near any storm; all points when storm positions are unknown. */
export function selectPoints(points: WatchPoint[], storms: { lat: number; lon: number }[] | null, radiusKm: number): WatchPoint[] {
  if (!storms || storms.length === 0) return points;
  return points.filter((p) => storms.some((s) => haversineKm(p.lat, p.lon, s.lat, s.lon) <= radiusKm));
}

/** Rotating window of at most [max] points starting at [cursor]. */
export function batchOf(points: WatchPoint[], cursor: number, max: number): { batch: WatchPoint[]; next: number } {
  if (points.length <= max) return { batch: points, next: 0 };
  const start = ((cursor % points.length) + points.length) % points.length;
  const batch = Array.from({ length: max }, (_, i) => points[(start + i) % points.length]);
  return { batch, next: (start + max) % points.length };
}

interface StormSnapshot {
  count: number;
  positions: { lat: number; lon: number }[] | null;
}

/** Active NW Pacific storms via the shared list cache (same KV entry the app's requests use). */
export async function activeStorms(env: Env, deps: Deps): Promise<StormSnapshot | null> {
  if (sourceConfigured("juhe", env)) {
    const route: UpstreamRoute = { kind: "upstream", source: "juhe", path: "fapigw/typhoon/active", params: [], ttlSeconds: LIST_TTL_SECONDS };
    const json = (await cachedJson(route, env, deps)) as { result?: { data?: { lat?: string; lng?: string }[] } } | null;
    if (json) {
      const data = json.result?.data ?? [];
      const positions = data
        .map((t) => ({ lat: Number(t.lat), lon: Number(t.lng) }))
        .filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lon) && !(p.lat === 0 && p.lon === 0));
      return { count: data.length, positions: positions.length === data.length ? positions : null };
    }
  }
  if (qweatherConfigured(env)) {
    const year = String(new Date(deps.now()).getUTCFullYear());
    const route: UpstreamRoute = {
      kind: "upstream",
      source: "qweather",
      path: "v7/tropical/storm-list",
      params: [["basin", "NP"], ["year", year]],
      ttlSeconds: LIST_TTL_SECONDS,
    };
    const json = (await cachedJson(route, env, deps)) as { storm?: { isActive?: string }[] } | null;
    if (json) return { count: (json.storm ?? []).filter((s) => s.isActive === "1").length, positions: null };
  }
  return null;
}

async function queryPoint(point: WatchPoint, env: Env, deps: Deps): Promise<QWeatherAlert[] | null> {
  const url = `${qweatherBaseUrl(env)}/weatheralert/v1/current/${point.lat.toFixed(2)}/${point.lon.toFixed(2)}?lang=zh&localTime=true`;
  try {
    const response = await deps.fetch(await upstreamRequest(url, env, deps, "qweather"));
    if (response.status !== 200) return null;
    const json = JSON.parse(scrubSecrets(await response.text(), env)) as { alerts?: QWeatherAlert[] };
    return (json.alerts ?? []).filter(isTyphoonRelated);
  } catch {
    return null;
  }
}

async function mapLimited<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const out = new Array<R>(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      out[i] = await fn(items[i]);
    }
  });
  await Promise.all(workers);
  return out;
}

function intVar(raw: string | undefined, fallback: number): number {
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

export interface RefreshSummary {
  skipped?: string;
  activeStorms?: number;
  selected?: number;
  queried?: number;
  failed?: number;
  withAlerts?: number;
}

/** Cron job. Never throws; a failed run keeps the previous list. */
export async function refreshAlerts(env: Env, deps: Deps): Promise<RefreshSummary> {
  if (!qweatherConfigured(env)) return { skipped: "qweather_not_configured" };
  const now = deps.now();
  const previous = (await env.RELAY_KV.get(ALERTS_KV_KEY, { type: "json" })) as AlertsState | null;

  const storms = await activeStorms(env, deps);
  if (!storms) return { skipped: "storm_list_unavailable" };

  if (storms.count === 0) {
    const empty: AlertsState = { version: 1, updatedAtMs: now, cursor: 0, activeStorms: 0, points: [] };
    await env.RELAY_KV.put(ALERTS_KV_KEY, JSON.stringify(empty));
    return { activeStorms: 0, selected: 0, queried: 0, failed: 0, withAlerts: 0 };
  }

  const selected = selectPoints(WATCH_POINTS, storms.positions, intVar(env.ALERT_STORM_RADIUS_KM, DEFAULT_RADIUS_KM));
  const { batch, next } = batchOf(selected, previous?.cursor ?? 0, intVar(env.MAX_ALERT_POINTS_PER_RUN, DEFAULT_MAX_POINTS));
  const results = await mapLimited(batch, CONCURRENCY, (p) => queryPoint(p, env, deps));

  const previousById = new Map((previous?.points ?? []).map((p) => [p.id, p]));
  const fresh = new Map<string, PointState>();
  let failed = 0;
  batch.forEach((point, i) => {
    const alerts = results[i];
    if (alerts) fresh.set(point.id, { ...point, fetchedAtMs: now, alerts });
    else failed++;
  });

  const points: PointState[] = [];
  for (const point of selected) {
    const state = fresh.get(point.id) ?? previousById.get(point.id);
    if (state && now - state.fetchedAtMs <= KEEP_UNREFRESHED_MS) points.push({ ...state, ...point });
  }

  // Nothing could be queried at all: keep the previous list rather than publish an empty one.
  if (batch.length > 0 && failed === batch.length && previous) {
    return { skipped: "all_alert_queries_failed", activeStorms: storms.count, selected: selected.length, queried: batch.length, failed };
  }

  const state: AlertsState = { version: 1, updatedAtMs: now, cursor: next, activeStorms: storms.count, points };
  await env.RELAY_KV.put(ALERTS_KV_KEY, JSON.stringify(state));
  return {
    activeStorms: storms.count,
    selected: selected.length,
    queried: batch.length,
    failed,
    withAlerts: points.filter((p) => p.alerts.length > 0).length,
  };
}

function notExpired(alert: QWeatherAlert, nowMs: number): boolean {
  if (!alert.expireTime) return true;
  const t = Date.parse(alert.expireTime);
  return Number.isNaN(t) || t > nowMs;
}

/** Public shape (see the app's RelayModels.kt): only points that currently have alerts. */
export async function serveAlerts(env: Env, deps: Deps): Promise<Response> {
  const state = (await env.RELAY_KV.get(ALERTS_KV_KEY, { type: "json", cacheTtl: 60 })) as AlertsState | null;
  const now = deps.now();
  const points = (state?.points ?? [])
    .map((p) => ({ id: p.id, name: p.name, lat: p.lat, lon: p.lon, alerts: p.alerts.filter((a) => notExpired(a, now)) }))
    .filter((p) => p.alerts.length > 0);
  const body = JSON.stringify({ version: 1, updatedAtMs: state?.updatedAtMs ?? 0, points });
  return jsonResponse(body, 200, ALERTS_MAX_AGE_SECONDS);
}
