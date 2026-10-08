/** JSON response with the relay's fixed header set (nothing from the client is echoed). */
export function jsonResponse(body: string, status: number, maxAgeSeconds: number): Response {
  const headers = new Headers({
    "Content-Type": "application/json; charset=utf-8",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": maxAgeSeconds > 0 ? `public, max-age=${Math.floor(maxAgeSeconds)}` : "no-store",
  });
  return new Response(body, { status, headers });
}

export function errorResponse(status: number, code: string, maxAgeSeconds = 0): Response {
  const response = jsonResponse(JSON.stringify({ error: code }), status, maxAgeSeconds);
  if (status === 429) response.headers.set("Retry-After", "60");
  return response;
}
