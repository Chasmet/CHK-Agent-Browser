package com.chk.agentbrowser;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class GuestQmp implements AutoCloseable {
    private final LocalSocket socket = new LocalSocket();
    private final BufferedInputStream input;
    private int sequence;

    GuestQmp(String path) throws Exception {
        try {
            socket.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
            socket.setSoTimeout(2500);
            input = new BufferedInputStream(socket.getInputStream());
            if (!receive().has("QMP")) throw new IOException("Le moteur Android ne répond pas.");
            command("qmp_capabilities", null);
        } catch (Exception error) {
            socket.close();
            throw error;
        }
    }

    synchronized Object command(String action, JSONObject arguments) throws Exception {
        int id = ++sequence;
        JSONObject request = new JSONObject().put("id", id).put("execute", action);
        if (arguments != null) request.put("arguments", arguments);
        socket.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 128; i++) {
            JSONObject reply = receive();
            if (reply.optInt("id", -1) != id) continue;
            if (reply.has("return")) return reply.get("return");
            JSONObject error = reply.optJSONObject("error");
            throw new IOException(error == null ? "Commande Android invalide." : error.optString("desc", "Commande Android refusée."));
        }
        throw new IOException("Le moteur Android ne confirme pas la commande.");
    }

    private JSONObject receive() throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int next; (next = input.read()) != -1; ) {
            if (next == '\n') return new JSONObject(line.toString("UTF-8"));
            if (line.size() >= 65536) throw new IOException("Réponse Android trop grande.");
            line.write(next);
        }
        throw new IOException("Machine virtuelle Android arrêtée.");
    }

    @Override public void close() throws IOException { socket.close(); }
}
