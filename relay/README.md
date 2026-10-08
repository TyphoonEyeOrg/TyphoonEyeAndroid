# TyphoonEye relay

Cloudflare Worker used by the **F-Droid build** (`fdroid` flavor) of TyphoonEye.
It is reached at `https://te-relay.seamain.org` (hard-coded as `RELAY_BASE_URL` in
`app/build.gradle.kts`). The GitHub build does not use it.

- **Keys stay here.** The Juhe key and the QWeather credentials (JWT signing key or API key) are Worker secrets. The app contains none.
- **Allowlist only.** Exactly the provider calls the app makes, with validated parameters.
  Detail / track / forecast only for storm ids that are in the current list (see
  [Quota protection](#quota-protection)). Responses are the providers' JSON, unchanged, so the app parses them with the same code
  as the GitHub build.
- **Shared cache.** Lists 10 min, details / track / forecast 30 min (same as the app's own
  TTLs, TYP-51). Edge cache per Cloudflare location, then KV (global), then the provider.
  Only successful provider answers are stored in KV; error answers are cached for 60 s.
  KV is best effort: if a KV read or write fails (e.g. Free-plan write limit), requests still
  succeed from the edge cache or the provider (see [KV limits](#kv-limits-workers-free-plan)).
- **No user locations.** A cron job fetches typhoon-related official warnings for a fixed
  list of coastal points ([`data/coastal-points.json`](data/coastal-points.json), 54
  points: coastal mainland China, Hong Kong, Macau, Taiwan, Philippines, Japan, Korea,
  Vietnam). Every app downloads the same list and picks nearby points on the device.
  Official-warning coverage is therefore **coastal only**: a user more than 150 km from
  every point gets no official warnings, only the app's own intensity alerts.
- **No logs.** Observability and Logpush are off; the code logs nothing. The client IP is
  used only as a rate-limit key (salted SHA-256, never stored by the relay) and is never
  forwarded: upstream requests are built from scratch with the relay's own headers.

Licensed under the Apache License 2.0, like the app.

## Endpoints

All `GET`; anything else is `405`, unknown paths `404`, invalid parameters `400`.
Unknown query parameters are ignored (not forwarded, not part of the cache key).

| Relay path | Upstream | Validation | Cache |
|---|---|---|---|
| `/v1/juhe/fapigw/typhoon/active` | `https://apis.juhe.cn/fapigw/typhoon/active?key=…` | – | 10 min |
| `/v1/juhe/fapigw/typhoon/detail?tfid=` | `…/fapigw/typhoon/detail?key=…&tfid=` | `^\d{4}[A-Z]?\d{2}$`, in the Juhe active list | 30 min |
| `/v1/qweather/v7/tropical/storm-list?basin=NP&year=` | `{QWEATHER_HOST}/v7/tropical/storm-list` | basin `NP`, current or previous year (UTC+8)¹ | 10 min |
| `/v1/qweather/v7/tropical/storm-track?stormid=` | `{QWEATHER_HOST}/v7/tropical/storm-track` | `^NP_[0-9A-Z]{4}$`, in the storm-list of its year (`NP_26xx` → 2026) | 30 min |
| `/v1/qweather/v7/tropical/storm-forecast?stormid=` | `{QWEATHER_HOST}/v7/tropical/storm-forecast` | same as storm-track | 30 min |
| `/v1/alerts` | KV (written by the cron job) | – | 5 min |
| `/v1/health` | – (`{ok, sources:{juhe,qweather}, alertsUpdatedAtMs}`) | – | 1 min |

¹ On 31 December from 18:00 UTC+8 the next year is accepted too (devices east of UTC+8
are already in January and the app asks for its local year).

Relay status codes (body `{"error": "<code>"}`):

| Status | `error` | Meaning |
|---|---|---|
| `400` | `invalid_<param>` | parameter fails validation (incl. a year outside the window) |
| `404` | `not_found` | unknown path |
| `404` | `unknown_storm` | storm id not in the current list of that source, or of a year outside the window; no provider call (cached 60 s) |
| `429` | `rate_limited` | per-client rate limit (`Retry-After: 60`) |
| `502` | `upstream_unavailable` | provider unreachable, provider 5xx, or the storm list needed for the id check could not be fetched |
| `503` | `source_not_configured` | that provider has no secret configured |
| `503` | `budget_exhausted` | daily budget for that provider used up and no earlier answer stored (`Retry-After` = seconds until 00:00 UTC+8) |

Provider 4xx answers and provider-level errors (Juhe `error_code`, QWeather `code`) are
passed through unchanged. Over budget with an earlier answer stored, the relay returns it
with `200`, `X-Relay-Stale: 1` and `Cache-Control: max-age=60`.

`/v1/alerts` response:

```json
{
  "version": 1,
  "updatedAtMs": 1791421200000,
  "points": [
    { "id": "cn-shenzhen", "name": "深圳", "lat": 22.54, "lon": 114.06,
      "alerts": [ /* QWeather weatheralert objects, unchanged, typhoon-related only, not expired */ ] }
  ]
}
```

Only points that currently have alerts are listed. `updatedAtMs: 0` means the cron job has
not run yet. The app ignores a list older than 6 hours and, with location, uses points
within 150 km of the user (all points without location). Inland users farther than 150 km
from every point get no official warnings from this list (coastal coverage only); the app
still shows its own intensity alerts for nearby or very strong storms.

## Quota protection

All F-Droid users share one Juhe and one QWeather account, so the relay limits what a
single client (or a bug) can make it spend:

1. **Known storm ids only.** A detail / track / forecast request reaches the provider only
   for an id in the latest list of that source: the Juhe active list for `tfid`, the
   QWeather storm-list of the id's year for `stormid` (current and previous year; past
   storms of those years are allowed if their list has them). The list is read from the
   edge cache or KV (any age). If the id is missing and the list is older than 10 min, the
   list is refreshed **at most once per 10 min** (gate in the edge cache and KV) and checked
   again; otherwise the answer is `404 unknown_storm` with no provider call. Enumerating
   ids therefore costs at most one list call per 10 min, not one call per id.
2. **Year window.** storm-list only for basin `NP` and the current or previous UTC+8 year.
3. **Global daily budget** per provider (`JUHE_DAILY_BUDGET` = 400, `QWEATHER_DAILY_BUDGET`
   = 4000 in `wrangler.toml` `[vars]`; change and redeploy). Every provider call (app
   traffic and cron) is counted in KV under `budget:v1:<source>:<YYYY-MM-DD>` (UTC+8 date),
   so the budget resets at 00:00 UTC+8. Over budget, requests get the last good answer (KV
   keeps answers for 6 h for this) or `503 budget_exhausted` with `Retry-After` until
   midnight; the cron job skips its run and never queries more points than remain.
   QWeather's budget covers the cron's weather-alert calls too (up to 1,920/day).
   **Batched per isolate** to keep KV writes low: each Worker isolate counts its calls in
   memory and merges them into KV (read, add, write, after the response) once it has
   **20 calls pending or 5 minutes have passed** since its last flush; the cron job flushes
   at the end of every run. The check is the last KV value the isolate read (re-read at
   most once a minute) plus its own pending calls. It is a backstop, not an exact meter:
   isolates don't see each other's pending calls and concurrent merges can lose
   increments, so **some overshoot is possible**; counts still in memory when an isolate is
   evicted are lost; at 00:00 UTC+8 the previous day's pending calls are dropped (that
   counter is never read again). If KV fails the relay **fails open**: requests are served,
   the pending calls are kept and retried at the next flush (5 min later), and one line is
   logged (`relay: KV read failed` / `write failed`).
4. Per-client rate limits ([Rate limits](#rate-limits)).

## Cron job and provider cost

Every 30 minutes (`[triggers] crons` in `wrangler.toml`; keep `ALERT_CRON_INTERVAL_MINUTES`
in `src/alerts.ts` in sync, a test checks both):

1. Get the active-storm list through the same cache the app uses (Juhe if `JUHE_KEY` is set,
   else QWeather storm-list). **No active storm → no alert calls**, an empty list is stored.
2. With storm positions (Juhe), query only points within `ALERT_STORM_RADIUS_KM` (1500 km)
   of a storm; without positions (QWeather only), all 54 points.
3. At most `MAX_ALERT_POINTS_PER_RUN` (40) points per run, rotating, so a run stays under
   the Workers Free plan limit of 50 subrequests. With all 54 points selected, each point is
   refreshed every 30–60 min. A point not re-queried keeps its last answer for up to 2 hours
   (4 cron intervals).
4. If every alert query fails, or the KV write fails, the previous list is kept.
5. Calls count against the daily budget (see [Quota protection](#quota-protection)); the
   run is skipped while the QWeather budget is used up.

QWeather weather-alert calls (worst case, 40 points every run while a storm is active):
40 × 48 runs/day = **1,920 per active-storm day**. QWeather prices warnings in the "weather
and basic services" group: first 50,000 requests/month free, then CNY 0.0007 per request
(2025 price list), i.e. about **26 full storm-days per month** within the free calls and
≈ CNY 1.3 per storm-day after that. No active storm: 0 warning calls.
With Juhe positions and a single storm, typically 10–30 points are in range.
Typhoon (tropical) calls are priced separately by QWeather (no free tier, CNY 0.003 per
request); the shared cache keeps those to roughly 1 list call per 10 min plus 2 calls per
active storm per 30 min, independent of the number of users.

## KV limits (Workers Free plan)

Workers KV on the Free plan allows **1,000 writes per day** (per account) and **1 write per
second to the same key**; reads are 100,000/day. The relay writes:

| What | Writes/day |
|---|---|
| Juhe typhoon list (10-min freshness; the cron refreshes it every run, app traffic in between) | ≈ 48–144 |
| Alert list (cron, every 30 min) | 48 |
| Juhe detail, per active storm (30-min freshness) | ≈ 48 |
| QWeather storm-list, only when Juhe is unavailable | ≈ 144 |
| QWeather track + forecast, per active storm, only when Juhe is unavailable | ≈ 96 |
| Daily budget counters (batched: one merge per 20 calls or 5 min per isolate, one per cron run) | ≈ calls ÷ 20 + 48, e.g. ≈ 220–300 at 4,400 calls/day |
| Known-storm refresh gate (only when an unknown id forces a list refresh) | ≤ 144 per list |

The figures below are without the budget counters and the gate.
With Juhe working: at most ≈ 192 + 48 × storms, about 340/day with 3 active storms. If both
sources end up in use on the same day (Juhe failing part of the time): ≈ 336 + 144 × storms,
about 770/day with 3 storms, 910 with 4 and over 1,000 with 5. Simultaneous misses in several Cloudflare
locations can add a few duplicate writes.

When a write is refused (daily limit, per-key rate, or any KV error), nothing breaks: KV
access is best effort (`src/kv.ts`). The request still returns the provider's answer with
200, and the write runs after the response (`waitUntil`). Until the limit resets (00:00 UTC),
responses are shared only through each location's edge cache, so **more requests reach the
providers** (one per location per freshness window instead of one globally), which costs
provider quota. A failed cron write keeps the previous alert list (the app ignores lists
older than 6 h). The **Workers Paid** plan (USD 5/month, 1 million KV writes per month) is
**optional**: the batched budget counter fits easily in the Free plan, and Paid only adds
headroom for days with many active storms on both sources. The 1 write/second per key
limit stays but is harmless here. If the write limit is reached anyway, merges fail and the
counts stay in each isolate's memory (still enforced per isolate) until KV accepts writes
again; the known-id check keeps working from the lists in KV and the edge cache.

## Deploy (owner, once)

Requires a Cloudflare account with `seamain.org` on Cloudflare DNS, Node.js 20+.

```bash
cd relay
npm ci
npx wrangler login                     # opens a browser; owner's Cloudflare account

# 1. KV namespace → paste the printed id into wrangler.toml ([[kv_namespaces]] id)
npx wrangler kv namespace create RELAY_KV

# 2. Secrets — set only via the Cloudflare dashboard or wrangler on your own machine.
#    Dashboard: Workers & Pages → typhooneye-relay → Settings → Variables and Secrets
#    → Add (type Secret). Or, from this directory (prompted / piped; never paste values
#    into chat, Issue comments, or tool logs):
npx wrangler secret put JUHE_KEY
npx wrangler secret put QWEATHER_HOST           # e.g. abc123xyz.re.qweatherapi.com
#   QWeather, recommended: JWT (all four needed; see "QWeather auth" below)
npx wrangler secret put QWEATHER_KID            # credential ID → JWT header `kid`
npx wrangler secret put QWEATHER_PROJECT_ID     # project ID → payload `sub`
npx wrangler secret put QWEATHER_DEVELOPER_ID   # developer ID, Q + 9 letters/digits → payload `iss`
npx wrangler secret put QWEATHER_PRIVATE_KEY < ed25519-private.pem   # Ed25519 PKCS#8 PEM
#   QWeather, fallback only when JWT is not set up:
#   npx wrangler secret put QWEATHER_API_KEY
#   optional: npx wrangler secret put IP_HASH_SALT  # any random string

# 3. Deploy. The custom domain te-relay.seamain.org (DNS record + certificate) is created
#    from the `routes` entry in wrangler.toml.
npx wrangler deploy

# 4. Check
curl https://te-relay.seamain.org/v1/health
curl "https://te-relay.seamain.org/v1/juhe/fapigw/typhoon/active"
curl https://te-relay.seamain.org/v1/alerts      # updatedAtMs > 0 after the first cron run
```

QWeather auth
([docs](https://dev.qweather.com/docs/configuration/authentication/)):

- **JWT is the recommended path.** Generate a key pair
  (`openssl genpkey -algorithm ED25519 -out ed25519-private.pem` and
  `openssl pkey -pubout -in ed25519-private.pem > ed25519-public.pem`), add the **public**
  key as a JSON Web Token credential in the QWeather console (Project management), and set
  the four secrets above. The credential ID is `QWEATHER_KID`, the project ID
  `QWEATHER_PROJECT_ID`; the developer ID (console → Settings) is `QWEATHER_DEVELOPER_ID`.
  Keep the private key out of the repo.
- The relay signs `{alg: "EdDSA", kid}` / `{iss, sub, iat: now − 30 s, exp: iat + 900 s}`
  (nothing else: `typ`, `aud`, `nbf` are reserved by QWeather) and reuses the token until
  shortly before it expires.
- When all four JWT secrets are set, the relay uses JWT and ignores `QWEATHER_API_KEY`
  (QWeather may reject requests that mix auth methods). Otherwise it falls back to
  `QWEATHER_API_KEY`. QWeather limits daily requests made with an API KEY from
  2027-01-01, so do not rely on the fallback long term.
- If QWeather answers 401, check the token in the console's JWT validator (it only accepts
  tokens for your own account).

Notes:

- `te-relay.seamain.org` is a first-level subdomain, so the Universal SSL certificate
  covers it. If a DNS record with that name already exists, remove it first.
- `workers_dev = false`: no `*.workers.dev` URL (often unreachable from mainland China).
- The rate-limit `namespace_id`s (`41001`, `41002`) must be unique in the account.
- Rate limits: see [Rate limits](#rate-limits).
- Rotate a key: `npx wrangler secret put JUHE_KEY` again (takes effect immediately). For the
  QWeather JWT key, add the new public key in the console first, then put the new
  `QWEATHER_KID` and `QWEATHER_PRIVATE_KEY`, then delete the old credential.
- In the Cloudflare dashboard, keep Workers Logs / Logpush off for this Worker.

## Rate limits

Per client IP (salted hash) and per Cloudflare location, set in `wrangler.toml`
(`[[ratelimits]]` → `simple.limit`, `period` 10 or 60 s; change and redeploy):

| Binding | Counts | Limit |
|---|---|---|
| `RL_REQUESTS` | every request | **600 / minute** |
| `RL_UPSTREAM` | requests that miss both caches and would reach a provider | **120 / minute** |

The limits are loose on purpose: carrier-grade NAT puts many mobile users in mainland
China behind one public IP. One app refresh is about 2–5 requests and the app throttles
manual refreshes to once a minute, so 600/min leaves room for well over a hundred users on
one IP. `RL_UPSTREAM` limits cache misses per client; cache hits never count against it.
Storm-id enumeration and the shared provider quota are covered by the known-id check and
the daily budget ([Quota protection](#quota-protection)), which hold across all clients. Over a limit the relay answers `429` with
`Retry-After: 60`, and the app keeps showing its cached data. A test
(`test/config.test.ts`) pins the values, so update it together with `wrangler.toml`.

## Develop and test

```bash
npm ci
npm test            # vitest, upstream fetch / KV / cache / rate limiter are mocked
npm run typecheck
cp .dev.vars.example .dev.vars && npx wrangler dev --local   # dummy secrets only
```

`node_modules/`, `.wrangler/` and `.dev.vars` are git-ignored. This directory is not part of
the Gradle build, so F-Droid's Android build does not use it.
