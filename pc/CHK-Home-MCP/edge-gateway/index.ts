/**
 * CHK Home MCP external HTTPS 443 gateway (Supabase Edge Functions).
 *
 * Isolated from unrelated Supabase functions and from the Render relay.
 * This function executes NO filesystem/browser operations; it only proxies
 * authenticated MCP requests to the owner's Windows Tailscale Funnel 8443.
 * Authentication is the 256-bit per-installation capability in the MCP path.
 *
 * NOTE: Rotate the formerly published capability before using real files.
 */
const WINDOWS_MCP = "https://pc-salon.taild9e4de.ts.net:8443";
const MAX_BYTES = 96 * 1024;
const MAX_TIMEOUT_MS = 23_000;
const CAP_PATH = /\/mcp\/([a-f0-9]{64})\/?$/;
const allowedHeaders = [
  "accept", "content-type", "mcp-protocol-version", "mcp-session-id", "origin"
];
const responseHeaders = [
  "content-type", "mcp-session-id", "mcp-protocol-version", "retry-after", "allow"
];

function responseJSON(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff"
    }
  });
}
function isSmallBody(request: Request): boolean {
  const length = request.headers.get("content-length");
  if (length === null) return true; // bounded by stream reader below
  const n = Number(length);
  return Number.isInteger(n) && n >= 0 && n <= MAX_BYTES;
}
async function safeBody(req: Request): Promise<Uint8Array> {
  const reader = req.body?.getReader();
  if (!reader) return new Uint8Array(0);
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_BYTES) throw new Error("over_limit");
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const buffer = new Uint8Array(length);
  let at = 0;
  for (const c of chunks) { buffer.set(c, at); at += c.byteLength; }
  return buffer;
}

Deno.serve(async (req: Request): Promise<Response> => {
  const path = new URL(req.url).pathname;
  // Privileged, non-destructive handshake test using the same public route
  // ChatGPT will use.  Nothing is logged and the response contains no token.
  const check = /\/self-test\/([a-f0-9]{64})\/?$/.exec(path);
  if (req.method === "GET" && check) {
    const cap = check[1];
    const dest = new URL(req.url);
    dest.pathname = path.replace(/\/self-test\/[a-f0-9]{64}\/?$/, "/mcp/" + cap);
    const cases = [
      { method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "CHK Supabase Gateway self-test", version: "1.0" } } },
      { method: "tools/list", params: {} },
      { method: "tools/call", params: { name: "server_info", arguments: {} } }
    ];
    const report = [];
    try {
      for (let i = 0; i < cases.length; i++) {
        const rpc = { jsonrpc: "2.0", id: i + 1, ...cases[i] };
        const resp = await fetch(dest.toString(), {
          method: "POST",
          headers: { "content-type": "application/json", "accept": "application/json, text/event-stream" },
          body: JSON.stringify(rpc),
          redirect: "manual",
          signal: AbortSignal.timeout(12000)
        });
        let body: any = null;
        try { body = await resp.json(); } catch (_) { }
        const correct = i === 0
          ? body?.result?.serverInfo?.name === "CHK Home MCP"
          : i === 1
          ? body?.result?.tools?.length === 10
          : body?.result?.isError === false;
        report.push({ step: cases[i].method, httpStatus: resp.status, valid: !!correct });
        if (!correct) break;
      }
      const passed = report.length === 3 && report.every((test) => test.valid);
      return responseJSON(200, {
        service: "CHK Home MCP gateway",
        passed,
        checks: report,
        render: false
      });
    } catch (reason) {
      return responseJSON(200, {
        service: "CHK Home MCP gateway", passed: false, checks: report,
        failureType: reason instanceof Error ? reason.name : "unknown"
      });
    }
  }
  if (req.method === "GET" && path.endsWith("/healthcheck")) {
    // Public status contains no tokens, filenames, or content.
    try {
      const upstream = await fetch(WINDOWS_MCP + "/mcp/", {
        method: "GET",
        redirect: "manual",
        signal: AbortSignal.timeout(8000)
      });
      return responseJSON(200, {
        service: "CHK MCP HTTPS gateway",
        host: "Windows through Tailscale Funnel",
        render: false,
        upstreamReachable: upstream.status === 405,
        upstreamHttpStatus: upstream.status
      });
    } catch (_) {
      return responseJSON(502, {
        service: "CHK MCP HTTPS gateway",
        render: false,
        upstreamReachable: false
      });
    }
  }

  const match = CAP_PATH.exec(path);
  if (!match) return responseJSON(404, { error: "Endpoint unavailable" });
  if (!["GET", "POST", "DELETE"].includes(req.method))
    return responseJSON(405, { error: "Method not allowed" });
  if (req.headers.get("origin"))
    return responseJSON(403, { error: "Browser requests forbidden" });
  if (!isSmallBody(req)) return responseJSON(413, { error: "Request too large" });

  const cap = match[1];
  let payload: Uint8Array | undefined = undefined;
  if (req.method === "POST") {
    try { payload = await safeBody(req); }
    catch (_) { return responseJSON(413, { error: "Request too large" }); }
  }
  const headers = new Headers();
  for (const key of allowedHeaders) {
    const value = req.headers.get(key);
    if (value) headers.set(key, value);
  }
  // Don't forward Supabase's identity headers/API keys or cookies.
  try {
    const upstream = await fetch(WINDOWS_MCP + "/mcp/" + cap, {
      method: req.method,
      headers,
      body: payload,
      redirect: "manual",
      signal: AbortSignal.timeout(MAX_TIMEOUT_MS)
    });
    const outHeaders = new Headers({
      "cache-control": "no-store",
      "x-content-type-options": "nosniff"
    });
    for (const key of responseHeaders) {
      const value = upstream.headers.get(key);
      if (value) outHeaders.set(key, value);
    }
    return new Response(upstream.body, {
      status: upstream.status,
      headers: outHeaders
    });
  } catch (_) {
    // Never log sensitive URL/path, content, or capability.
    return responseJSON(502, { error: "Windows MCP upstream not reachable" });
  }
});
