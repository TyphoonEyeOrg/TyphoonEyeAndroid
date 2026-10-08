import { localDate } from "./clock";
import type { Env } from "./env";
import { kvPut, kvTryGetJson } from "./kv";
import type { Source } from "./routes";

/**
 * Global daily upstream budget per provider, so that no client (or bug) can burn the shared
 * Juhe / QWeather quota. Counted in KV per UTC+8 day (`budget:v1:<source>:<YYYY-MM-DD>`).
 *
 * Batched per isolate to stay far inside the KV write limits (Free plan: 1,000 writes/day,
 * 1 write/s per key): each Worker isolate counts its provider calls in memory and merges
 * them into KV (read, add, write) once it has FLUSH_EVERY_CALLS pending or
 * FLUSH_INTERVAL_MS have passed since its last flush, whichever comes first, after the
 * response (waitUntil). The cron job flushes at the end of every run.
 *
 * The budget check is the last KV value this isolate saw (re-read at most every
 * REFRESH_INTERVAL_MS) plus its own pending calls. It is a backstop, not an exact meter:
 * - isolates do not see each other's pending calls, and concurrent merges can lose
 *   increments, so the budget can be overshot somewhat;
 * - pending calls in an isolate that is evicted before flushing are lost;
 * - at the UTC+8 date change, pending calls of the previous day are dropped (that day's
 *   counter is never read again) and counting starts fresh.
 * KV failures fail OPEN: requests are served, the pending calls are kept and retried at the
 * next flush (not before FLUSH_INTERVAL_MS), and kv.ts logs one line.
 */

export const DEFAULT_DAILY_BUDGET: Record<Source, number> = { juhe: 400, qweather: 4000 };
export const FLUSH_EVERY_CALLS = 20;
export const FLUSH_INTERVAL_MS = 5 * 60 * 1000;
export const REFRESH_INTERVAL_MS = 60 * 1000;
const COUNTER_TTL_SECONDS = 2 * 24 * 60 * 60;

interface Counter {
  count: number;
}

/** This isolate's view of one source's counter for one UTC+8 date. */
interface Tally {
  date: string;
  /** Last value read from (or written to) KV. */
  known: number;
  knownAtMs: number;
  /** Calls made by this isolate that are not in KV yet. */
  pending: number;
  lastFlushMs: number;
  /** After a failed flush: no new attempt before this time. */
  retryAtMs: number;
  flushing: boolean;
}

const tallies = new Map<Source, Tally>();

/** Forgets this isolate's counts (tests: simulates a fresh isolate). */
export function resetBudgetMemory(): void {
  tallies.clear();
}

/** Read-only view of this isolate's tally (tests and debugging). */
export function budgetMemory(source: Source): Readonly<Tally> | undefined {
  return tallies.get(source);
}

export function budgetKey(source: Source, nowMs: number): string {
  return keyFor(source, localDate(nowMs));
}

function keyFor(source: Source, date: string): string {
  return `budget:v1:${source}:${date}`;
}

export function dailyLimit(source: Source, env: Env): number {
  const raw = source === "juhe" ? env.JUHE_DAILY_BUDGET : env.QWEATHER_DAILY_BUDGET;
  const n = Number(raw);
  return raw !== undefined && raw.trim() !== "" && Number.isFinite(n) && n >= 0 ? Math.floor(n) : DEFAULT_DAILY_BUDGET[source];
}

function countOf(value: unknown): number {
  const count = (value as Counter | null)?.count;
  return typeof count === "number" && count > 0 ? count : 0;
}

/** The tally for today (UTC+8); a new day starts from zero (previous day's pending dropped). */
function tally(source: Source, nowMs: number): Tally {
  const date = localDate(nowMs);
  let t = tallies.get(source);
  if (!t || t.date !== date) {
    t = { date, known: 0, knownAtMs: Number.NEGATIVE_INFINITY, pending: 0, lastFlushMs: nowMs, retryAtMs: 0, flushing: false };
    tallies.set(source, t);
  }
  return t;
}

