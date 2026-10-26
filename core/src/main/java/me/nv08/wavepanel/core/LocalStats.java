package me.nv08.wavepanel.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/** Local mode: a WavePanel on the same machine reads the stats from 127.0.0.1 (loopback only, read-only). */
final class LocalStats {
    private final HttpServer http;

    LocalStats(final Core core, int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        http.createContext("/stats", ex -> {
            byte[] body = core.snapshot();
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        http.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WavePanel-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    void stop() { http.stop(0); }
}
