/**
 * The relay's "day" follows China Standard Time (UTC+8, no DST), like most of its users:
 * daily upstream budgets reset at 00:00 UTC+8 and storm-list years are UTC+8 years.
 */
const LOCAL_OFFSET_MS = 8 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;
/** UTC+14 is the earliest time zone on Earth (Kiribati). */
const EARLIEST_OFFSET_MS = 14 * 60 * 60 * 1000;

/** `YYYY-MM-DD` in UTC+8. */
export function localDate(nowMs: number): string {
  return new Date(nowMs + LOCAL_OFFSET_MS).toISOString().slice(0, 10);
}

/** Calendar year in UTC+8. */
export function localYear(nowMs: number): number {
  return new Date(nowMs + LOCAL_OFFSET_MS).getUTCFullYear();
}

/** Whole seconds until the next 00:00 UTC+8 (at least 1). */
export function secondsUntilLocalMidnight(nowMs: number): number {
  const local = nowMs + LOCAL_OFFSET_MS;
  const nextMidnight = (Math.floor(local / DAY_MS) + 1) * DAY_MS;
  return Math.max(1, Math.ceil((nextMidnight - local) / 1000));
}

/**
 * Storm-list years the relay serves: the current and the previous UTC+8 year. On the evening
 * of 31 December (UTC+8) the next year is also accepted, because the app asks for its
 * device-local year and devices east of UTC+8 are already in January.
 */
export function allowedYears(nowMs: number): { min: number; max: number } {
  const current = localYear(nowMs);
  return { min: current - 1, max: new Date(nowMs + EARLIEST_OFFSET_MS).getUTCFullYear() };
}

export function yearAllowed(year: number, nowMs: number): boolean {
  const { min, max } = allowedYears(nowMs);
  return Number.isInteger(year) && year >= min && year <= max;
}
