import { localDate } from "./clock";
import type { Env } from "./env";
import { kvGetJson, kvPut } from "./kv";
import type { Source } from "./routes";

/**
 * Global daily upstream budget per provider, so that no client (or bug) can burn the shared
 * Juhe / QWeather quota. Counted in KV per UTC+8 day (`budget:v1:<source>:<YYYY-MM-DD>`).
 *
 * Best effort by design: KV is eventually consistent and has no atomic increment, so
 * concurrent requests in different locations can undercount a little. A KV failure fails
 * OPEN (the request is served, kv.ts logs a one-line warning): losing the counter must
 * never take the relay down. One request or cron run writes the counter at most once.
 */

export const DEFAULT_DAILY_BUDGET: Record<Source, number> = { juhe: 400, qweather: 4000 };
const COUNTER_TTL_SECONDS = 2 * 24 * 60 * 60;

interface Counter {
  count: number;
}

export function budgetKey(source: Source, nowMs: number): string {
  return `budget:v1:${source}:${localDate(nowMs)}`;
}

export function dailyLimit(source: Source, env: Env): number {
  const raw = source === "juhe" ? env.JUHE_DAILY_BUDGET : env.QWEATHER_DAILY_BUDGET;
  const n = Number(raw);
  return raw !== undefined && raw.trim() !== "" && Number.isFinite(n) && n >= 0 ? Math.floor(n) : DEFAULT_DAILY_BUDGET[source];
}

async function readCount(env: Env, key: string): Promise<number> {
  const stored = (await kvGetJson(env, key)) as Counter | null;
  return stored && typeof stored.count === "number" && stored.count > 0 ? stored.count : 0;
}

export class UpstreamBudget {
  private pending = 0;

  private constructor(
    private readonly env: Env,
    readonly source: Source,
    readonly key: string,
    readonly limit: number,
    private used: number,
  ) {}

  static async load(source: Source, env: Env, nowMs: number): Promise<UpstreamBudget> {
    const key = budgetKey(source, nowMs);
    return new UpstreamBudget(env, source, key, dailyLimit(source, env), await readCount(env, key));
  }

  get remaining(): number {
    return Math.max(0, this.limit - this.used - this.pending);
  }

  get exhausted(): boolean {
    return this.remaining <= 0;
  }

  /** Records [n] upstream calls (made or about to be made). */
  spend(n = 1): void {
    this.pending += n;
  }

  /** Adds the spent calls to the KV counter. Never throws; no-op when nothing was spent. */
  async flush(): Promise<void> {
    if (this.pending === 0) return;
    const add = this.pending;
    this.pending = 0;
    // Re-read so calls counted elsewhere since load() are not overwritten.
    const latest = Math.max(this.used, await readCount(this.env, this.key));
    this.used = latest + add;
    await kvPut(this.env, this.key, JSON.stringify({ count: this.used } satisfies Counter), { expirationTtl: COUNTER_TTL_SECONDS });
  }
}

/** Lazily loaded budgets for both sources (cron job). */
export class Budgets {
  private readonly loaded = new Map<Source, Promise<UpstreamBudget>>();
  constructor(
    private readonly env: Env,
    private readonly nowMs: number,
  ) {}

  get(source: Source): Promise<UpstreamBudget> {
    let b = this.loaded.get(source);
    if (!b) {
      b = UpstreamBudget.load(source, this.env, this.nowMs);
      this.loaded.set(source, b);
    }
    return b;
  }

  async flushAll(): Promise<void> {
    await Promise.all([...this.loaded.values()].map(async (b) => (await b).flush()));
  }
}
