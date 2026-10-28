package me.nv08.wavepanel.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * WavePanel host mode (Split, Beta): what runs when the host starts a split server - its jar is WavePanel.jar now.
 * Starts the parts (the original server on 127.0.0.1 + the new ones, each with WavePanel.jar inside), with a Velocity
 * proxy on the server's own port in front of them. Their consoles come out here tagged [name]; a command typed into
 * the host's console goes to the first part, "name: command" to another one, "stop" stops everything. A part that
 * crashes (or restarts from WavePanel: a non-zero exit code) is started again; one that was stopped stays stopped
 * until WavePanel or the console ("start name") starts it. WavePanel reaches this process through notes in
 * wavepanel/split/control/ that the parts' WavePanel.jar write (subdomains -> parts, start a part, undo the split).
 *
 * The host's JVM settings (often -Xmx = the whole server) apply to this small process only, so it allocates as good
 * as nothing: console lines are passed through as bytes.
 */
public final class Supervisor {
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");
    private static final PrintStream OUT = System.out;
    private static final byte[] DONE = "Done (".getBytes(StandardCharsets.US_ASCII);
    private static final String[] PART_FLAGS = {"-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40",
            "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1", "-Dusing.aikars.flags=https://mcflags.emc.gs"};
    private static final String[] PROXY_FLAGS = {"-XX:+UseG1GC", "-XX:G1HeapRegionSize=4M", "-XX:+UnlockExperimentalVMOptions",
            "-XX:+ParallelRefProcEnabled", "-XX:+AlwaysPreTouch", "-XX:MaxInlineLevel=15"};

    private final File root, splitDir, json;
    private Map<String, Object> cfg;
    private final List<Child> parts = new ArrayList<>();
    private Child proxy;
    private volatile boolean stopping;
    private long hostsSeen;

    private Supervisor(File root) {
        this.root = root;
        this.splitDir = new File(root, Split.DIR);
        this.json = new File(splitDir, "split.json");
    }

    public static void main(String[] args) throws Exception {
        Supervisor s = new Supervisor(new File(".").getCanonicalFile());
        if (!s.json.isFile()) {
            say("WavePanel.jar isn't a Minecraft server by itself: put it in plugins/ or mods/, or start it with -javaagent:WavePanel.jar.");
            say("(It runs as a server only after a Split in WavePanel - there's no " + Split.DIR + "/split.json here.)");
            System.exit(1);
        }
        s.run();
    }

