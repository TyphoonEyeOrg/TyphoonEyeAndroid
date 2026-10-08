import type { Env } from "./env";

/** QWeather auth header for one upstream request, or null when QWeather is not configured. */
export type AuthHeader = [string, string];

let cachedJwt: { token: string; expiresAtMs: number; fingerprint: string } | null = null;

export function qweatherConfigured(env: Env): boolean {
  return Boolean(env.QWEATHER_API_KEY?.trim()) || jwtConfigured(env);
}

export function jwtConfigured(env: Env): boolean {
  return Boolean(
    env.QWEATHER_KID?.trim() &&
      env.QWEATHER_PROJECT_ID?.trim() &&
      env.QWEATHER_DEVELOPER_ID?.trim() &&
      env.QWEATHER_PRIVATE_KEY?.trim(),
  );
}

/**
 * `https://<host>` from QWEATHER_HOST (bare host or https URL); the legacy shared host when
 * unset. Anything other than plain https is rejected so the key never travels in clear text.
 */
export function qweatherBaseUrl(env: Env): string {
  const raw = env.QWEATHER_HOST?.trim() ?? "";
  if (!raw) return "https://devapi.qweather.com";
  const url = new URL(raw.includes("://") ? raw : `https://${raw}`);
  if (url.protocol !== "https:") throw new Error("QWEATHER_HOST must be https");
  return `https://${url.host}`;
}

/**
 * JWT when fully configured (QWeather's recommended method; API KEY daily requests are limited
 * from 2027-01-01), otherwise the API key. Never both: QWeather may reject mixed auth.
 */
export async function qweatherAuthHeader(env: Env, nowMs: number): Promise<AuthHeader | null> {
  if (jwtConfigured(env)) return ["Authorization", `Bearer ${await qweatherJwt(env, nowMs)}`];
  const apiKey = env.QWEATHER_API_KEY?.trim();
  if (apiKey) return ["X-QW-Api-Key", apiKey];
  return null;
}

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Base64 body of a PKCS#8 PEM (literal "\\n" escapes allowed, as pasted into one-line secrets). */
export function pemBody(pem: string): string {
  return pem
    .replace(/\\n/g, "\n")
    .replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
}

function pemToPkcs8(pem: string): ArrayBuffer {
  const binary = atob(pemBody(pem));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

/**
 * EdDSA (Ed25519) JWT as documented at https://dev.qweather.com/docs/configuration/authentication/:
 * header {alg, kid}, payload {iss, sub, iat, exp} and nothing else (typ/aud/nbf are reserved).
 * Cached until shortly before expiry.
 */
export async function qweatherJwt(env: Env, nowMs: number): Promise<string> {
  const kid = env.QWEATHER_KID!.trim();
  const sub = env.QWEATHER_PROJECT_ID!.trim();
  const iss = env.QWEATHER_DEVELOPER_ID!.trim();
  const fingerprint = `${kid}|${sub}|${iss}|${pemBody(env.QWEATHER_PRIVATE_KEY!).slice(-16)}`;
  if (cachedJwt && cachedJwt.fingerprint === fingerprint && nowMs < cachedJwt.expiresAtMs - 60_000) {
    return cachedJwt.token;
  }
  const iat = Math.floor(nowMs / 1000) - 30;
  const exp = iat + 900; // QWeather allows at most 86400 s
  const enc = new TextEncoder();
  const header = base64url(enc.encode(JSON.stringify({ alg: "EdDSA", kid })));
  const payload = base64url(enc.encode(JSON.stringify({ iss, sub, iat, exp })));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToPkcs8(env.QWEATHER_PRIVATE_KEY!),
    { name: "Ed25519" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign({ name: "Ed25519" }, key, enc.encode(`${header}.${payload}`));
  const token = `${header}.${payload}.${base64url(new Uint8Array(signature))}`;
  cachedJwt = { token, expiresAtMs: exp * 1000, fingerprint };
  return token;
}

/** Test hook. */
export function resetJwtCache(): void {
  cachedJwt = null;
}
