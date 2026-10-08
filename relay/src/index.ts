/**
 * TyphoonEye relay — Cloudflare Worker used by the F-Droid build of TyphoonEye.
 *
 * - Holds the weather API keys (Worker secrets); the app contains none.
 * - Forwards only the calls the app needs (path allowlist, validated parameters, storm ids
 *   that are in the current list), returns the provider's JSON unchanged, and caches it
 *   (list 10 min, detail 30 min). A global daily budget per provider caps provider calls.
 * - Serves official typhoon warnings for fixed coastal points (cron job), so the app never
 *   has to send a location.
 * - Uses the client IP only as a (salted, hashed) rate-limit key; never logs or stores it.
 *
 * Licensed under the Apache License, Version 2.0 (see ../LICENSE).
 */
import { refreshAlerts, serveAlerts, ALERTS_KV_KEY, type AlertsState } from "./alerts";
import { flushDue } from "./budget";
import type { CacheLike, Deps, Env } from "./env";
import { errorResponse, jsonResponse } from "./http";
import { inBackground, kvGetJson } from "./kv";
import { proxy } from "./proxy";
import { qweatherConfigured } from "./qweatherAuth";
import { matchRoute, RouteError } from "./routes";
import { sourceConfigured } from "./upstream";

interface Ctx {
  waitUntil(promise: Promise<unknown>): void;
}

export function defaultDeps(ctx?: Ctx): Deps {
  const caches = (globalThis as unknown as { caches?: { default?: CacheLike } }).caches;
  return {
    fetch: (input, init) => fetch(input, init),
    cache: caches?.default ?? null,
    now: () => Date.now(),
    waitUntil: ctx ? (promise) => ctx.waitUntil(promise) : undefined,
  };
}

/** Salted SHA-256 of the client IP. The IP itself is not kept, logged or forwarded. */
export async function clientKey(request: Request, env: Env): Promise<string> {
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const data = new TextEncoder().encode(`${env.IP_HASH_SALT ?? "typhooneye-relay"}|${ip}`);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", data));
  return Array.from(digest.slice(0, 16), (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function handleFetch(request: Request, env: Env, deps: Deps): Promise<Response> {
  try {
    return await routeRequest(request, env, deps);
  } finally {
    // Merge this isolate's batched budget counts into KV when due (after the response).
    const flush = flushDue(env, deps.now());
    if (flush) await inBackground(deps, flush);
  }
}

async function routeRequest(request: Request, env: Env, deps: Deps): Promise<Response> {
  if (request.method !== "GET") {
    const response = errorResponse(405, "method_not_allowed");
    response.headers.set("Allow", "GET");
    return response;
  }

  let matched;
  try {
    matched = matchRoute(new URL(request.url), deps.now());
  } catch (e) {
    if (e instanceof RouteError) return errorResponse(e.status, e.code, e.code === "not_found" ? 3600 : e.status === 404 ? 60 : 0);
    return errorResponse(400, "bad_request");
  }

  const key = await clientKey(request, env);
  if (env.RL_REQUESTS) {
    const { success } = await env.RL_REQUESTS.limit({ key });
    if (!success) return errorResponse(429, "rate_limited");
  }

  switch (matched.kind) {
    case "health": {
      const state = (await kvGetJson(env, ALERTS_KV_KEY, 60)) as AlertsState | null;
      const body = JSON.stringify({
        ok: true,
        sources: { juhe: sourceConfigured("juhe", env), qweather: qweatherConfigured(env) },
        alertsUpdatedAtMs: state?.updatedAtMs ?? 0,
      });
      return jsonResponse(body, 200, 60);
    }
    case "alerts":
      return serveAlerts(env, deps);
    case "upstream":
      return proxy(matched, env, deps, key);
  }
}

export default {
  async fetch(request: Request, env: Env, ctx: Ctx): Promise<Response> {
    try {
      // KV writes run after the response (waitUntil) so they never delay or fail it.
      return await handleFetch(request, env, defaultDeps(ctx));
    } catch {
      // No details: nothing about the request may end up in logs.
      return errorResponse(500, "internal_error");
    }
  },

  async scheduled(_controller: unknown, env: Env, ctx: Ctx): Promise<void> {
    ctx.waitUntil(refreshAlerts(env, defaultDeps()).catch(() => undefined));
  },
};
