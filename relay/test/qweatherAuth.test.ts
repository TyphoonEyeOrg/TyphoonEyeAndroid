import { beforeEach, describe, expect, it } from "vitest";
import { jwtConfigured, qweatherAuthHeader, qweatherJwt, resetJwtCache } from "../src/qweatherAuth";
import { scrubSecrets } from "../src/upstream";
import { makeEnv } from "./helpers";

const T0 = Date.UTC(2026, 9, 8, 1, 0, 0);

async function keyPair(): Promise<{ pair: CryptoKeyPair; pem: string }> {
  const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
  const b64 = btoa(String.fromCharCode(...pkcs8));
  const pem = `-----BEGIN PRIVATE KEY-----\n${b64.match(/.{1,64}/g)!.join("\n")}\n-----END PRIVATE KEY-----\n`;
  return { pair, pem };
}

const b64urlDecode = (x: string) => Uint8Array.from(atob(x.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
const decodeJson = (x: string) => JSON.parse(new TextDecoder().decode(b64urlDecode(x)));

function jwtEnv(pem: string, overrides: Record<string, string | undefined> = {}) {
  return makeEnv({
    QWEATHER_API_KEY: undefined,
    QWEATHER_KID: "KIDABC1234",
    QWEATHER_PROJECT_ID: "PROJ123456",
    QWEATHER_DEVELOPER_ID: "Q12345ABCD",
    QWEATHER_PRIVATE_KEY: pem,
    ...overrides,
  });
}

beforeEach(() => resetJwtCache());

describe("QWeather JWT", () => {
  it("has exactly {alg, kid} / {iss, sub, iat, exp} and a valid Ed25519 signature", async () => {
    const { pair, pem } = await keyPair();
    const token = await qweatherJwt(jwtEnv(pem), T0);
    const parts = token.split(".");
    expect(parts).toHaveLength(3);
    for (const part of parts) expect(part).toMatch(/^[A-Za-z0-9_-]+$/); // base64url, no padding
    const [h, p, s] = parts;

    expect(decodeJson(h)).toEqual({ alg: "EdDSA", kid: "KIDABC1234" });
    const payload = decodeJson(p);
    expect(Object.keys(payload).sort()).toEqual(["exp", "iat", "iss", "sub"]);
    expect(payload.iss).toBe("Q12345ABCD");
    expect(payload.sub).toBe("PROJ123456");
    expect(payload.iat).toBe(T0 / 1000 - 30);
    expect(payload.exp - payload.iat).toBe(900);
    expect(payload.exp - payload.iat).toBeLessThanOrEqual(86400);

    const ok = await crypto.subtle.verify({ name: "Ed25519" }, pair.publicKey, b64urlDecode(s), new TextEncoder().encode(`${h}.${p}`));
    expect(ok).toBe(true);
  });

  it("accepts a one-line PEM with literal \\n escapes", async () => {
    const { pair, pem } = await keyPair();
    const token = await qweatherJwt(jwtEnv(pem.trim().replace(/\n/g, "\\n")), T0);
    const [h, p, s] = token.split(".");
    expect(await crypto.subtle.verify({ name: "Ed25519" }, pair.publicKey, b64urlDecode(s), new TextEncoder().encode(`${h}.${p}`))).toBe(true);
  });

  it("is not configured without the developer ID (iss)", async () => {
    const { pem } = await keyPair();
    const env = jwtEnv(pem, { QWEATHER_DEVELOPER_ID: undefined });
    expect(jwtConfigured(env)).toBe(false);
    expect(await qweatherAuthHeader(env, T0)).toBeNull();
    expect(jwtConfigured(jwtEnv(pem, { QWEATHER_DEVELOPER_ID: "  " }))).toBe(false);
    expect(jwtConfigured(jwtEnv(pem))).toBe(true);
  });

  it("prefers JWT over the API key and never sends both", async () => {
    const { pem } = await keyPair();
    const header = await qweatherAuthHeader(jwtEnv(pem, { QWEATHER_API_KEY: "QW-SECRET-0123456789" }), T0);
    expect(header![0]).toBe("Authorization");
    expect(header![1]).toMatch(/^Bearer [\w-]+\.[\w-]+\.[\w-]+$/);
  });

  it("falls back to the API key when JWT is incomplete", async () => {
    const { pem } = await keyPair();
    const header = await qweatherAuthHeader(jwtEnv(pem, { QWEATHER_API_KEY: "QW-SECRET-0123456789", QWEATHER_DEVELOPER_ID: undefined }), T0);
    expect(header).toEqual(["X-QW-Api-Key", "QW-SECRET-0123456789"]);
  });

  it("reuses the cached token, and re-signs when iss changes or the token nears expiry", async () => {
    const { pem } = await keyPair();
    const first = await qweatherJwt(jwtEnv(pem), T0);
    expect(await qweatherJwt(jwtEnv(pem), T0 + 5 * 60_000)).toBe(first);

    const otherIss = await qweatherJwt(jwtEnv(pem, { QWEATHER_DEVELOPER_ID: "Q99999ZZZZ" }), T0 + 5 * 60_000);
    expect(otherIss).not.toBe(first);
    expect(decodeJson(otherIss.split(".")[1]).iss).toBe("Q99999ZZZZ");

    const later = await qweatherJwt(jwtEnv(pem, { QWEATHER_DEVELOPER_ID: "Q99999ZZZZ" }), T0 + 19 * 60_000);
    expect(later).not.toBe(otherIss);
    expect(decodeJson(later.split(".")[1]).iat).toBe((T0 + 19 * 60_000) / 1000 - 30);
  });

  it("scrubs the private key body from echoed error bodies", async () => {
    const { pem } = await keyPair();
    const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s+/g, "");
    const out = scrubSecrets(`{"code":"401","echo":"${body}"}`, jwtEnv(pem));
    expect(out).not.toContain(body);
    expect(out).toContain("****");
  });
});
