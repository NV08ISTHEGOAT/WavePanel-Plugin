package me.nv08.wavepanel.core;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static me.nv08.wavepanel.core.Texts.*;

/**
 * WavePanel.jar's engine, the same on every kind of server: config, connect codes, the stats snapshot, the push to
 * WavePanel, the file/plugin/backup jobs and the /wavepanel command. A {@link Platform} supplies the server's own
 * numbers and its main thread; this class never touches a game API itself.
 *
 * One per JVM: a server where the jar is loaded twice (plugins/ AND mods/ on a hybrid, or as an agent too) runs
 * the first copy only.
 */
public final class Core {
    public static final String VERSION = "3.2";
    static final String DEFAULT_PANEL = "https://wavepanel.xyz";
    private static final Pattern SHORT_CODE = Pattern.compile("^WP-?([2-9A-HJ-NP-Z]{4})-?([2-9A-HJ-NP-Z]{4})$");
    private static final Pattern FIELD = Pattern.compile("\"(\\w)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static Core instance;
    private static boolean haltHook;
    private static volatile boolean installing;

    final Platform p;
    final Log log;
    final Config cfg;
    private final long startedAt = System.currentTimeMillis();
    private final Machine machine = new Machine();
    private final ScheduledExecutorService tick = daemon("WavePanel-tick");
    private final ScheduledExecutorService push = daemon("WavePanel-push");
    private volatile byte[] snapshot = "{}".getBytes(StandardCharsets.UTF_8);
    private volatile Snapshot last;
    private LineSource lines;
    private RemoteLink link;
    private ScheduledFuture<?> pushTask;
    private JobLink jobs;
    private volatile boolean filesOn;
    private LocalStats local;
    private volatile boolean claiming;

    /** Starts WavePanel for this platform - or returns null (and says why) when another copy already runs here. */
    public static synchronized Core start(Platform p) {
        String running = System.getProperty("wavepanel.platform");
        if (running != null) {
            p.log().warn("WavePanel already runs on this server (as " + running + ") - this copy stays off.");
            return null;
        }
        System.setProperty("wavepanel.platform", p.id());
        Core c = new Core(p);
        instance = c;
        c.boot();
        return c;
    }

    public static synchronized Core get() { return instance; }

    private Core(Platform p) {
        this.p = p;
        this.log = p.log();
        this.cfg = new Config(new File(p.dataDir(), "config.yml"));
    }

    private static ScheduledExecutorService daemon(final String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private void boot() {
        if (!cfg.exists()) save();
        LineSource own = p.lines();
        lines = own != null ? own : new LineSource.FileTail(p.logFile());
        long every = Math.max(500L, cfg.integer("refresh-ticks", 40) * 50L);
        tick.scheduleAtFixedRate(this::refresh, 200, every, TimeUnit.MILLISECONDS);
        if (cfg.bool("remote.enabled")) {
            startRemote(null);
        } else {
            log.info("Not connected yet - get a code on " + host(panelUrl()) + " (Connect a server), then run: wavepanel connect <code>");
        }
        if (cfg.bool("local")) startLocal(cfg.integer("port", 25590));
        log.info("WavePanel " + VERSION + " running on " + p.displayName() + ".");
    }

    /** The plugin/mod is going away (server stop, plugin reload): everything stops, another copy may start. */
    public synchronized void stop() {
        tick.shutdownNow();
        stopRemote();
        push.shutdownNow();
        if (local != null) local.stop();
        local = null;
        synchronized (Core.class) {
            if (instance == this) instance = null;
            if (p.id().equals(System.getProperty("wavepanel.platform"))) System.clearProperty("wavepanel.platform");
        }
    }

    // ------------------------------------------------------------------ snapshot

    byte[] snapshot() { return snapshot; }

    /** Asks the platform for its numbers (on its main thread), then renders the latest ones with this JVM's. */
    private void refresh() {
        try {
            p.runSync(() -> {
                try {
                    Snapshot s = new Snapshot();
                    p.collect(s);
                    last = s;
                } catch (Throwable t) {
                    // one bad read (a player leaving mid-read, a world unloading) - the next refresh is fine
                }
            });
        } catch (Throwable ignored) {
            // the server is shutting down
        }
        try {
            snapshot = render(last).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // keep the previous snapshot
        }
    }

    private String render(Snapshot s) {
        StringBuilder j = new StringBuilder(2048);
        j.append('{');
        j.append("\"time\":").append(System.currentTimeMillis());
        j.append(",\"uptime\":").append(System.currentTimeMillis() - startedAt);
        j.append(",\"platform\":").append(Json.str(p.displayName()));
        j.append(",\"addons\":").append(Json.str(p.addonsFolder()));
        j.append(",\"proxy\":").append(p.isProxy());
        j.append(",\"plugin\":").append(Json.str(VERSION));
        j.append(",\"files\":").append(filesOn);
        j.append(",\"java\":").append(Installer.javaMajor());
        j.append(",\"installer\":").append(filesOn);              // 3.2+: WavePanel can swap this server's software
        if (s != null) {
            j.append(",\"version\":").append(Json.str(s.software != null ? s.software : p.displayName()));
            if (s.tps != null && s.tps.length >= 3) {
                j.append(",\"tps\":[").append(Json.num(s.tps[0])).append(',').append(Json.num(s.tps[1])).append(',').append(Json.num(s.tps[2])).append(']');
            }
            if (s.mspt != null) j.append(",\"mspt\":").append(Json.num(s.mspt));
            j.append(",\"online\":").append(s.online);
            j.append(",\"max\":").append(s.max);
        }
        machine.append(j);
        j.append(",\"players\":[");
        if (s != null) {
            boolean first = true;
            for (Snapshot.Player pl : s.players) {
                if (!first) j.append(',');
                first = false;
                j.append('{')
                 .append("\"name\":").append(Json.str(pl.name))
                 .append(",\"uuid\":").append(Json.str(pl.uuid))
                 .append(",\"world\":").append(Json.str(pl.server != null ? pl.server : pl.world))
                 .append(",\"x\":").append(pl.x).append(",\"y\":").append(pl.y).append(",\"z\":").append(pl.z)
                 .append(",\"ping\":").append(pl.ping)
                 .append(",\"gamemode\":").append(Json.str(pl.gamemode))
                 .append(",\"op\":").append(pl.op)
                 .append(",\"health\":").append(Json.num(pl.health))
                 .append(",\"maxHealth\":").append(Json.num(pl.maxHealth))
                 .append(",\"food\":").append(pl.food)
                 .append('}');
            }
        }
        j.append("],\"worlds\":[");
        if (s != null) {
            boolean first = true;
            for (Snapshot.World w : s.worlds) {
                if (!first) j.append(',');
                first = false;
                j.append('{')
                 .append("\"name\":").append(Json.str(w.name))
                 .append(",\"env\":").append(Json.str(w.env))
                 .append(",\"players\":").append(w.players)
                 .append(",\"entities\":").append(Math.max(0, w.entities))
                 .append(",\"chunks\":").append(Math.max(0, w.chunks))
                 .append('}');
            }
        }
        j.append("]}");
        return j.toString();
    }

    // ------------------------------------------------------------------ connection

    String userAgent() { return "WavePanel/" + VERSION + " (" + p.id() + ")"; }

    private String panelUrl() {
        String s = cfg.get("panel").trim();
        return (s.isEmpty() ? DEFAULT_PANEL : s).replaceAll("/+$", "");
    }

    private synchronized void startRemote(final Consumer<String> who) {
        stopRemote();
        String url = cfg.get("remote.url"), secret = cfg.get("remote.secret");
        if (url.trim().isEmpty() || secret.trim().isEmpty()) {
            log.error("remote.enabled is true but remote.url / remote.secret are empty - run: wavepanel connect <code>");
            return;
        }
        try {
            final RemoteLink l = new RemoteLink(this, url, secret, cfg.get("remote.cert-sha256"), lines);
            link = l;
            long every = Math.max(1000L, cfg.integer("remote.interval-ticks", 40) * 50L);
            pushTask = push.scheduleWithFixedDelay(() -> {
                boolean first = l.pushes() == 0;
                boolean ok = l.push();
                if (first && who != null) {
                    tell(who, ok ? GREEN + "[WavePanel] Connected - this server is live in WavePanel now."
                            : RED + "[WavePanel] Could not reach WavePanel: " + l.lastError() + GRAY + " (it keeps retrying)");
                }
            }, 1000, every, TimeUnit.MILLISECONDS);
            log.info("Connected mode: pushing stats + console to " + host(url));
            if (!"false".equalsIgnoreCase(cfg.get("remote.allow-files").trim())) {
                jobs = new JobLink(this, url, secret, l.pinnedContext(), cfg.integer("remote.backups-keep", 3));
                jobs.start();
                filesOn = true;
                log.info("Files, " + (p.addonsFolder() == null ? "" : p.addonsFolder() + " ") + "and backups available to WavePanel (this server's folder only).");
            }
        } catch (Exception e) {
            log.error("Connection off: " + e.getMessage());
        }
    }

    private synchronized void stopRemote() {
        if (pushTask != null) pushTask.cancel(false);
        pushTask = null;
        link = null;
        if (jobs != null) jobs.stop();
        jobs = null;
        filesOn = false;
    }

    private void startLocal(int port) {
        try {
            local = new LocalStats(this, port);
            log.info("Serving live stats on 127.0.0.1:" + port + " for a WavePanel on this machine.");
        } catch (IOException e) {
            log.error("Could not open 127.0.0.1:" + port + " (" + e.getMessage() + ") - a WavePanel on this machine will show this server without live stats.");
        }
    }

    private void save() {
        try {
            cfg.save();
        } catch (IOException e) {
            log.error("Could not write " + new File(p.dataDir(), "config.yml") + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ the panel's commands

    /** A console command from WavePanel: run on the main thread. */
    void runFromPanel(final String cmd) {
        p.runSync(() -> {
            log.info("WavePanel ran: " + cmd);
            try {
                p.dispatch(cmd);
            } catch (Throwable t) {
                log.warn("That command failed: " + t);
            }
        });
    }

    /** A console command the core itself needs (save-off for a backup): runs on the main thread and waits for it. */
    void consoleAndWait(final String cmd) {
        final CountDownLatch done = new CountDownLatch(1);
        p.runSync(() -> {
            try {
                p.dispatch(cmd);
            } catch (Throwable ignored) {
                // the server is shutting down or busy - the zip still goes ahead
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Restart pressed in WavePanel: save, stop - and exit with remote.restart-exit-code so the host starts it again. */
    void restartFromPanel() {
        final int code = cfg.integer("remote.restart-exit-code", 1);
        log.info("Restart from WavePanel: saving and stopping" + (code != 0
                ? " - exit code " + code + " tells the host to start the server again (its auto-restart / crash detection)." : "."));
        p.runSync(() -> p.restart(code));
    }

    /**
     * A version install is ready (downloaded + checked): stop the server now. The swap itself happens once it's gone
     * (Installer.apply in a shutdown hook), which then ends the JVM with the restart code - the host starts the new jar.
     */
    void installAndRestart(final Installer.Plan plan) {
        final int code = cfg.integer("remote.restart-exit-code", 1);
        synchronized (Core.class) {
            installing = true;
            haltHook = true;                                     // the install hook ends the JVM itself
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Installer.apply(plan, code), "WavePanel-install"));
        log.info("Installing " + plan.label + (plan.wipe ? " (wiping the server files)" : "") + ": stopping the server now - "
                + (code != 0 ? "it comes back on the new software by itself (exit code " + code + ")." : "start it again in your host's panel."));
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1500);                              // WavePanel gets its answer first
            } catch (InterruptedException ignored) {
                // stop now then
            }
            p.runSync(() -> p.restart(code));
        }, "WavePanel-install-stop");
        t.setDaemon(true);
        t.start();
    }

    /** An install is waiting for the server to stop: no restart scripts (they'd start the old jar right away). */
    public static boolean installing() { return installing; }

    /**
     * After a stop from WavePanel, end the JVM with this exit code once the server has saved (its own shutdown hooks
     * first): a hosting panel then sees a "crash" and starts it again. Platforms call it from their restart().
     */
    public static synchronized void haltOnExit(final int code) {
        if (code == 0 || haltHook) return;
        haltHook = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Thread.sleep(1500);                                  // the server has saved; let the other hooks finish
            } catch (InterruptedException ignored) {
                // exiting anyway
            }
            Runtime.getRuntime().halt(code);
        }, "WavePanel-restart"));
    }

