package com.chk.agentbrowser;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owner-enabled, private MCP listener on Android. This service never contacts Render.
 *
 * The HTTP socket only accepts loopback and Tailscale 100.64/10 peers, and every
 * MCP request must provide a random 256-bit installation capability. It is NOT
 * publicly accessible to cloud ChatGPT: a separately verified public HTTPS
 * ingress would be required. Never publish the private capability in a log.
 */
final class DirectMcpServer implements AutoCloseable {
    static final int PORT = 8765;
    private static final String PREF = "browser_mcp_v1";
    private static final int MAX_BODY = 32768;
    private static final String PROTOCOL = "2025-06-18";
    interface Reply { void finish(boolean success, String result); }
    interface Runner {
        boolean active();
        JSONObject status();
        void execute(String action, JSONObject args, Reply callback);
    }
    private final Runner runner;
    private final String capability;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(2, 2, 0L,
        TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(6));
    private volatile ServerSocket listener;
    private volatile boolean closed;
    private Thread acceptThread;

    DirectMcpServer(Context context, Runner callback) {
        runner = callback;
        capability = secret(context);
    }

    static synchronized String secret(Context context) {
        android.content.SharedPreferences p = context.getApplicationContext()
            .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String existing = p.getString("direct_secret", "");
        if (existing.matches("[a-f0-9]{64}")) return existing;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        String value = out.toString();
        if (!p.edit().putString("direct_secret", value).commit())
            throw new IllegalStateException("Impossible de protéger le jeton local.");
        return value;
    }