    private void run() throws Exception {
        cfg = Json.obj(Json.parse(Split.read(json)));
        if (Json.b(cfg, "pending")) {
            say("The split didn't finish (see " + Installer.LOG + "). Nothing was started - undo it in WavePanel or fix it by hand.");
            System.exit(1);
        }
        List<Object> list = Json.arr(cfg.get("parts"));
        if (list.isEmpty()) {
            say(Split.DIR + "/split.json lists no servers - nothing to start.");
            System.exit(1);
        }
        rootProps();
        Map<String, Object> px = Json.obj(cfg.get("proxy"));
        for (Object o : list) parts.add(part(Json.obj(o), parts.isEmpty()));
        proxy = new Child("proxy", "Proxy", new File(root, Json.s(px, "dir")), proxyCommand(px), true);
        say("WavePanel host mode (Split, Beta): a proxy on port " + Json.s(px, "port") + " and " + parts.size()
                + " servers behind it. \"stop\" stops everything, \"parts\" shows them, \"name: command\" runs a command on one part.");

        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAll, "WavePanel-host-stop"));
        Thread in = new Thread(this::console, "WavePanel-host-console");
        in.setDaemon(true);
        in.start();

        try {
            if (!new File(proxy.dir, "velocity.toml").isFile()) generateProxy();
            configureProxy();
        } catch (Exception e) {
            say("The proxy can't be set up: " + e.getMessage() + " - nothing was started.");
            System.exit(1);
        }
        for (Child c : parts) if (!c.first) c.start();
        proxy.start();
        hostsSeen = new File(splitDir, "control/hosts.json").lastModified();
        for (final Child c : parts) {
            if (!c.first) continue;
            Thread t = new Thread(() -> firstStart(c), "WavePanel-host-setup-" + c.slug);
            t.setDaemon(true);
            t.start();
        }

        while (!stopping) {
            Thread.sleep(1000);
            control();
            for (Child c : all()) c.tick();
        }
        stopAll();                                              // returns once the stop (from another thread) is done
    }

    // ------------------------------------------------------------------ the parts and the proxy

    private Child part(Map<String, Object> p, boolean original) {
        int heap = Split.intOr(Json.s(p, "heapMB"), 2048);
        List<String> cmd = new ArrayList<>(Arrays.asList(java(), "-Xms" + Math.min(heap, 1024) + "M", "-Xmx" + heap + "M"));
        cmd.addAll(Arrays.asList(PART_FLAGS));
        if (original) {
            // the host's own settings for this server: system properties, and WavePanel as an agent if it ran that way
            for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (a.startsWith("-D") || a.startsWith("-javaagent:")) cmd.add(a);
            }
        }
        cmd.add("-jar");
        cmd.add(Json.s(p, "jar"));
        cmd.add("nogui");
        Child c = new Child(Json.s(p, "slug"), Json.s(p, "name"), new File(root, Json.s(p, "dir")), cmd, false);
        c.heapMB = heap;
        c.first = Json.b(p, "first");
        return c;
    }

    private List<String> proxyCommand(Map<String, Object> px) {
        int heap = Split.intOr(Json.s(px, "heapMB"), 384);
        List<String> cmd = new ArrayList<>(Arrays.asList(java(), "-Xms" + heap + "M", "-Xmx" + heap + "M"));
        cmd.addAll(Arrays.asList(PROXY_FLAGS));
        cmd.add("-jar");
        cmd.add(Json.s(px, "jar"));
        return cmd;
    }

    private static String java() {
        File bin = new File(System.getProperty("java.home"), "bin");
        File exe = new File(bin, "java.exe");
        return (exe.isFile() ? exe : new File(bin, "java")).getPath();
    }

    private List<Child> all() {
        List<Child> all = new ArrayList<>(parts);
        if (proxy != null) all.add(proxy);
        return all;
    }

    /**
     * Hosts like Pterodactyl write their port (and 0.0.0.0) into server.properties at every start: that address is the
     * proxy's now, and the first part goes back to its 127.0.0.1 port behind it.
     */
    private void rootProps() throws IOException {
        File f = new File(root, "server.properties");
        Properties sp = Split.props(f);
        Map<String, Object> px = Json.obj(cfg.get("proxy"));
        Map<String, Object> p1 = Json.obj(Json.arr(cfg.get("parts")).get(0));
        int port1 = Split.intOr(Json.s(p1, "port"), 0), now = Split.intOr(sp.getProperty("server-port", ""), 0);
        String ip = sp.getProperty("server-ip", "").trim();
        boolean changed = false;
        if (now > 0 && now != port1 && now != Split.intOr(Json.s(px, "port"), 0)) {
            say("The host gave this server port " + now + " - the proxy takes that port now.");
            px.put("port", now);
            changed = true;
        }
        if (!ip.isEmpty() && !ip.equals("127.0.0.1") && !ip.equals(Json.s(px, "bind"))) {
            px.put("bind", ip);
            changed = true;
        }
        if (changed) save();
        Map<String, String> set = new LinkedHashMap<>();
        set.put("server-ip", "127.0.0.1");
        set.put("server-port", String.valueOf(port1));
        set.put("online-mode", "false");
        Split.setProps(f, set);
    }

    /** A new Paper part: started once so it writes its configs, then Velocity forwarding is switched on in them. */
    private void firstStart(Child c) {
        c.busy = true;
        try {
            say(c.name + ": first start - it makes its world and config files, then restarts behind the proxy.");
            c.start();
            boolean ready = c.awaitReady(360);
            c.send("stop");
            c.waitFor(120);
            if (c.alive()) c.proc.destroyForcibly();
            if (stopping) return;
            if (!ready) {
                c.wanted = false;
                say(c.name + " didn't finish its first start (see its lines above - the EULA?). It stays off until it's fixed; then type: start " + c.slug);
                return;
            }
            Map<String, Object> px = Json.obj(cfg.get("proxy"));
            if (!Split.paperForwarding(c.dir, true, Json.b(px, "online"), Split.read(new File(proxy.dir, "forwarding.secret")).trim())) {
                c.wanted = false;
                say(c.name + " has no config/paper-global.yml - Velocity forwarding couldn't be switched on, so it stays off.");
                return;
            }
            synchronized (this) {
                for (Object o : Json.arr(cfg.get("parts"))) {
                    Map<String, Object> p = Json.obj(o);
                    if (c.slug.equals(Json.s(p, "slug"))) p.remove("first");
                }
                save();
            }
            c.first = false;
            say(c.name + " is set up - starting it behind the proxy.");
            c.busy = false;
            c.start();
        } catch (Exception e) {
            c.wanted = false;
            say(c.name + ": the first start failed: " + e.getMessage());
        } finally {
            c.busy = false;
        }
    }

    /** Velocity writes its default velocity.toml on its first start; the split's settings go into that. */
    private void generateProxy() throws Exception {
        say("Proxy: first start - it makes velocity.toml.");
        File toml = new File(proxy.dir, "velocity.toml");
        proxy.busy = true;
        proxy.start();
        for (int i = 0; i < 120 && !toml.isFile() && proxy.alive(); i++) Thread.sleep(1000);
        if (toml.isFile()) proxy.awaitReady(60);
        proxy.send("end");
        proxy.waitFor(30);
        if (proxy.alive()) proxy.proc.destroyForcibly();
        proxy.waitFor(10);
        proxy.proc = null;
        proxy.busy = false;
    }

    private synchronized void configureProxy() throws IOException {
        File f = new File(proxy.dir, "velocity.toml");
        if (!f.isFile()) throw new IOException("Velocity didn't write velocity.toml (see its lines above)");
        Map<String, Object> px = Json.obj(cfg.get("proxy"));
        List<String> t = new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        String bind = Json.s(px, "bind");
        tomlSet(t, null, "bind", "\"" + (bind.isEmpty() ? "0.0.0.0" : bind) + ":" + Json.s(px, "port") + "\"");
        tomlSet(t, null, "online-mode", String.valueOf(Json.b(px, "online")));
        tomlSet(t, null, "player-info-forwarding-mode", "\"MODERN\"");
        tomlSet(t, null, "forwarding-secret-file", "\"forwarding.secret\"");
        tomlSet(t, null, "show-max-players", String.valueOf(Split.intOr(Json.s(px, "maxPlayers"), 20)));
        tomlSet(t, null, "motd", "\"<gray>Starting up - join again in a moment</gray>\"");
        // the server list answer comes from the part an address leads to: its MOTD, icon and players, as before the split
        tomlSet(t, null, "ping-passthrough", "\"ALL\"");
        List<String> servers = new ArrayList<>();
        for (Child c : parts) servers.add(c.slug + " = \"127.0.0.1:" + portOf(c.slug) + "\"");
        servers.add("try = [\"" + parts.get(0).slug + "\"]");
        tomlSection(t, "servers", servers);
        tomlSection(t, "forced-hosts", forcedHosts());
        Files.write(f.toPath(), t, StandardCharsets.UTF_8);
    }

    private List<String> forcedHosts() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : Json.obj(cfg.get("hosts")).entrySet()) {
            String host = e.getKey().toLowerCase(Locale.ROOT), slug = String.valueOf(e.getValue());
            if (host.matches("[a-z0-9.-]{1,200}") && portOf(slug) > 0) out.add("\"" + host + "\" = [\"" + slug + "\"]");
        }
        return out;
    }

    private int portOf(String slug) {
        for (Object o : Json.arr(cfg.get("parts"))) {
            Map<String, Object> p = Json.obj(o);
            if (slug.equals(Json.s(p, "slug"))) return Split.intOr(Json.s(p, "port"), 0);
        }
        return 0;
    }

    private synchronized void save() throws IOException {
        File tmp = new File(splitDir, "split.json.tmp");
        Split.write(tmp, Json.write(cfg));
        Files.move(tmp.toPath(), json.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ velocity.toml (TOML, edited line by line)

    /** Sets key = value at the top level (section null) or in [section]; false if the key isn't there. */
    static boolean tomlSet(List<String> t, String section, String key, String value) {
        boolean in = section == null;
        Pattern k = Pattern.compile(Pattern.quote(key) + "\\s*=.*");
        for (int i = 0; i < t.size(); i++) {
            String l = t.get(i).trim();
            if (l.startsWith("[") && !l.startsWith("[\"")) {
                in = section != null && l.equals("[" + section + "]");
                continue;
            }
            if (in && k.matcher(l).matches()) {
                t.set(i, key + " = " + value);
                return true;
            }
        }
        return false;
    }

    /** Replaces the entries of [section] (up to the next section, comments kept) with {@code body}; adds a missing one. */
    static void tomlSection(List<String> t, String section, List<String> body) {
        int start = -1, end = t.size();
        for (int i = 0; i < t.size(); i++) {
            String l = t.get(i).trim();
            if (start < 0 && l.equals("[" + section + "]")) start = i;
            else if (start >= 0 && l.startsWith("[") && !l.startsWith("[\"")) { end = i; break; }
        }
        if (start < 0) {
            t.add("");
            t.add("[" + section + "]");
            t.addAll(body);
            return;
        }
        List<String> next = new ArrayList<>();
        for (int i = start + 1; i < end; i++) if (t.get(i).trim().startsWith("#")) next.add(t.get(i));
        next.addAll(body);
        next.add("");
        for (int i = end - 1; i > start; i--) t.remove(i);
        t.addAll(start + 1, next);
    }

    // ------------------------------------------------------------------ WavePanel's notes (control/)

    private void control() {
        File dir = new File(splitDir, "control");
        File hosts = new File(dir, "hosts.json");
        if (hosts.isFile() && hosts.lastModified() != hostsSeen) {
            hostsSeen = hosts.lastModified();
            try {
                synchronized (this) {
                    cfg.put("hosts", Json.obj(Json.parse(Split.read(hosts))));
                    save();
                }
                configureProxy();
                proxy.send("velocity reload");
                say("Subdomains updated: " + forcedHosts().size() + " address(es) lead straight to their own part.");
            } catch (Exception e) {
                say("Couldn't use the new subdomains: " + e.getMessage());
            }
        }
        File[] notes = dir.listFiles();
        if (notes == null) return;
        for (File n : notes) {
            String name = n.getName();
            if (name.startsWith("start-") || name.startsWith("restart-")) {
                n.delete();
                Child c = find(name.substring(name.indexOf('-') + 1));
                if (c != null) startOrRestart(c, name.startsWith("restart-"), "asked in WavePanel");
            } else if (name.equals("unsplit")) {
                n.delete();
                unsplit();
            }
        }
    }

    private void startOrRestart(Child c, boolean restart, String why) {
        if (c.busy) {
            say(c.name + " is still being set up - wait a moment.");
        } else if (c.alive() && restart) {
            say("Restarting " + c.name + " (" + why + ")");
            c.restartAfterExit = true;
            c.send(c.isProxy ? "end" : "stop");
        } else if (!c.alive()) {
            say("Starting " + c.name + " (" + why + ")");
            c.wanted = true;
            c.crashes = 0;
            c.start();
        } else {
            say(c.name + " is already running.");
        }
    }

    private Child find(String slug) {
        for (Child c : all()) if (c.slug.equalsIgnoreCase(slug) || c.name.equalsIgnoreCase(slug)) return c;
        return null;
    }

    // ------------------------------------------------------------------ the host's console

    private void console() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String cmd = line.trim();
                if (cmd.isEmpty()) continue;
                String low = cmd.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
                int colon = cmd.indexOf(':');
                Child to = colon > 0 && cmd.substring(0, colon).matches("[\\w-]+") ? find(cmd.substring(0, colon)) : null;
                if (low.equals("stop") || low.equals("end") || low.equals("shutdown")) {
                    stopAll();
                    System.exit(0);
                } else if (low.equals("parts") || low.equals("status")) {
                    for (Child c : all()) {
                        say(c.name + " (" + c.slug + "): " + (c.busy ? "being set up" : c.alive() ? "running" : c.wanted ? "starting again soon" : "stopped"));
                    }
                } else if (low.startsWith("start ") || low.startsWith("restart ")) {
                    String which = cmd.substring(cmd.indexOf(' ') + 1).trim();
                    Child c = find(which);
                    if (c == null) say("There's no part called " + which + " - type: parts");
                    else startOrRestart(c, low.startsWith("restart "), "typed in the console");
                } else if (to != null) {
                    to.send(cmd.substring(colon + 1).trim());
                } else {
                    parts.get(0).send(cmd);
                }
            }
        } catch (IOException ignored) {
            // no console (stdin closed) - the parts keep running
        }
    }

    // ------------------------------------------------------------------ stopping, undoing

    private synchronized void stopAll() {
        if (stopping) return;
        stopping = true;
        say("Stopping the proxy and every part (they save first)...");
        if (proxy != null && proxy.alive()) proxy.send("end");
        for (Child c : parts) if (c.alive()) c.send("stop");
        long until = System.currentTimeMillis() + 120_000;
        for (Child c : all()) c.waitFor(Math.max(1, (until - System.currentTimeMillis()) / 1000));
        for (Child c : all()) if (c.alive()) c.proc.destroyForcibly();
        say("Everything stopped.");
    }

    /** Undo: stop everything, put the original server back as it was, keep the other parts' files, exit to restart. */
    private void unsplit() {
        say("Undoing the split (asked in WavePanel): stopping everything first...");
        stopAll();
        Map<String, Object> r = Json.obj(cfg.get("restore"));
        File log = new File(root, Installer.LOG);
        int code = Split.intOr(Json.s(cfg, "restartCode"), 1);
        try {
            File orig = new File(root, Json.s(r, "origJar")), host = new File(root, Json.s(r, "hostJar"));
            try {
                Files.copy(orig.toPath(), host.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException locked) {
                Split.helper(orig, host, log, host);             // Windows: this JVM still holds the host's jar (WavePanel.jar)
            }
            Map<String, String> props = new LinkedHashMap<>();
            props.put("server-ip", Json.s(r, "serverIp"));
            props.put("server-port", Json.s(Json.obj(cfg.get("proxy")), "port"));    // the host's port, as it is now
            props.put("online-mode", Json.s(r, "onlineMode"));
            Split.setProps(new File(root, "server.properties"), props);
            if ("fabric".equals(Json.s(r, "kind"))) {
                File removed = new File(splitDir, "removed-mods");
                for (Object o : Json.arr(r.get("addedMods"))) {
                    File m = new File(root, String.valueOf(o));
                    if (!m.isFile()) continue;
                    removed.mkdirs();
                    Files.move(m.toPath(), new File(removed, m.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Split.paperForwarding(root, false, !"false".equals(Json.s(r, "onlineMode")), null);
            }
            File old = new File(root, "wavepanel/split-old-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
            Files.move(splitDir.toPath(), old.toPath());
            Split.say(log, "Split undone: the original server is back on port " + props.get("server-port") + ". The other parts' files are kept in "
                    + "wavepanel/" + old.getName() + "/parts/.");
        } catch (Throwable t) {
            Split.say(log, "Undoing the split failed: " + t + Split.where(t) + " - nothing more was changed.");
        }
        Runtime.getRuntime().halt(code);
    }

    static void say(String msg) {
        synchronized (OUT) {
            OUT.println("[" + TIME.format(new Date()) + " WavePanel host] " + msg);
            OUT.flush();
        }
    }

    // ------------------------------------------------------------------ one process

    final class Child {
        final String slug, name;
        final File dir;
        final List<String> cmd;
        final boolean isProxy;
        final byte[] prefix;
        int heapMB;
        volatile boolean first, busy;
        volatile Process proc;
        volatile boolean wanted = true, restartAfterExit;
        volatile CountDownLatch ready = new CountDownLatch(1);
        int crashes;
        long firstCrash, startAt, startedAt;
        private OutputStream stdin;

        Child(String slug, String name, File dir, List<String> cmd, boolean isProxy) {
            this.slug = slug;
            this.name = name.isEmpty() ? slug : name;
            this.dir = dir;
            this.cmd = cmd;
            this.isProxy = isProxy;
            this.prefix = ("[" + this.name + "] ").getBytes(StandardCharsets.UTF_8);
        }

        boolean alive() { return proc != null && proc.isAlive(); }

        synchronized void start() {
            if (alive() || stopping) return;
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
                pb.environment().put("WAVEPANEL_SPLIT", splitDir.getPath());
                pb.environment().put("WAVEPANEL_PART", slug);
                ready = new CountDownLatch(1);
                proc = pb.start();
                stdin = proc.getOutputStream();
                startAt = 0;
                startedAt = System.currentTimeMillis();
                final Process p = proc;
                Thread out = new Thread(() -> pump(p), "WavePanel-host-" + slug);
                out.setDaemon(true);
                out.start();
                say("Started " + name + (isProxy ? "" : " (" + heapMB + " MB)"));
            } catch (IOException e) {
                say("Couldn't start " + name + ": " + e.getMessage());
                wanted = false;
            }
        }

        /** The child's console, line by line, tagged with its name - copied as bytes (no Strings: nothing to collect). */
        private void pump(Process p) {
            byte[] buf = new byte[8192], line = new byte[1024];
            int len = 0, match = 0;
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    for (int i = 0; i < n; i++) {
                        byte b = buf[i];
                        if (len == 0) {
                            System.arraycopy(prefix, 0, line, 0, prefix.length);
                            len = prefix.length;
                        }
                        if (len == line.length) line = Arrays.copyOf(line, line.length * 2);
                        line[len++] = b;
                        CountDownLatch r = ready;
                        if (r.getCount() > 0) {
                            match = b == DONE[match] ? match + 1 : b == DONE[0] ? 1 : 0;
                            if (match == DONE.length) {
                                r.countDown();
                                match = 0;
                            }
                        }
                        if (b == '\n' || len >= 16384) {
                            synchronized (OUT) {
                                OUT.write(line, 0, len);
                                if (b != '\n') OUT.write('\n');
                            }
                            len = 0;
                        }
                    }
                }
            } catch (IOException ignored) {
                // the process ended
            }
            if (len > prefix.length) {
                synchronized (OUT) {
                    OUT.write(line, 0, len);
                    OUT.write('\n');
                }
            }
            OUT.flush();
        }

        synchronized void send(String line) {
            if (!alive() || stdin == null) return;
            try {
                stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException ignored) {
                // it's ending
            }
        }

        /** True once it printed "Done (" - false if it ended (or took longer than {@code seconds}) first. */
        boolean awaitReady(long seconds) throws InterruptedException {
            long until = System.currentTimeMillis() + seconds * 1000;
            while (System.currentTimeMillis() < until) {
                if (ready.await(1, TimeUnit.SECONDS)) return true;
                if (!alive()) return ready.getCount() == 0;
            }
            return false;
        }

        void waitFor(long seconds) {
            Process p = proc;
            if (p == null) return;
            try {
                p.waitFor(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        /** Once a second: notice an exit and decide whether it comes back (crash / restart: yes, a stop: no). */
        void tick() {
            if (busy || stopping) return;
            Process p = proc;
            long now = System.currentTimeMillis();
            if (p == null) {
                if (wanted && startAt > 0 && now >= startAt) start();
                return;
            }
            if (p.isAlive()) return;
            int code = p.exitValue();
            proc = null;
            if (restartAfterExit) {
                restartAfterExit = false;
                say(name + " stopped - starting it again");
                startAt = now + 2000;
                return;
            }
            if (code == 0 && !isProxy) {
                wanted = false;
                say(name + " stopped. Start it again in WavePanel, or type: start " + slug);
                return;
            }
            // a restart from WavePanel ends with its exit code too: only quick exits count towards giving up
            if (now - startedAt > 180_000 || now - firstCrash > 600_000) {
                firstCrash = now;
                crashes = 0;
            }
            if (++crashes > 5) {
                wanted = false;
                say(name + " keeps stopping right after it starts (exit code " + code + ") - left off. Type: start " + slug);
                return;
            }
            say(name + " ended (exit code " + code + ") - starting it again in 5 seconds");
            startAt = now + 5000;
        }
    }
}
