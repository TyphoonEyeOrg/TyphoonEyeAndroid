import type { Env } from "./env";

/** QWeather auth header for one upstream request, or null when QWeather is not configured. */
export type AuthHeader = [string, string];

let cachedJwt: { token: string; expiresAtMs: number; fingerprint: string } | null = null;

export function qweatherConfigured(env: Env): boolean {
  return Boolean(env.QWEATHER_API_KEY?.trim()) || jwtConfigured(env);
}

function jwtConfigured(env: Env): boolean {
  return Boolean(env.QWEATHER_KID?.trim() && env.QWEATHER_PROJECT_ID?.trim() && env.QWEATHER_PRIVATE_KEY?.trim());
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

export async function qweatherAuthHeader(env: Env, nowMs: number): Promise<AuthHeader | null> {
  const apiKey = env.QWEATHER_API_KEY?.trim();
  if (apiKey) return ["X-QW-Api-Key", apiKey];
  if (!jwtConfigured(env)) return null;
  return ["Authorization", `Bearer ${await qweatherJwt(env, nowMs)}`];
}

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToPkcs8(pem: string): ArrayBuffer {
  const body = pem
    .replace(/\\n/g, "\n")
    .replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

/** EdDSA (Ed25519) JWT as documented by QWeather; cached until shortly before expiry. */
export async function qweatherJwt(env: Env, nowMs: number): Promise<string> {
  const kid = env.QWEATHER_KID!.trim();
  const sub = env.QWEATHER_PROJECT_ID!.trim();
  const fingerprint = `${kid}|${sub}`;
  if (cachedJwt && cachedJwt.fingerprint === fingerprint && nowMs < cachedJwt.expiresAtMs - 60_000) {
    return cachedJwt.token;
  }
  const iat = Math.floor(nowMs / 1000) - 30;
  const exp = iat + 900;
  const enc = new TextEncoder();
  const header = base64url(enc.encode(JSON.stringify({ alg: "EdDSA", kid })));
  const payload = base64url(enc.encode(JSON.stringify({ sub, iat, exp })));
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
