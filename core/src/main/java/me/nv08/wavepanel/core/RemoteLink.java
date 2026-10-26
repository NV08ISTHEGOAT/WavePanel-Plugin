package me.nv08.wavepanel.core;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The connection to WavePanel. Every few seconds it pushes the stats snapshot plus the console lines written since
 * the last push, and runs the console commands WavePanel hands back. Nothing listens on this machine.
 *
 * Trust: a panel with a real certificate (wavepanel.xyz - Let's Encrypt) is checked the normal way. A panel with a
 * self-signed one comes with its SHA-256 fingerprint, and then the connection is pinned to exactly that certificate.
 */
final class RemoteLink {
    private final Core core;
    private final URL url;
    private final String secret;
    private final SSLContext pinned;                                   // null = normal certificate checks
    private final LineSource lines;
    private final LinkedHashSet<Long> ran = new LinkedHashSet<>();     // ids already executed (never twice)
    private final List<Long> acks = new ArrayList<>();
    private volatile boolean up = true;                                // last push worked (log only changes)
    private volatile long lastOk;
    private volatile String lastError;
    private volatile int pushes;

    RemoteLink(Core core, String url, String secret, String pin, LineSource lines) throws Exception {
        this.core = core;
        this.url = new URL(url);
        this.secret = secret;
        this.lines = lines;
        this.pinned = url.startsWith("https") && pin != null && !pin.trim().isEmpty() ? pinned(pin) : null;
    }

    int pushes() { return pushes; }
    SSLContext pinnedContext() { return pinned; }
    boolean up() { return up; }
    long lastOk() { return lastOk; }
    String lastError() { return lastError; }

    private static SSLContext pinned(String pin) throws Exception {
        final String want = pin.replace(":", "").replace(" ", "").toLowerCase(Locale.ROOT);
        if (want.length() != 64) throw new IllegalArgumentException("remote.cert-sha256 must be the 64-hex SHA-256 fingerprint of WavePanel's certificate");
        X509TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("client"); }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    String got = hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                    if (!got.equals(want)) throw new CertificateException("certificate does not match remote.cert-sha256");
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new CertificateException(e);
                }
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, null);
        return ctx;
    }

    /** One push. Runs off the main thread; commands go to the main thread. True = delivered. */
    boolean push() {
        List<String> fresh = lines.next();
        StringBuilder body = new StringBuilder(4096);
        body.append("{\"stats\":").append(new String(core.snapshot(), StandardCharsets.UTF_8));
        body.append(",\"lines\":[");
        for (int i = 0; i < fresh.size(); i++) {
            if (i > 0) body.append(',');
            body.append(Json.str(fresh.get(i)));
        }
        body.append("],\"acks\":[");
        synchronized (acks) {
            for (int i = 0; i < acks.size(); i++) body.append(i > 0 ? "," : "").append(acks.get(i));
        }
        body.append("]}");

        try {
            HttpURLConnection c = open();
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(out); }
            int code = c.getResponseCode();
            String reply = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code != 200) {
                throw new IOException(code == 403 ? "the panel refused the secret - this server was removed or the code is old"
                        : code == 404 ? "the panel doesn't know this server - get a new connect code"
                        : "WavePanel answered " + code + " " + reply.replaceAll("\\s+", " ").trim());
            }
            synchronized (acks) { acks.clear(); }
            if (!up) core.log.info("Connected to WavePanel again.");
            up = true;
            lastOk = System.currentTimeMillis();
            lastError = null;
            handle(reply);
            return true;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (up) core.log.warn("WavePanel unreachable (" + lastError + ") - retrying every push.");
            up = false;
            lines.skipToEnd();                                     // resend nothing twice; skip what we couldn't deliver
            return false;
        } finally {
            pushes++;
        }
    }

    private void handle(String reply) {
        Object parsed;
        try {
            parsed = Json.parse(reply);
        } catch (RuntimeException e) {
            return;
        }
        for (Object o : Json.arr(Json.obj(parsed).get("commands"))) {
            Map<String, Object> c = Json.obj(o);
            final long id;
            try {
                id = Long.parseLong(Json.s(c, "id"));
            } catch (NumberFormatException e) {
                continue;
            }
            synchronized (acks) { acks.add(id); }
            if (!ran.add(id)) continue;                            // re-sent before our ack arrived
            if (ran.size() > 500) ran.remove(ran.iterator().next());
            String cmd = Json.s(c, "cmd");
            String head = cmd.trim().replaceFirst("^/", "").split("\\s+")[0].toLowerCase(Locale.ROOT);
            boolean power = head.equals("stop") || head.equals("restart") || head.equals("end");
            // a stop/restart arriving right after startup is a leftover from before this boot (an older panel
            // re-sent it because the server died before it could confirm) - never act on it: that was a restart loop
            if (power && java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() < 20000) {
                core.log.warn("Ignored a '" + head + "' from WavePanel that arrived right after startup (left over from before this boot).");
                continue;
            }
            if (head.equals("restart")) core.restartFromPanel();
            else core.runFromPanel(cmd);
        }
    }

    private HttpURLConnection open() throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection && pinned != null) {
            HttpsURLConnection https = (HttpsURLConnection) c;
            https.setSSLSocketFactory(pinned.getSocketFactory());
            https.setHostnameVerifier((h, s) -> true);             // the pinned fingerprint IS the identity check
        }
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("X-Link-Secret", secret);
        c.setRequestProperty("User-Agent", core.userAgent());
        return c;
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format("%02x", b & 0xff));
        return s.toString();
    }
}
