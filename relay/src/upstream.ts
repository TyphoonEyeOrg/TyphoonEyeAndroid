import type { Deps, Env } from "./env";
import { pemBody, qweatherAuthHeader, qweatherBaseUrl, qweatherConfigured } from "./qweatherAuth";
import type { Source, UpstreamRoute } from "./routes";

export const JUHE_BASE_URL = "https://apis.juhe.cn";
const USER_AGENT = "TyphoonEye-Relay/1 (+https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid)";

export function sourceConfigured(source: Source, env: Env): boolean {
  return source === "juhe" ? Boolean(env.JUHE_KEY?.trim()) : qweatherConfigured(env);
}

export interface UpstreamResult {
  status: number;
  body: string;
  /** Provider-level success (Juhe `error_code` 0, QWeather `code` "200"). */
  ok: boolean;
}

/** Removes any secret value that a provider might echo back in an error body. */
export function scrubSecrets(body: string, env: Env): string {
  let out = body;
  const privateKey = env.QWEATHER_PRIVATE_KEY ? pemBody(env.QWEATHER_PRIVATE_KEY) : undefined;
  for (const secret of [env.JUHE_KEY, env.QWEATHER_API_KEY, privateKey]) {
    const s = secret?.trim();
    if (s && s.length >= 6) out = out.split(s).join("****");
  }
  return out;
}

/**
 * Builds a fresh request: only our own headers go upstream, never the client's
 * (no IP, User-Agent, cookies or anything else from the app).
 */
export async function upstreamRequest(url: string, env: Env, deps: Deps, source: Source): Promise<Request> {
  const headers = new Headers({ Accept: "application/json", "User-Agent": USER_AGENT });
  if (source === "qweather") {
    const auth = await qweatherAuthHeader(env, deps.now());
    if (auth) headers.set(auth[0], auth[1]);
  }
  return new Request(url, { method: "GET", headers, redirect: "error" });
}

export function upstreamUrl(route: UpstreamRoute, env: Env): string {
  const params = new URLSearchParams();
  if (route.source === "juhe") params.set("key", env.JUHE_KEY!.trim());
  for (const [k, v] of route.params) params.set(k, v);
  const query = params.toString();
  const base = route.source === "juhe" ? JUHE_BASE_URL : qweatherBaseUrl(env);
  return `${base}/${route.path}${query ? `?${query}` : ""}`;
}

function providerOk(source: Source, status: number, body: string): boolean {
  if (status !== 200) return false;
  try {
    const json = JSON.parse(body) as Record<string, unknown>;
    return source === "juhe" ? json.error_code === 0 : json.code === "200";
  } catch {
    return false;
  }
}

/** Throws on network failure; otherwise returns the provider's answer unchanged (scrubbed). */
export async function callUpstream(route: UpstreamRoute, env: Env, deps: Deps): Promise<UpstreamResult> {
  const request = await upstreamRequest(upstreamUrl(route, env), env, deps, route.source);
  const response = await deps.fetch(request);
  const body = scrubSecrets(await response.text(), env);
  return { status: response.status, body, ok: providerOk(route.source, response.status, body) };
}