    static String tailscaleIp() {
        try {
            Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
            while (networks != null && networks.hasMoreElements()) {
                NetworkInterface network = networks.nextElement();
                if (!network.isUp()) continue;
                Enumeration<InetAddress> ips = network.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    if (ip instanceof Inet4Address && inTailnet(ip))
                        return ip.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        // Known previous address, shown as provisional until checked on Android.
        return "100.86.142.102";
    }

    static String privateUrl(Context context) {
        return "http://" + tailscaleIp() + ":" + PORT + "/mcp/" + secret(context);
    }

    static boolean inTailnet(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 255) == 100
            && (bytes[1] & 255) >= 64 && (bytes[1] & 255) <= 127;
    }

    synchronized void start() throws IOException {
        if (listener != null || closed) return;
        ServerSocket opened = new ServerSocket();
        opened.setReuseAddress(true);
        opened.bind(new InetSocketAddress("0.0.0.0", PORT), 8);
        opened.setSoTimeout(1500);
        listener = opened;
        acceptThread = new Thread(() -> {
            while (!closed) {
                try {
                    Socket peer = opened.accept();
                    peer.setSoTimeout(20000);
                    try {
                        clients.execute(() -> {
                            try (Socket s = peer) { serve(s); }
                            catch (Exception ignored) { /* Never log capability or HTTP body. */ }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException busy) {
                        try { peer.close(); } catch (IOException ignored) {}
                    }
                } catch (java.net.SocketTimeoutException retry) {
                    // Check close flag periodically without holding a wake lock.
                } catch (IOException error) {
                    if (!closed && !opened.isClosed()) break;
                }
            }
        }, "CHK-Direct-MCP");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    boolean listening() { return listener != null && !listener.isClosed() && !closed; }

    private static String line(InputStream stream, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = stream.read()) != -1) {
            if (c == '\n') {
                byte[] value = out.toByteArray();
                int end = value.length > 0 && value[value.length - 1] == '\r'
                    ? value.length - 1 : value.length;
                return new String(value, 0, end, StandardCharsets.US_ASCII);
            }
            if (out.size() >= max) throw new IOException("En-tête trop long");
            out.write(c);
        }
        throw new IOException("Requête incomplète");
    }

    private void serve(Socket peer) throws Exception {
        OutputStream out = peer.getOutputStream();
        InetAddress remote = peer.getInetAddress();
        if (!remote.isLoopbackAddress() && !inTailnet(remote)) {
            send(out, 403, new JSONObject().put("error", "Réseau Tailscale requis"));
            return;
        }
        InputStream in = peer.getInputStream();
        String[] first = line(in, 2048).split(" ");
        if (first.length != 3) { send(out, 400, errorMessage("Requête invalide")); return; }
        String method = first[0], path = first[1];
        String authorization = "", origin = "";
        int contentLength = -1;
        boolean transferEncoding = false;
        for (int i = 0; i < 50; i++) {
            String header = line(in, 8192);
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon < 1) { send(out, 400, errorMessage("En-tête invalide")); return; }
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                if (contentLength != -1) { send(out, 400, errorMessage("Longueur dupliquée")); return; }
                try { contentLength = Integer.parseInt(value); }
                catch (NumberFormatException problem) { send(out, 400, errorMessage("Longueur invalide")); return; }
            } else if ("transfer-encoding".equals(name)) transferEncoding = true;
            else if ("authorization".equals(name)) authorization = value;
            else if ("origin".equals(name)) origin = value;
        }
        if (!origin.isEmpty()) { send(out, 403, errorMessage("Appels navigateur bloqués")); return; }
        if ("GET".equals(method) && "/health".equals(path)) {
            send(out, 200, new JSONObject().put("ok", listening())
                .put("server", "CHK Android").put("transport", "private-tailscale-http")
                .put("public_https", false).put("render", false));
            return;
        }
        boolean bearer = constantTime(authorization, "Bearer " + capability);
        boolean pathSecret = constantTime(path, "/mcp/" + capability);
        if (!bearer && !pathSecret) { send(out, 401, errorMessage("Accès MCP refusé")); return; }
        if (!"POST".equals(method)) { send(out, 405, errorMessage("POST requis")); return; }
        if (transferEncoding || contentLength < 0 || contentLength > MAX_BODY) {
            send(out, 413, errorMessage("Corps HTTP invalide")); return;
        }
        byte[] input = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int count = in.read(input, offset, contentLength - offset);
            if (count < 0) throw new IOException("Message tronqué");
            offset += count;
        }
        JSONObject request;
        try { request = new JSONObject(new String(input, StandardCharsets.UTF_8)); }
        catch (Exception bad) { send(out, 400, error(-32700, null, "JSON invalide")); return; }
        String action = request.optString("method", "");
        Object id = request.opt("id");
        if (!"2.0".equals(request.optString("jsonrpc"))) {
            send(out, 400, error(-32600, id, "JSON-RPC 2.0 attendu")); return;
        }
        if (!request.has("id")) {
            send(out, 202, new JSONObject());
            return;
        }
        if (!runner.active()) {
            send(out, 503, error(-32000, id, "Serveur MCP direct désactivé")); return;
        }
        JSONObject response;
        try {
            JSONObject args = request.optJSONObject("params");
            if (args == null) args = new JSONObject();
            JSONObject result;
            switch (action) {
                case "initialize":
                    result = new JSONObject()
                        .put("protocolVersion", PROTOCOL)
                        .put("serverInfo", new JSONObject().put("name", "CHK Agent Browser direct")
                            .put("version", "0.1"))
                        .put("capabilities", new JSONObject()
                            .put("tools", new JSONObject().put("listChanged", false)));
                    break;
                case "ping": result = new JSONObject(); break;
                case "tools/list": result = new JSONObject().put("tools", tools()); break;
                case "tools/call": result = call(args); break;
                default:
                    response = error(-32601, id, "Méthode MCP inconnue");
                    send(out, 200, response); return;
            }
            response = new JSONObject().put("jsonrpc", "2.0")
                .put("id", id).put("result", result);
        } catch (Exception failure) {
            response = error(-32602, id, "Arguments MCP invalides");
        }
        send(out, 200, response);
    }

    private static JSONObject tool(String name, String description, JSONObject properties,
                                   String... required) throws Exception {
        JSONArray needs = new JSONArray();
        for (String item : required) needs.put(item);
        return new JSONObject().put("name", name).put("description", description)
            .put("inputSchema", new JSONObject().put("type", "object")
                .put("properties", properties).put("required", needs)
                .put("additionalProperties", false));
    }
    private static JSONObject stringProp(String description) throws Exception {
        return new JSONObject().put("type", "string").put("description", description);
    }
    private static JSONArray tools() throws Exception {
        JSONArray all = new JSONArray();
        all.put(tool("browser_status", "Etat du serveur et du navigateur Android, sans Render.", new JSONObject()));
        all.put(tool("browser_tabs", "Liste les onglets CHK sur le téléphone.", new JSONObject()));
        all.put(tool("browser_read_page", "Lit la page HTML visible dans CHK.", new JSONObject()));
        all.put(tool("browser_open_url", "Ouvre une URL HTTPS publique dans CHK.",
            new JSONObject().put("url", stringProp("Adresse HTTPS")), "url"));
        all.put(tool("browser_click", "Clique un élément CSS sur la page CHK.",
            new JSONObject().put("selector", stringProp("Sélecteur CSS")), "selector"));
        all.put(tool("browser_type", "Saisit du texte dans un champ non protégé.",
            new JSONObject().put("selector", stringProp("Sélecteur CSS"))
                .put("text", stringProp("Texte")), "selector", "text"));
        all.put(tool("browser_scroll", "Défilement de la page CHK.",
            new JSONObject().put("direction", stringProp("up ou down"))));
        all.put(tool("browser_get_form", "Inspecte les champs non sensibles de la page.", new JSONObject()));
        return all;
    }

    private JSONObject call(JSONObject params) throws Exception {
        String tool = params.optString("name", "");
        JSONObject a = params.optJSONObject("arguments");
        if (a == null) a = new JSONObject();
        if ("browser_status".equals(tool)) return result(runner.status().toString(), false);
        String action;
        switch (tool) {
            case "browser_tabs": action = "tabs"; break;
            case "browser_read_page": action = "read_page"; break;
            case "browser_open_url": action = "open_url"; break;
            case "browser_click": action = "click"; break;
            case "browser_type": action = "type"; break;
            case "browser_scroll": action = "scroll"; break;
            case "browser_get_form": action = "get_form"; break;
            default: return result("Outil MCP inconnu", true);
        }
        if (("open_url".equals(action) && (a.optString("url", "").length() < 8 || a.optString("url").length() > 2000))
            || ("click".equals(action) && (a.optString("selector", "").isEmpty() || a.optString("selector").length() > 350))
            || ("type".equals(action) && (a.optString("selector", "").isEmpty()
                || a.optString("selector").length() > 350 || a.optString("text", "").length() > 8000))) {
            return result("Paramètres refusés", true);
        }
        if ("scroll".equals(action) && !("up".equals(a.optString("direction", "down"))
            || "down".equals(a.optString("direction", "down"))))
            return result("Sens de défilement invalide", true);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> text = new AtomicReference<>("Aucun résultat");
        AtomicBoolean success = new AtomicBoolean(false);
        AtomicBoolean valid = new AtomicBoolean(true);
        runner.execute(action, a, (ok, message) -> {
            if (!valid.compareAndSet(true, false)) return;
            success.set(ok);
            text.set(message == null ? "" : message);
            done.countDown();
        });
        if (!done.await(18, TimeUnit.SECONDS)) {
            valid.set(false);
            return result("Délai dépassé : résultat non confirmé, ne pas relancer sans vérifier.", true);
        }
        return result(text.get(), !success.get());
    }

    private static JSONObject result(String text, boolean error) throws Exception {
        return new JSONObject().put("content", new JSONArray().put(
            new JSONObject().put("type", "text").put("text", text))).put("isError", error);
    }

    private static JSONObject error(int code, Object id, String message) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id",
                id == null ? JSONObject.NULL : id)
            .put("error", new JSONObject().put("code", code).put("message", message));
    }
    private static JSONObject errorMessage(String error) throws Exception {
        return new JSONObject().put("ok", false).put("error", error);
    }
    private static boolean constantTime(String actual, String expected) {
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
            expected.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int status, JSONObject body) throws IOException {
        byte[] json = body.toString().getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 202 ? "Accepted" : "Error";
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: application/json; charset=utf-8\r\n"
            + "Cache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Connection: close\r\n"
            + "Content-Length: " + json.length + "\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(json); out.flush();
    }

    @Override public synchronized void close() {
        closed = true;
        ServerSocket opened = listener;
        listener = null;
        if (opened != null) try { opened.close(); } catch (IOException ignored) {}
        clients.shutdownNow();
    }
}
