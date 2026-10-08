import type { UpstreamBudget } from "./budget";
import type { Deps, Env } from "./env";
import { jsonResponse } from "./http";
import { inBackground, kvGetJson, kvPut } from "./kv";
import { cacheKey, type UpstreamRoute } from "./routes";
import { callUpstream, type UpstreamResult } from "./upstream";

/**
 * Response storage: edge cache (per Cloudflare location) and KV (global).
 * KV entries are kept for STALE_KEEP_SECONDS although they are only fresh for the route's
 * TTL, so that a stale copy can still be served when the daily upstream budget runs out.
 */
export const STALE_KEEP_SECONDS = 6 * 60 * 60;

interface Stored {
  fetchedAtMs: number;
  body: string;
}

function edgeCacheRequest(key: string): Request {
  return new Request(`https://relay-cache.invalid/${encodeURIComponent(key)}`);
}

export async function edgeMatch(deps: Deps, key: string): Promise<Response | undefined> {
  if (!deps.cache) return undefined;
  try {
    return await deps.cache.match(edgeCacheRequest(key));
  } catch {
    return undefined; // Cache is best effort.
  }
}

export async function edgePut(deps: Deps, key: string, response: Response): Promise<void> {
  if (!deps.cache) return;
  try {
    await deps.cache.put(edgeCacheRequest(key), response.clone());
  } catch {
    // Cache is best effort.
  }
}

/** Last successful answer from KV, whatever its age (within STALE_KEEP_SECONDS), or null. */
export async function readStored(route: UpstreamRoute, env: Env, nowMs: number): Promise<{ body: string; ageSeconds: number } | null> {
  const stored = (await kvGetJson(env, cacheKey(route))) as Stored | null;
  if (!stored || typeof stored.body !== "string" || typeof stored.fetchedAtMs !== "number") return null;
  const ageSeconds = (nowMs - stored.fetchedAtMs) / 1000;
  if (ageSeconds < 0) return null;
  return { body: stored.body, ageSeconds };
}

/** Fresh copy from KV (shared by all Cloudflare locations), or null. */
export async function readFresh(route: UpstreamRoute, env: Env, nowMs: number): Promise<{ body: string; ageSeconds: number } | null> {
  const stored = await readStored(route, env, nowMs);
  return stored && stored.ageSeconds < route.ttlSeconds ? stored : null;
}

/** Best effort (see kv.ts): never throws; false when KV did not store it. */
export async function writeFresh(route: UpstreamRoute, env: Env, nowMs: number, body: string): Promise<boolean> {
  const value: Stored = { fetchedAtMs: nowMs, body };
  return kvPut(env, cacheKey(route), JSON.stringify(value), {
    expirationTtl: Math.max(route.ttlSeconds * 2, STALE_KEEP_SECONDS),
  });
}

/**
 * One provider call, charged to [budget]. A successful answer is stored in KV (after the
 * response when the runtime has waitUntil) and in this location's edge cache.
 * Throws on network failure or redirect (see upstream.ts).
 */
export async function fetchAndStore(route: UpstreamRoute, env: Env, deps: Deps, budget: UpstreamBudget, nowMs: number): Promise<UpstreamResult> {
  budget.spend();
  const result = await callUpstream(route, env, deps);
  if (result.ok) {
    // A failed KV write (e.g. Free-plan write limit) must not fail the request.
    await inBackground(deps, writeFresh(route, env, nowMs, result.body));
    await edgePut(deps, cacheKey(route), jsonResponse(result.body, 200, route.ttlSeconds));
  }
  return result;
}