    // ------------------------------------------------------------------ /wavepanel

    /**
     * /wavepanel connect|status|disconnect, from any platform's command system. {@code reply} may be called from
     * another thread later (the code check runs off the main thread); it's handed back via the main thread.
     */
    public void command(String[] a, Consumer<String> reply) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "status";
        if (sub.equals("connect")) {
            if (a.length < 2) {
                reply.accept(YELLOW + "wavepanel connect <code>" + GRAY + "  - get the code on " + host(panelUrl()) + ": Connect a server");
                return;
            }
            String code = a[1].trim();
            Matcher sm = SHORT_CODE.matcher(code.toUpperCase(Locale.ROOT));
            if (sm.matches()) {                                       // WP-7KQ2-M9XD from the website
                claim(reply, "WP-" + sm.group(1) + "-" + sm.group(2));
                return;
            }
            String[] info = decode(code);                             // the long WP1.xxxx codes of PanelLink 2.x still work
            if (info == null) {
                reply.accept(RED + "[WavePanel] That isn't a WavePanel connect code (it looks like WP-XXXX-XXXX). Copy the whole command again.");
                return;
            }
            connect(reply, info[0], info[1], info[2], info[3]);
            return;
        }
        if (sub.equals("disconnect")) {
            stopRemote();
            cfg.set("remote.enabled", false);
            save();
            reply.accept(YELLOW + "[WavePanel] Disconnected from WavePanel (connect again with: wavepanel connect <code>).");
            return;
        }
        RemoteLink l = link;
        if (l != null) {
            long ago = l.lastOk() == 0 ? -1 : (System.currentTimeMillis() - l.lastOk()) / 1000;
            String name = cfg.get("remote.name");
            reply.accept(AQUA + "[WavePanel] " + (name.isEmpty() ? "" : WHITE + name + AQUA + " on ") + WHITE + host(cfg.get("remote.url"))
                    + (l.up() && ago >= 0 ? GREEN + " connected" + GRAY + " (last push " + ago + "s ago)"
                    : RED + " not reaching it" + (l.lastError() != null ? GRAY + " - " + l.lastError() : "")));
        } else {
            reply.accept(AQUA + "[WavePanel] Not connected." + GRAY + " Get a code on " + host(panelUrl())
                    + " (Connect a server), then: wavepanel connect <code>");
        }
        if (local != null) reply.accept(AQUA + "[WavePanel] Local stats on 127.0.0.1:" + cfg.integer("port", 25590));
        reply.accept(GRAY + "[WavePanel] " + VERSION + " on " + p.displayName());
    }

    /** A code handed over at startup (-javaagent:WavePanel.jar=WP-XXXX-XXXX): used once, while not connected. */
    public void connectOnce(String code) {
        if (code == null || code.trim().isEmpty() || cfg.bool("remote.enabled")) return;
        command(new String[]{"connect", code.trim()}, log::info);
    }

    /** Hands a message back via the main thread (a sender may only be told things there on some platforms). */
    private void tell(final Consumer<String> who, final String msg) {
        try {
            p.runSync(() -> who.accept(msg));
        } catch (Throwable t) {
            log.info(msg);
        }
    }

    /** A short code from the website: traded (once) at the panel for this server's own address + secret. */
    private void claim(final Consumer<String> who, final String code) {
        if (claiming) {
            who.accept(YELLOW + "[WavePanel] Already checking a code - one moment.");
            return;
        }
        claiming = true;
        final String panel = panelUrl();
        who.accept(AQUA + "[WavePanel] Checking your code with " + WHITE + host(panel) + AQUA + "...");
        Thread t = new Thread(() -> {
            String[] got = null;
            String err;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(panel + "/api/link/claim").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", userAgent());
                byte[] body = ("{\"code\":" + Json.str(code) + "}").getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
                int status = c.getResponseCode();
                String reply = RemoteLink.read(status < 400 ? c.getInputStream() : c.getErrorStream());
                Map<String, Object> r;
                try {
                    r = Json.obj(Json.parse(reply));
                } catch (RuntimeException e) {
                    r = Json.obj(null);
                }
                String url = Json.s(r, "url"), secret = Json.s(r, "secret");
                if (status == 200 && !secret.isEmpty() && (url.startsWith("https://") || url.startsWith("http://"))) {
                    got = new String[]{url, secret, Json.s(r, "name")};
                    err = null;
                } else {
                    String msg = Json.s(r, "error");
                    err = !msg.isEmpty() ? msg : "WavePanel answered " + status;
                }
            } catch (Exception e) {
                err = "Could not reach " + host(panel) + " (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")";
            }
            claiming = false;
            if (got == null) tell(who, RED + "[WavePanel] " + err);
            else connect(who, got[0], got[1], got[2], null);
        }, "WavePanel-claim");
        t.setDaemon(true);
        t.start();
    }

    /** Save the connection and start pushing (the first push's result goes back to {@code who}). */
    private void connect(Consumer<String> who, String url, String secret, String name, String pin) {
        cfg.set("remote.enabled", true);
        cfg.set("remote.url", url);
        cfg.set("remote.secret", secret);
        cfg.set("remote.cert-sha256", pin == null ? "" : pin);
        cfg.set("remote.name", name == null ? "" : name);
        cfg.set("local", false);                                  // the panel is elsewhere: no local listener needed
        save();
        if (local != null) local.stop();
        local = null;
        tell(who, AQUA + "[WavePanel] Connecting" + (name != null && !name.isEmpty() ? " as " + WHITE + name + AQUA : "")
                + " to " + WHITE + host(url) + AQUA + "...");
        startRemote(who);
    }

    /** "WP1.<base64url json>" (PanelLink 2.x codes) -> {url, secret, name, pin} or null. */
    static String[] decode(String code) {
        if (code == null || !code.startsWith("WP1.")) return null;
        String json;
        try {
            json = new String(Base64.getUrlDecoder().decode(code.substring(4).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String[] out = new String[4];
        Matcher m = FIELD.matcher(json);
        while (m.find()) {
            String v = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/");
            switch (m.group(1)) {
                case "u": out[0] = v; break;
                case "s": out[1] = v; break;
                case "n": out[2] = v; break;
                case "p": out[3] = v; break;
                default: break;
            }
        }
        if (out[0] == null || out[1] == null || !(out[0].startsWith("https://") || out[0].startsWith("http://"))) return null;
        return out;
    }

    static String host(String url) {
        return url.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
    }

    /** "/wavepanel connect ..." typed or pasted with its slash, or "wavepanel ..." - the arguments after the word. */
    public static String[] args(String line) {
        String s = line.trim().replaceFirst("^/", "");
        int sp = s.indexOf(' ');
        return sp < 0 ? new String[0] : s.substring(sp + 1).trim().split("\\s+");
    }
}
