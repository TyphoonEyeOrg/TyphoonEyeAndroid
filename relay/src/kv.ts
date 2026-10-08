import type { Deps, Env } from "./env";

/**
 * KV is best effort. On the Workers Free plan KV allows 1,000 writes/day per account and
 * 1 write/second per key; over the limit (or on any KV error) the relay keeps working from
 * the edge cache and the providers instead of failing the request or the cron run.
 * Logs carry no key names, parameters or client data.
 */
export async function kvGetJson(env: Env, key: string, cacheTtl?: number): Promise<unknown | null> {
  const read = await kvTryGetJson(env, key, cacheTtl);
  return read.ok ? read.value : null;
}

/** Like kvGetJson, but tells a failed read apart from a missing key. Never throws. */
export async function kvTryGetJson(env: Env, key: string, cacheTtl?: number): Promise<{ ok: true; value: unknown | null } | { ok: false }> {
  try {
    return { ok: true, value: await env.RELAY_KV.get(key, cacheTtl ? { type: "json", cacheTtl } : { type: "json" }) };
  } catch {
    console.warn("relay: KV read failed");
    return { ok: false };
  }
}

/** True when stored; false (never throws) when KV refused or failed. */
export async function kvPut(env: Env, key: string, value: string, options?: { expirationTtl?: number }): Promise<boolean> {
  try {
    await env.RELAY_KV.put(key, value, options);
    return true;
  } catch {
    console.warn("relay: KV write failed");
    return false;
  }
}

/**
 * Runs [task] after the response when the runtime offers `waitUntil` (Workers fetch
 * handler); otherwise awaits it (tests, cron). [task] must not throw.
 */
export async function inBackground(deps: Deps, task: Promise<unknown>): Promise<void> {
  if (deps.waitUntil) deps.waitUntil(task);
  else await task;
}
