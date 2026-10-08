import type { CacheLike, Deps, Env, KVLike, RateLimiterLike } from "../src/env";

export class FakeKV implements KVLike {
  store = new Map<string, string>();
  puts: { key: string; ttl?: number }[] = [];
  async get(key: string): Promise<unknown> {
    const v = this.store.get(key);
    return v === undefined ? null : JSON.parse(v);
  }
  async put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void> {
    this.store.set(key, value);
    this.puts.push({ key, ttl: options?.expirationTtl });
  }
}

export class FakeCache implements CacheLike {
  store = new Map<string, Response>();
  async match(request: Request): Promise<Response | undefined> {
    return this.store.get(request.url)?.clone();
  }
  async put(request: Request, response: Response): Promise<void> {
    this.store.set(request.url, response);
  }
}

export class FakeLimiter implements RateLimiterLike {
  keys: string[] = [];
  constructor(public allow = true) {}
  async limit({ key }: { key: string }): Promise<{ success: boolean }> {
    this.keys.push(key);
    return { success: this.allow };
  }
}

export type Responder = (request: Request) => Response | Promise<Response>;

export class FakeUpstream {
  requests: Request[] = [];
  constructor(public responder: Responder) {}
  fetch: typeof fetch = async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    this.requests.push(request);
    return this.responder(request);
  };
}

export const SECRETS = {
  JUHE_KEY: "JUHE-SECRET-0123456789",
  QWEATHER_API_KEY: "QW-SECRET-0123456789",
  QWEATHER_HOST: "abc123.re.qweatherapi.com",
};

export function makeEnv(overrides: Partial<Env> = {}): Env & { RELAY_KV: FakeKV } {
  return {
    ...SECRETS,
    RELAY_KV: new FakeKV(),
    RL_REQUESTS: new FakeLimiter(),
    RL_UPSTREAM: new FakeLimiter(),
    ...overrides,
  } as Env & { RELAY_KV: FakeKV };
}

export function makeDeps(upstream: FakeUpstream, clock: { now: number }, cache: CacheLike | null = new FakeCache()): Deps {
  return { fetch: upstream.fetch, cache, now: () => clock.now };
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export const JUHE_OK_EMPTY = { reason: "success", error_code: 0, result: { data: [] } };
export const QW_OK = { code: "200", storm: [] };
/** Lists that contain the ids most tests ask for (Juhe `202609`, QWeather `NP_2609`). */
export const JUHE_LIST = { reason: "success", error_code: 0, result: { data: [{ tfid: "202609", name: "巴威", lat: "21.5", lng: "116.5" }] } };
export const QW_LIST = { code: "200", storm: [{ id: "NP_2609", name: "Bavi", basin: "NP", year: "2026", isActive: "1" }] };
export const JUHE_LIST_KEY = "v1:juhe:fapigw/typhoon/active";
export const qwListKey = (year = 2026) => `v1:qweather:v7/tropical/storm-list?basin=NP&year=${year}`;

/** Puts a list answer into KV as the relay would have stored it at [fetchedAtMs]. */
export function seedList(kv: FakeKV, key: string, body: unknown, fetchedAtMs: number): void {
  kv.store.set(key, JSON.stringify({ fetchedAtMs, body: JSON.stringify(body) }));
}

/** Response-cache entries in KV (ignores budget counters and refresh gates). */
export function cachedKeys(kv: FakeKV): string[] {
  return [...kv.store.keys()].filter((k) => k.startsWith("v1:"));
}

export function clientRequest(path: string, headers: Record<string, string> = {}): Request {
  return new Request(`https://te-relay.seamain.org${path}`, {
    headers: { "CF-Connecting-IP": "203.0.113.7", ...headers },
  });
}
