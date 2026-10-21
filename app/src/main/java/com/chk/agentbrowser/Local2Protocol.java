package com.chk.agentbrowser;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Validates bridge payloads independently of HTTP success. Never prints private URLs. */
final class Local2Protocol {
    static final class RpcFailure extends IOException {
        final int code;
        RpcFailure(int value) { super("Erreur MCP " + value); code = value; }
    }
    static void checkError(JSONObject response) throws IOException {
        if (response.has("error")) {
            JSONObject error = response.optJSONObject("error");
            throw new RpcFailure(error == null ? 0 : error.optInt("code", 0));
        }
    }
    static JSONObject command(JSONObject response) throws Exception {
        checkError(response);
        if (!response.has("command")) throw new IOException("Canal Android absent");
        if (response.isNull("command")) return null;
        JSONObject command = response.optJSONObject("command");
        if (command == null || command.optString("id").isEmpty()
            || command.optString("action").isEmpty()
            || !(command.opt("args") instanceof JSONObject))
            throw new IOException("Commande Android invalide");
        return command;
    }
    static void completion(JSONObject response) throws IOException {
        checkError(response);
        if (!Boolean.TRUE.equals(response.opt("ok")))
            throw new IOException("Résultat Android non acquitté");
    }
    static String proxyCompatibilityAddress(String address) throws Exception {
        URL url = new URL(address);
        if (!url.getPath().matches("/mcp/[a-f0-9]{64}/?"))
            throw new IOException("Route MCP invalide");
        // Old Windows 1.3.0 only accepts /mcp/<cap> for android/*.
        // If Funnel strips /mcp, retain that prefix behind its mount point.
        return new URL(url, "/mcp" + url.getPath()).toExternalForm();
    }
    static String fingerprint(String address) throws Exception {
        String capability = new URL(address).getPath().replaceFirst("^.*/mcp/", "").replace("/", "");
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(capability.getBytes(StandardCharsets.US_ASCII));
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 8; i++) value.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
        return value.toString();
    }
}
