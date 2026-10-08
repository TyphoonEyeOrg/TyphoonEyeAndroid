import { describe, expect, it } from "vitest";
import { ALERT_CRON_INTERVAL_MINUTES, KEEP_UNREFRESHED_MS } from "../src/alerts";
import { DEFAULT_DAILY_BUDGET } from "../src/budget";
import toml from "../wrangler.toml?raw";

/** wrangler.toml is the single place for cron and rate limits; keep code/docs in sync with it. */

function rateLimit(name: string): { limit: number; period: number } {
  const block = toml.split("[[ratelimits]]").find((b: string) => b.includes(`name = "${name}"`));
  if (!block) throw new Error(`no rate limit ${name}`);
  return {
    limit: Number(/limit\s*=\s*([\d_]+)/.exec(block)![1].replace(/_/g, "")),
    period: Number(/period\s*=\s*(\d+)/.exec(block)![1]),
  };
}

describe("wrangler.toml", () => {
  it("runs the alert cron every 30 minutes, matching the code constant", () => {
    expect(/crons\s*=\s*\["\*\/30 \* \* \* \*"\]/.test(toml)).toBe(true);
    expect(ALERT_CRON_INTERVAL_MINUTES).toBe(30);
    // A rotated-out point survives a full rotation plus missed runs, well inside the
    // app's 6 h staleness cutoff.
    expect(KEEP_UNREFRESHED_MS).toBe(2 * 60 * 60 * 1000);
  });

  it("uses loose per-IP limits for carrier-grade NAT", () => {
    expect(rateLimit("RL_REQUESTS")).toEqual({ limit: 600, period: 60 });
    expect(rateLimit("RL_UPSTREAM")).toEqual({ limit: 120, period: 60 });
  });

  it("keeps request logs off and no workers.dev URL", () => {
    expect(/\[observability\]\s*\nenabled\s*=\s*false/.test(toml)).toBe(true);
    expect(/^logpush\s*=\s*false/m.test(toml)).toBe(true);
    expect(/^workers_dev\s*=\s*false/m.test(toml)).toBe(true);
  });

  it("sets the daily upstream budgets as plain vars, matching the code defaults", () => {
    const v = (name: string) => Number(new RegExp(`^${name}\\s*=\\s*"(\\d+)"`, "m").exec(toml)?.[1]);
    expect(v("JUHE_DAILY_BUDGET")).toBe(DEFAULT_DAILY_BUDGET.juhe);
    expect(v("QWEATHER_DAILY_BUDGET")).toBe(DEFAULT_DAILY_BUDGET.qweather);
  });
});
