/** Minimal binding shapes, so the code also type-checks and runs under plain Node tests. */
export interface KVLike {
  get(key: string, options: { type: "json"; cacheTtl?: number }): Promise<unknown>;
  put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>;
}

export interface RateLimiterLike {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export interface CacheLike {
  match(request: Request): Promise<Response | undefined>;
  put(request: Request, response: Response): Promise<void>;
}

export interface Env {
  /** Secrets (`wrangler secret put …`). Any of them may be missing; that source is then off. */
  JUHE_KEY?: string;
  QWEATHER_API_KEY?: string;
  /** Per-account QWeather API host, e.g. `abc123xyz.re.qweatherapi.com`. */
  QWEATHER_HOST?: string;
  /**
   * QWeather JWT auth (recommended; used instead of QWEATHER_API_KEY when all four are set):
   * credential ID (`kid`), project ID (`sub`), developer ID (`iss`, "Q" + 9 letters/digits,
   * console → Settings) and the Ed25519 private key as PKCS#8 PEM.
   */
  QWEATHER_KID?: string;
  QWEATHER_PROJECT_ID?: string;
  QWEATHER_DEVELOPER_ID?: string;
  QWEATHER_PRIVATE_KEY?: string;
  /** Optional secret salt for hashing client IPs into rate-limit keys. */
  IP_HASH_SALT?: string;

  /** KV namespace: shared response cache + the alert list written by the cron. */
  RELAY_KV: KVLike;
  /** Every request, per client (hashed IP). */
  RL_REQUESTS?: RateLimiterLike;
  /** Requests that would reach a provider (cache miss), per client (hashed IP). */
  RL_UPSTREAM?: RateLimiterLike;

  /** Plain vars from wrangler.toml. */
  ALERT_STORM_RADIUS_KM?: string;
  MAX_ALERT_POINTS_PER_RUN?: string;
  /** Global daily provider-call budgets (counted per UTC+8 day in KV; see budget.ts). */
  JUHE_DAILY_BUDGET?: string;
  QWEATHER_DAILY_BUDGET?: string;
}

/** Injected so tests can run without the Workers runtime. */
export interface Deps {
  fetch: typeof fetch;
  cache: CacheLike | null;
  now: () => number;
  /** Workers `ctx.waitUntil` in the fetch handler; absent in tests and the cron job. */
  waitUntil?: (promise: Promise<unknown>) => void;
}
