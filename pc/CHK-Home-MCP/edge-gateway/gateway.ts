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
// Windows may wait 30s for Android. Stay below the 150s Edge idle limit.
const MAX_TIMEOUT_MS = 55_000;
const VERSION = "1.1.0";
const CAP_PATH = /\/mcp\/([a-f0-9]{64})\/?$/;
const allowedHeaders = [
  "accept", "content-type", "mcp-protocol-version", "mcp-session-id"
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
      "x-content-type-options": "nosniff",
      "x-chk-gateway-version": VERSION
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
      if (length > MAX_BYTES) { await reader.cancel(); throw new Error("over_limit"); }
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

function toolObject(body: any): Record<string, any> | null {
  if (body?.result?.isError !== false || !Array.isArray(body?.result?.content)) return null;
  try {
    const value = JSON.parse(body.result.content.find((item: any) => item.type === "text")?.text);
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch { return null; }
}
function replaceToolObject(body: any, value: unknown) {
  body.result.content.find((item: any) => item.type === "text").text = JSON.stringify(value);
}
function describeEfficientTools(body: any) {
  if (!Array.isArray(body?.result?.tools)) return;
  for (const tool of body.result.tools) {
    if (tool.name === "server_info") tool.description +=
      " Inclut aussi android_channel (état récent, file, commandes reçues) en une seule requête. Une connexion récente ne prouve pas l'exécution ni l'affichage.";
    if (tool.name === "android_command") tool.description +=
      " Économiser les appels : inutile de vérifier android_status avant chaque action. browser_batch regroupe 1..12 étapes action/args. Pour une tâche persistante Android, action=android_action, args={action:files_task,args:{operation:submit,id:identifiant_stable,steps:[{id:etape1,action:nom_android,args:{...}}]}} (1..50 étapes, mode autonome requis). Consulter files_capabilities avant une fonction nouvelle. Après pending, garder le même id et consulter android_result ; ne pas renvoyer la commande.";
    if (tool.name === "android_result") tool.description +=
      " Après pending, espacer les consultations d'au moins 3 secondes. Ne pas relancer android_command pour attendre le résultat.";
  }
}

type Fetcher = (input: string | URL | Request, init?: RequestInit) => Promise<Response>;

export function createGateway(fetcher: Fetcher = fetch, upstreamHost = WINDOWS_MCP) {
  return async (req: Request): Promise<Response> => {
    const path = new URL(req.url).pathname;
    if (req.headers.get("origin"))
      return responseJSON(403, { error: "Browser requests forbidden" });
    // One Edge invocation: probe Windows directly instead of recursively calling
    // this function three times. This verifies forwarding, not Android execution.
    const check = /\/self-test\/([a-f0-9]{64})\/?$/.exec(path);
    if (req.method === "GET" && check) {
      const cap = check[1];
      const dest = upstreamHost + "/mcp/" + cap;
      const cases = [
        { method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "CHK Supabase Gateway self-test", version: VERSION } } },
        { method: "tools/list", params: {} },
        { method: "tools/call", params: { name: "server_info", arguments: {} } },
        { method: "tools/call", params: { name: "android_status", arguments: {} } }
      ];
      const report = [];
      let toolCount = 0;
      let version = "";
      let info: any = null;
      let android: any = null;
      let identity = "not_provided_by_relay";
      try {
        for (let i = 0; i < cases.length; i++) {
          const rpc = { jsonrpc: "2.0", id: i + 1, ...cases[i] };
          const resp = await fetcher(dest, {
            method: "POST",
            headers: { "content-type": "application/json", "accept": "application/json, text/event-stream" },
            body: JSON.stringify(rpc),
            redirect: "manual",
            signal: AbortSignal.timeout(12000)
          });
          let body: any = null;
          try { body = await resp.json(); } catch (_) { }
          let correct = resp.status === 200 && body?.id === i + 1 && !body?.error;
          if (i === 0) {
            version = body?.result?.serverInfo?.version;
            correct &&= body?.result?.serverInfo?.name === "CHK Home MCP" && typeof version === "string";
          } else if (i === 1) {
            const tools = body?.result?.tools;
            toolCount = Array.isArray(tools) ? tools.length : 0;
            correct &&= toolCount > 0 && ["server_info", "android_status", "android_command", "android_result"]
              .every((name) => tools.some((tool: any) => tool.name === name));
          } else if (i === 2) {
            info = toolObject(body);
            correct &&= info?.name === "CHK Home MCP" && info?.version === version && info?.tools === toolCount;
            if (info?.server_id !== undefined) {
              const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(cap));
              const expected = Array.from(new Uint8Array(hash), (b) => b.toString(16).padStart(2, "0")).join("").slice(0, 16);
              identity = info.server_id === expected ? "matched" : "mismatch";
              correct &&= identity === "matched";
            }
          } else {
            android = toolObject(body);
            correct &&= typeof android?.android_recent === "boolean" && Number.isInteger(android?.queued) && Number.isInteger(android?.leased);
            if (android?.server_id !== undefined && info?.server_id !== undefined)
              correct &&= android.server_id === info.server_id;
          }
          report.push({ step: i < 2 ? cases[i].method : (cases[i].params as any).name, httpStatus: resp.status, valid: !!correct });
          if (!correct) break;
        }
        const passed = report.length === 4 && report.every((test) => test.valid);
        return responseJSON(passed ? 200 : 502, {
          service: "CHK Home MCP gateway",
          version: VERSION,
          server: info ? { name: info.name, version: info.version, tools: info.tools, identity } : null,
          android_channel: android ? { android_recent: android.android_recent, queued: android.queued, leased: android.leased } : null,
          execution_verified: false, display_verified: false, edge_invocations: 1,
          passed,
          checks: report,
          render: false
        });
      } catch (reason) {
        return responseJSON(502, {
          service: "CHK Home MCP gateway",
          version: VERSION,
          server: info ? { name: info.name, version: info.version, tools: info.tools, identity } : null,
          android_channel: android ? { android_recent: android.android_recent, queued: android.queued, leased: android.leased } : null,
          execution_verified: false, display_verified: false, edge_invocations: 1, passed: false, checks: report,
          failureType: reason instanceof Error ? reason.name : "unknown"
        });
      }
    }
    if (req.method === "GET" && path.endsWith("/healthcheck")) {
      // Public status contains no tokens, filenames, or content.
      try {
        const upstream = await fetcher(upstreamHost + "/mcp/", {
          method: "GET",
          redirect: "manual",
          signal: AbortSignal.timeout(8000)
        });
        const reachable = upstream.status === 405;
        await upstream.body?.cancel();
        return responseJSON(reachable ? 200 : 502, {
          service: "CHK MCP HTTPS gateway",
          version: VERSION,
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
    if (!isSmallBody(req)) return responseJSON(413, { error: "Request too large" });
  
    const cap = match[1];
    let payload: Uint8Array | undefined = undefined;
    let rpc: any = null;
    if (req.method === "POST") {
      try { payload = await safeBody(req); }
      catch (_) { return responseJSON(413, { error: "Request too large" }); }
      try { rpc = JSON.parse(new TextDecoder().decode(payload)); } catch { /* Windows validates JSON-RPC. */ }
    }
    const headers = new Headers();
    for (const key of allowedHeaders) {
      const value = req.headers.get(key);
      if (value) headers.set(key, value);
    }
    // Don't forward Supabase's identity headers/API keys or cookies.
    try {
      const upstream = await fetcher(upstreamHost + "/mcp/" + cap, {
        method: req.method,
        headers,
        body: payload,
        redirect: "manual",
        signal: AbortSignal.timeout(MAX_TIMEOUT_MS)
      });
      const outHeaders = new Headers({
        "cache-control": "no-store",
        "x-content-type-options": "nosniff",
        "x-chk-gateway-version": VERSION
      });
      for (const key of responseHeaders) {
        const value = upstream.headers.get(key);
        if (value) outHeaders.set(key, value);
      }
      if (upstream.status === 200 && (rpc?.method === "tools/list" ||
          (rpc?.method === "tools/call" && ["server_info", "android_result"].includes(rpc?.params?.name)))) {
        const body = await upstream.json();
        if (rpc.method === "tools/list") describeEfficientTools(body);
        else {
          const value = toolObject(body);
          if (value && rpc.params.name === "server_info" && value.name === "CHK Home MCP") {
            // Two direct PC reads in one client request. Do not cache connection
            // state, nor send a command merely to report connection state.
            let channel: any = null;
            try {
              const status = await fetcher(upstreamHost + "/mcp/" + cap, {
                method: "POST", headers: { "content-type": "application/json", accept: "application/json" },
                body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "android_status", arguments: {} } }),
                redirect: "manual", signal: AbortSignal.timeout(8000)
              });
              if (status.status === 200) channel = toolObject(await status.json());
              else await status.body?.cancel();
            } catch { /* Preserve the verified Windows result. */ }
            replaceToolObject(body, { ...value, android_channel: channel || { available: false }, gateway: { version: VERSION, edge_invocations: 1 } });
          } else if (value?.state === "pending") {
            replaceToolObject(body, { ...value, recommended_poll_delay_ms: 3000, resend_command: false });
          }
        }
        return new Response(JSON.stringify(body), { status: upstream.status, headers: outHeaders });
      }
      return new Response(upstream.body, {
        status: upstream.status,
        headers: outHeaders
      });
    } catch (reason) {
      // No automatic POST retry: Android may already have executed the action.
      const timeout = reason instanceof Error && ["TimeoutError", "AbortError"].includes(reason.name);
      return responseJSON(timeout ? 504 : 502, {
        error: timeout ? "Windows MCP response timed out" : "Windows MCP response unavailable",
        execution_state: "unknown", retry_command: false,
        message: "Ne pas renvoyer une commande incertaine. Consulter son résultat ou l'état Android avant toute reprise."
      });
    }
  };

}