/** Re-reads the KV counter if this isolate's copy is older than REFRESH_INTERVAL_MS. */
async function refresh(source: Source, env: Env, t: Tally, nowMs: number): Promise<void> {
  if (nowMs - t.knownAtMs < REFRESH_INTERVAL_MS) return;
  t.knownAtMs = nowMs; // also after a failed read: no read storm while KV is down
  const read = await kvTryGetJson(env, keyFor(source, t.date));
  // max(): KV is eventually consistent and may still return a value older than our own write.
  if (read.ok) t.known = Math.max(t.known, countOf(read.value));
}

function due(t: Tally, nowMs: number): boolean {
  if (t.pending === 0 || t.flushing || nowMs < t.retryAtMs) return false;
  return t.pending >= FLUSH_EVERY_CALLS || nowMs - t.lastFlushMs >= FLUSH_INTERVAL_MS;
}

/** Merges [t]'s pending calls into KV: read, add, write. Never throws. */
async function merge(source: Source, env: Env, t: Tally, nowMs: number): Promise<void> {
  t.flushing = true;
  const delta = t.pending;
  try {
    const key = keyFor(source, t.date);
    const read = await kvTryGetJson(env, key);
    const total = read.ok ? countOf(read.value) + delta : 0;
    if (!read.ok || !(await kvPut(env, key, JSON.stringify({ count: total } satisfies Counter), { expirationTtl: COUNTER_TTL_SECONDS }))) {
      t.retryAtMs = nowMs + FLUSH_INTERVAL_MS; // keep the delta, retry later
      return;
    }
    t.pending -= delta; // calls made during the merge stay pending
    t.known = Math.max(t.known, total);
    t.knownAtMs = nowMs;
    t.lastFlushMs = nowMs;
    t.retryAtMs = 0;
  } finally {
    t.flushing = false;
  }
}

/**
 * Starts a merge for [source] when one is due (or whenever calls are pending, with
 * [force]); returns its promise for waitUntil, or null when there is nothing to do.
 */
export function flushIfDue(source: Source, env: Env, nowMs: number, force = false): Promise<void> | null {
  const t = tallies.get(source);
  if (!t || t.date !== localDate(nowMs)) return null;
  if (force ? t.pending === 0 || t.flushing : !due(t, nowMs)) return null;
  return merge(source, env, t, nowMs);
}

/** Both sources (fetch handler: called on every request, costs nothing unless due). */
export function flushDue(env: Env, nowMs: number, force = false): Promise<void> | null {
  const runs = (["juhe", "qweather"] as const).map((s) => flushIfDue(s, env, nowMs, force)).filter((p) => p !== null);
  return runs.length ? Promise.all(runs).then(() => undefined) : null;
}

/** One request's (or cron run's) handle on a source's budget. */
export class UpstreamBudget {
  private constructor(
    readonly source: Source,
    readonly limit: number,
    private readonly nowMs: number,
  ) {}

  static async load(source: Source, env: Env, nowMs: number): Promise<UpstreamBudget> {
    await refresh(source, env, tally(source, nowMs), nowMs);
    return new UpstreamBudget(source, dailyLimit(source, env), nowMs);
  }

  /** Last known KV value + this isolate's pending calls. */
  get used(): number {
    const t = tally(this.source, this.nowMs);
    return t.known + t.pending;
  }

  get remaining(): number {
    return Math.max(0, this.limit - this.used);
  }

  get exhausted(): boolean {
    return this.remaining <= 0;
  }

  /** Records [n] provider calls (made or about to be made) in this isolate's tally. */
  spend(n = 1): void {
    tally(this.source, this.nowMs).pending += n;
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

  /** Cron: merge every pending call of this isolate into KV now. */
  async flushAll(): Promise<void> {
    await flushDue(this.env, this.nowMs, true);
  }
}
