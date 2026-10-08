import { type Budgets, UpstreamBudget } from "./budget";
import { secondsUntilLocalMidnight } from "./clock";
import type { Deps, Env } from "./env";
import { checkKnown } from "./guard";
import { errorResponse, jsonResponse } from "./http";
import { cacheKey, type UpstreamRoute } from "./routes";
import { edgeMatch, edgePut, fetchAndStore, readFresh, readStored } from "./store";
import { sourceConfigured } from "./upstream";

export { readFresh, writeFresh } from "./store";

/** Provider error answers are cached briefly so a broken key cannot be hammered. */
export const ERROR_CACHE_SECONDS = 60;
/** 404 unknown_storm at the edge: short, so a storm that just appeared is not blocked for long. */
export const UNKNOWN_STORM_CACHE_SECONDS = 60;
/** A stale copy served while over budget is re-checked this often. */
export const STALE_MAX_AGE_SECONDS = 60;
const NOT_CONFIGURED_CACHE_SECONDS = 300;

/** Over the daily budget: the last good answer (any age) if KV has one, else 503 until local midnight. */
async function overBudget(route: UpstreamRoute, env: Env, deps: Deps, nowMs: number): Promise<Response> {
  const stale = await readStored(route, env, nowMs);
  if (stale) {
    const response = jsonResponse(stale.body, 200, STALE_MAX_AGE_SECONDS);
    response.headers.set("X-Relay-Stale", "1");
    await edgePut(deps, cacheKey(route), response);
    return response;
  }
  const response = errorResponse(503, "budget_exhausted");
  response.headers.set("Retry-After", String(secondsUntilLocalMidnight(nowMs)));
  return response;
}

async function fromProvider(route: UpstreamRoute, env: Env, deps: Deps, budget: UpstreamBudget, nowMs: number): Promise<Response> {
  const key = cacheKey(route);
  const known = await checkKnown(route, env, deps, budget);
  if (known === "unknown") {
    const response = errorResponse(404, "unknown_storm", UNKNOWN_STORM_CACHE_SECONDS);
    await edgePut(deps, key, response);
    return response;
  }
  if (budget.exhausted) return overBudget(route, env, deps, nowMs);
  if (known === "unavailable") return errorResponse(502, "upstream_unavailable");

  let result;
  try {
    result = await fetchAndStore(route, env, deps, budget, nowMs);
  } catch {
    return errorResponse(502, "upstream_unavailable");
  }
  if (result.ok) return jsonResponse(result.body, 200, route.ttlSeconds);

  // Provider said no (bad id, quota, auth…): pass its answer through unchanged, briefly cached.
  // 5xx from the provider becomes 502 so it is clearly not the relay's own failure.
  const status = result.status >= 500 ? 502 : result.status;
  const response = jsonResponse(result.body, status, ERROR_CACHE_SECONDS);
  await edgePut(deps, key, response);
  return response;
}

/**
 * Edge cache (per location) → KV (global) → provider. Only successful provider answers are
 * stored in KV; error answers are kept in the edge cache for [ERROR_CACHE_SECONDS].
 * A provider call needs a known storm id (guard.ts) and room in the daily budget (budget.ts).
 * [clientKey] is a salted hash of the client IP, used only for the upstream rate limit.
 */
export async function proxy(route: UpstreamRoute, env: Env, deps: Deps, clientKey: string): Promise<Response> {
  const key = cacheKey(route);
  const hit = await edgeMatch(deps, key);
  if (hit) return hit;

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

  const budget = await UpstreamBudget.load(route.source, env, now);
  return fromProvider(route, env, deps, budget, now);
}

/**
 * Same cache path without rate limiting or the known-storm guard (lists only), for the cron
 * job. Respects the daily budget. Null on any failure or when over budget.
 */
export async function cachedJson(route: UpstreamRoute, env: Env, deps: Deps, budgets: Budgets): Promise<unknown | null> {
  const now = deps.now();
  const fresh = await readFresh(route, env, now);
  if (fresh) return JSON.parse(fresh.body);
  if (!sourceConfigured(route.source, env)) return null;
  const budget = await budgets.get(route.source);
  if (budget.exhausted) return null;
  try {
    const result = await fetchAndStore(route, env, deps, budget, now);
    return result.ok ? JSON.parse(result.body) : null;
  } catch {
    return null;
  }
}
