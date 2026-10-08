import type { Deps, Env } from "./env";
import { errorResponse, jsonResponse } from "./http";
import { cacheKey, type UpstreamRoute } from "./routes";
import { callUpstream, sourceConfigured } from "./upstream";

/** Provider error answers are cached briefly so a broken key cannot be hammered. */
export const ERROR_CACHE_SECONDS = 60;
const NOT_CONFIGURED_CACHE_SECONDS = 300;

interface Stored {
  fetchedAtMs: number;
  body: string;
}

function edgeCacheRequest(key: string): Request {
  return new Request(`https://relay-cache.invalid/${encodeURIComponent(key)}`);
}

async function edgePut(deps: Deps, key: string, response: Response): Promise<void> {
  if (!deps.cache) return;
  try {
    await deps.cache.put(edgeCacheRequest(key), response.clone());
  } catch {
    // Cache is best effort.
  }
}

/** Fresh copy from KV (shared by all Cloudflare locations), or null. */
export async function readFresh(route: UpstreamRoute, env: Env, nowMs: number): Promise<{ body: string; ageSeconds: number } | null> {
  const stored = (await env.RELAY_KV.get(cacheKey(route), { type: "json" })) as Stored | null;
  if (!stored || typeof stored.body !== "string" || typeof stored.fetchedAtMs !== "number") return null;
  const ageSeconds = (nowMs - stored.fetchedAtMs) / 1000;
  if (ageSeconds < 0 || ageSeconds >= route.ttlSeconds) return null;
  return { body: stored.body, ageSeconds };
}

export async function writeFresh(route: UpstreamRoute, env: Env, nowMs: number, body: string): Promise<void> {
  const value: Stored = { fetchedAtMs: nowMs, body };
  await env.RELAY_KV.put(cacheKey(route), JSON.stringify(value), {
    expirationTtl: Math.max(60, route.ttlSeconds * 2),
  });
}

/**
 * Edge cache (per location) → KV (global) → provider. Only successful provider answers are
 * stored in KV; error answers are kept in the edge cache for [ERROR_CACHE_SECONDS].
 * [clientKey] is a salted hash of the client IP, used only for the upstream rate limit.
 */
export async function proxy(route: UpstreamRoute, env: Env, deps: Deps, clientKey: string): Promise<Response> {
  const key = cacheKey(route);
  if (deps.cache) {
    const hit = await deps.cache.match(edgeCacheRequest(key));
    if (hit) return hit;
  }

  const now = deps.now();
  const fresh = await readFresh(route, env, now);
  if (fresh) {
    const response = jsonResponse(fresh.body, 200, route.ttlSeconds - fresh.ageSeconds);
    await edgePut(deps, key, response);
    return response;
  }

  if (!sourceConfigured(route.source, env)) {
    return errorResponse(503, "source_not_configured", NOT_CONFIGURED_CACHE_SECONDS);
  }

  if (env.RL_UPSTREAM) {
    const { success } = await env.RL_UPSTREAM.limit({ key: clientKey });
    if (!success) return errorResponse(429, "rate_limited");
  }

  let result;
  try {
    result = await callUpstream(route, env, deps);
  } catch {
    return errorResponse(502, "upstream_unavailable");
  }

  if (result.ok) {
    await writeFresh(route, env, now, result.body);
    const response = jsonResponse(result.body, 200, route.ttlSeconds);
    await edgePut(deps, key, response);
    return response;
  }

  // Provider said no (bad id, quota, auth…): pass its answer through unchanged, briefly cached.
  // 5xx from the provider becomes 502 so it is clearly not the relay's own failure.
  const status = result.status >= 500 ? 502 : result.status;
  const response = jsonResponse(result.body, status, ERROR_CACHE_SECONDS);
  await edgePut(deps, key, response);
  return response;
}

/** Same cache path without rate limiting, for the cron job. Null on any failure. */
export async function cachedJson(route: UpstreamRoute, env: Env, deps: Deps): Promise<unknown | null> {
  const now = deps.now();
  const fresh = await readFresh(route, env, now);
  if (fresh) return JSON.parse(fresh.body);
  if (!sourceConfigured(route.source, env)) return null;
  try {
    const result = await callUpstream(route, env, deps);
    if (!result.ok) return null;
    await writeFresh(route, env, now, result.body);
    return JSON.parse(result.body);
  } catch {
    return null;
  }
}
