import type { UpstreamBudget } from "./budget";
import type { Deps, Env } from "./env";
import { jsonResponse } from "./http";
import { kvGetJson, kvPut } from "./kv";
import { cacheKey, type Source, type UpstreamRoute } from "./routes";
import { edgeMatch, edgePut, fetchAndStore, readStored } from "./store";

/**
 * Known-storm guard: a detail / track / forecast request only reaches the provider for a
 * storm id that is in the latest list of that source (Juhe active list for `tfid`, QWeather
 * storm-list of the id's year for `stormid`). Otherwise anyone could enumerate valid-looking
 * ids, each a cache miss, and burn the shared provider quota.
 *
 * The list comes from the edge cache or KV (any age). If the id is not in it and the list
 * is older than its TTL, the list is refreshed, at most once per list TTL across requests
 * (gate in the edge cache and KV, best effort), and checked again.
 */
export type Known = "known" | "unknown" | "unavailable";

/** Storm ids in a successful list answer, or null (provider error, unparsable). */
export function listIds(source: Source, body: string): Set<string> | null {
  try {
    const json = JSON.parse(body) as Record<string, unknown>;
    let items: unknown;
    let field: string;
    if (source === "juhe") {
      if (json.error_code !== 0) return null;
      items = (json.result as { data?: unknown } | null | undefined)?.data;
      field = "tfid";
    } else {
      if (json.code !== "200") return null;
      items = json.storm ?? [];
      field = "id";
    }
    if (!Array.isArray(items)) return null;
    const ids = new Set<string>();
    for (const item of items) {
      const id = (item as Record<string, unknown> | null)?.[field];
      if (typeof id === "string") ids.add(id);
    }
    return ids;
  } catch {
    return null;
  }
}

async function cachedList(list: UpstreamRoute, env: Env, deps: Deps, nowMs: number): Promise<{ ids: Set<string>; fresh: boolean } | null> {
  const hit = await edgeMatch(deps, cacheKey(list));
  if (hit && hit.status === 200) {
    // Edge entries expire with their max-age, so a hit is fresh.
    const ids = listIds(list.source, await hit.text());
    if (ids) return { ids, fresh: true };
  }
  const stored = await readStored(list, env, nowMs);
  const ids = stored ? listIds(list.source, stored.body) : null;
  return stored && ids ? { ids, fresh: stored.ageSeconds < list.ttlSeconds } : null;
}

/** True for the one caller (per list TTL, best effort) that may refresh [list]. */
async function claimRefresh(list: UpstreamRoute, env: Env, deps: Deps, nowMs: number): Promise<boolean> {
  const key = `guard:v1:${cacheKey(list)}`;
  if (await edgeMatch(deps, key)) return false;
  const last = (await kvGetJson(env, key)) as { atMs?: unknown } | null;
  if (last && typeof last.atMs === "number" && nowMs >= last.atMs && nowMs - last.atMs < list.ttlSeconds * 1000) return false;
  await edgePut(deps, key, jsonResponse("{}", 200, list.ttlSeconds));
  await kvPut(env, key, JSON.stringify({ atMs: nowMs }), { expirationTtl: Math.max(60, list.ttlSeconds) });
  return true;
}

/**
 * "known": go ahead. "unknown": the id is not in an up-to-date list (answer 404, no provider
 * call). "unavailable": no list could be read or fetched (provider trouble, budget, gate).
 */
export async function checkKnown(route: UpstreamRoute, env: Env, deps: Deps, budget: UpstreamBudget): Promise<Known> {
  const known = route.knownIn;
  if (!known) return "known";
  const now = deps.now();
  const cached = await cachedList(known.list, env, deps, now);
  if (cached?.ids.has(known.id)) return "known";
  if (cached?.fresh) return "unknown";

  const otherwise: Known = cached ? "unknown" : "unavailable";
  if (budget.exhausted || !(await claimRefresh(known.list, env, deps, now))) return otherwise;
  try {
    const result = await fetchAndStore(known.list, env, deps, budget, now);
    const ids = result.ok ? listIds(known.list.source, result.body) : null;
    if (!ids) return otherwise;
    return ids.has(known.id) ? "known" : "unknown";
  } catch {
    return otherwise;
  }
}
