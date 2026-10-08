import { describe, expect, it } from "vitest";
import { clientKey, handleFetch } from "../src/index";
import { clientRequest, FakeCache, FakeLimiter, FakeUpstream, json, JUHE_OK_EMPTY, makeDeps, makeEnv, QW_OK, SECRETS } from "./helpers";

const T0 = Date.UTC(2026, 9, 8, 1, 0, 0);

describe("forwarding", () => {
  it("adds the Juhe key server-side and returns the provider JSON unchanged", async () => {
    const body = { reason: "success", error_code: 0, result: { data: [{ tfid: "202609", name: "巴威" }] } };
    const upstream = new FakeUpstream(() => json(body));
    const res = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), makeEnv(), makeDeps(upstream, { now: T0 }));

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual(body);
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=600");
    const sent = new URL(upstream.requests[0].url);
    expect(`${sent.origin}${sent.pathname}`).toBe("https://apis.juhe.cn/fapigw/typhoon/active");
    expect(sent.searchParams.get("key")).toBe(SECRETS.JUHE_KEY);
  });

  it("sends the QWeather key as a header to the account host", async () => {
    const upstream = new FakeUpstream(() => json(QW_OK));
    await handleFetch(clientRequest("/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026"), makeEnv(), makeDeps(upstream, { now: T0 }));

    const sent = upstream.requests[0];
    expect(sent.url).toBe("https://abc123.re.qweatherapi.com/v7/tropical/storm-list?basin=NP&year=2026");
    expect(sent.headers.get("X-QW-Api-Key")).toBe(SECRETS.QWEATHER_API_KEY);
  });

  it("never forwards client headers or the client IP", async () => {
    const upstream = new FakeUpstream(() => json(QW_OK));
    const request = clientRequest("/v1/qweather/v7/tropical/storm-track?stormid=NP_2609", {
      "User-Agent": "okhttp/4.12.0",
      Cookie: "a=b",
      "X-Forwarded-For": "198.51.100.1",
      "X-QW-Api-Key": "client-supplied",
      Authorization: "Bearer client",
    });
    await handleFetch(request, makeEnv(), makeDeps(upstream, { now: T0 }));

    const headers = [...upstream.requests[0].headers.keys()].sort();
    expect(headers).toEqual(["accept", "user-agent", "x-qw-api-key"]);
    expect(upstream.requests[0].headers.get("X-QW-Api-Key")).toBe(SECRETS.QWEATHER_API_KEY);
    expect(upstream.requests[0].headers.get("User-Agent")).toMatch(/^TyphoonEye-Relay/);
    expect(upstream.requests[0].url).not.toContain("203.0.113.7");
  });

  it("scrubs a key that a provider echoes back", async () => {
    const upstream = new FakeUpstream(() => json({ reason: `bad key ${SECRETS.JUHE_KEY}`, error_code: 10001 }));
    const res = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), makeEnv(), makeDeps(upstream, { now: T0 }));
    const text = await res.text();
    expect(text).not.toContain(SECRETS.JUHE_KEY);
    expect(text).toContain("10001");
  });

  it("supports QWeather JWT secrets instead of an API key", async () => {
    const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
    const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
    const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8))}\n-----END PRIVATE KEY-----`;
    const env = makeEnv({ QWEATHER_API_KEY: undefined, QWEATHER_KID: "KID1", QWEATHER_PROJECT_ID: "PROJ1", QWEATHER_PRIVATE_KEY: pem });
    const upstream = new FakeUpstream(() => json(QW_OK));
    await handleFetch(clientRequest("/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026"), env, makeDeps(upstream, { now: T0 }));

    const auth = upstream.requests[0].headers.get("Authorization")!;
    expect(upstream.requests[0].headers.get("X-QW-Api-Key")).toBeNull();
    const [h, p, s] = auth.replace("Bearer ", "").split(".");
    const decode = (x: string) => JSON.parse(atob(x.replace(/-/g, "+").replace(/_/g, "/")));
    expect(decode(h)).toEqual({ alg: "EdDSA", kid: "KID1" });
    expect(decode(p).sub).toBe("PROJ1");
    const sig = Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
    const ok = await crypto.subtle.verify({ name: "Ed25519" }, pair.publicKey, sig, new TextEncoder().encode(`${h}.${p}`));
    expect(ok).toBe(true);
  });
});

describe("caching", () => {
  it("serves repeats from the edge cache, then from KV in another location", async () => {
    const upstream = new FakeUpstream(() => json(JUHE_OK_EMPTY));
    const env = makeEnv();
    const clock = { now: T0 };
    await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, makeDeps(upstream, clock));
    await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, makeDeps(upstream, clock, upstream.requests.length ? new FakeCache() : null));
    clock.now += 9 * 60 * 1000;
    const fromKv = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, makeDeps(upstream, clock, null));

    expect(upstream.requests).toHaveLength(1);
    expect(fromKv.headers.get("Cache-Control")).toBe("public, max-age=60");
  });

  it("refetches a list after 10 minutes and a detail after 30", async () => {
    const upstream = new FakeUpstream((r) => json(r.url.includes("storm-list") ? QW_OK : { code: "200", track: [] }));
    const env = makeEnv();
    const clock = { now: T0 };
    const get = (path: string) => handleFetch(clientRequest(path), env, makeDeps(upstream, clock, null));
    const list = "/v1/qweather/v7/tropical/storm-list?basin=NP&year=2026";
    const track = "/v1/qweather/v7/tropical/storm-track?stormid=NP_2609";

    await get(list);
    await get(track);
    clock.now += 11 * 60 * 1000;
    await get(list);
    await get(track);
    expect(upstream.requests).toHaveLength(3);
    clock.now += 20 * 60 * 1000;
    await get(track);
    expect(upstream.requests).toHaveLength(4);
  });

  it("does not store provider errors in KV, caches them briefly at the edge", async () => {
    const upstream = new FakeUpstream(() => json({ reason: "超过每日可允许请求次数", error_code: 10012 }));
    const env = makeEnv();
    const res = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), env, makeDeps(upstream, { now: T0 }));

    expect(res.status).toBe(200);
    expect((await res.json()).error_code).toBe(10012);
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=60");
    expect(env.RELAY_KV.store.size).toBe(0);
  });
});

describe("failures and limits", () => {
  it("returns 502 when the provider is unreachable", async () => {
    const upstream = new FakeUpstream(() => {
      throw new TypeError("network");
    });
    const res = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), makeEnv(), makeDeps(upstream, { now: T0 }));
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ error: "upstream_unavailable" });
  });

  it("maps provider 5xx to 502 and passes 4xx through", async () => {
    const env = makeEnv();
    const r500 = await handleFetch(
      clientRequest("/v1/qweather/v7/tropical/storm-track?stormid=NP_2609"),
      env,
      makeDeps(new FakeUpstream(() => json({ code: "500" }, 500)), { now: T0 }),
    );
    const r401 = await handleFetch(
      clientRequest("/v1/qweather/v7/tropical/storm-forecast?stormid=NP_2609"),
      env,
      makeDeps(new FakeUpstream(() => json({ code: "401" }, 401)), { now: T0 }),
    );
    expect(r500.status).toBe(502);
    expect(r401.status).toBe(401);
  });

  it("answers 503 for a source without a secret, without calling anyone", async () => {
    const upstream = new FakeUpstream(() => json(JUHE_OK_EMPTY));
    const res = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), makeEnv({ JUHE_KEY: undefined }), makeDeps(upstream, { now: T0 }));
    expect(res.status).toBe(503);
    expect(upstream.requests).toHaveLength(0);
  });

  it("rate-limits per client with a hashed key, before and at the upstream", async () => {
    const upstream = new FakeUpstream(() => json(JUHE_OK_EMPTY));
    const requests = new FakeLimiter(false);
    const blocked = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), makeEnv({ RL_REQUESTS: requests }), makeDeps(upstream, { now: T0 }));
    expect(blocked.status).toBe(429);
    expect(blocked.headers.get("Retry-After")).toBe("60");
    expect(requests.keys[0]).toMatch(/^[0-9a-f]{32}$/);
    expect(requests.keys[0]).not.toContain("203.0.113.7");

    const upstreamLimiter = new FakeLimiter(false);
    const missBlocked = await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/active"), makeEnv({ RL_UPSTREAM: upstreamLimiter }), makeDeps(upstream, { now: T0 }));
    expect(missBlocked.status).toBe(429);
    expect(upstream.requests).toHaveLength(0);
  });

  it("hashes the IP with the optional salt", async () => {
    const r = clientRequest("/v1/health");
    const a = await clientKey(r, makeEnv());
    const b = await clientKey(r, makeEnv({ IP_HASH_SALT: "other" }));
    expect(a).not.toBe(b);
  });

  it("only allows GET, rejects unknown paths and invalid ids without calling upstream", async () => {
    const upstream = new FakeUpstream(() => json(JUHE_OK_EMPTY));
    const deps = makeDeps(upstream, { now: T0 });
    const post = await handleFetch(new Request("https://te-relay.seamain.org/v1/alerts", { method: "POST", body: "{}" }), makeEnv(), deps);
    expect(post.status).toBe(405);
    expect((await handleFetch(clientRequest("/v1/qweather/weatheralert/v1/current/22.54/114.06"), makeEnv(), deps)).status).toBe(404);
    expect((await handleFetch(clientRequest("/v1/juhe/fapigw/typhoon/detail?tfid=abc"), makeEnv(), deps)).status).toBe(400);
    expect(upstream.requests).toHaveLength(0);
  });

  it("health reports which sources are configured, never the secrets", async () => {
    const res = await handleFetch(clientRequest("/v1/health"), makeEnv({ JUHE_KEY: undefined }), makeDeps(new FakeUpstream(() => json({})), { now: T0 }));
    const text = await res.text();
    expect(JSON.parse(text)).toEqual({ ok: true, sources: { juhe: false, qweather: true }, alertsUpdatedAtMs: 0 });
    expect(text).not.toContain(SECRETS.QWEATHER_API_KEY);
  });
});
