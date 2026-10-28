package me.nv08.wavepanel.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Split (Beta): one hosted server becomes a small network on the same machine. The host keeps starting "java -jar
 * &lt;its jar&gt;", but that jar is WavePanel.jar now, in host mode ({@link Supervisor}): a Velocity proxy on the server's
 * own port, this server (part 1, world and plugins untouched) on a 127.0.0.1 port behind it, and fresh parts next to
 * it - each with WavePanel.jar inside, connected to WavePanel as its own server. Each part's subdomain leads straight
 * to it (Velocity forced hosts); /server switches between them.
 *
 * Like the version installer, everything is downloaded and checked while the server still runs; the switch happens in
 * a shutdown hook once it has saved, and the JVM then exits with the restart code - the host starts the supervisor.
 * The files of a split live in wavepanel/split/ (split.json, proxy/, parts/&lt;name&gt;/, control/).
 */
final class Split {
    static final String DIR = "wavepanel/split";
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("fill-data.papermc.io", "fill.papermc.io", "api.papermc.io",
            "api.purpurmc.org", "meta.fabricmc.net", "maven.fabricmc.net", "piston-data.mojang.com", "launcher.mojang.com", "cdn.modrinth.com"));
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,23}");
    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;

    private final Core core;
    private final File root;

    Split(Core core, File root) {
        this.core = core;
        this.root = root;
    }

    /** What happens once the server has stopped - prepared while it runs. */
    final class Plan {
        String label;
        File hostJar, origJar, wpJar, splitDir;
        String slug1, secret;
        int port1;
        boolean online, fabric;
        final List<File[]> origExtras = new ArrayList<>();     // {staged file, destination}
        Map<String, Object> result = new LinkedHashMap<>();

        void apply(int code) {
            File log = new File(root, Installer.LOG);
            try {
                waitForGame();
                say(log, "Splitting this server: " + label);
                // the jar the host starts becomes WavePanel.jar in host mode; the server's own jar is copied next to it
                // (copied, not moved: Windows can't move a jar this JVM still has open)
                if (!origJar.exists()) Files.copy(hostJar.toPath(), origJar.toPath());
                try {
                    Files.copy(wpJar.toPath(), hostJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException locked) {
                    helper(wpJar, hostJar, log, wpJar);           // Windows: this JVM still holds the jar
                }
                Map<String, String> props = new LinkedHashMap<>();
                props.put("server-ip", "127.0.0.1");
                props.put("server-port", String.valueOf(port1));
                props.put("online-mode", "false");
                setProps(new File(root, "server.properties"), props);
                if (fabric) {
                    for (File[] x : origExtras) {
                        x[1].getParentFile().mkdirs();
                        Files.move(x[0].toPath(), x[1].toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    writeFabricProxy(root, secret);
                } else {
                    paperForwarding(root, true, online, secret);
                }
                File json = new File(splitDir, "split.json");
                Map<String, Object> s = Json.obj(Json.parse(read(json)));
                s.remove("pending");
                write(json, Json.write(s));
                say(log, "Split ready - the host starts WavePanel's host mode now: a proxy on the server's port, the parts behind it.");
            } catch (Throwable t) {
                say(log, "The split didn't finish: " + t + where(t) + " - nothing on the server was removed; wavepanel/split/ is kept.");
            } finally {
                Runtime.getRuntime().halt(code);
            }
        }
    }

    /** "server.split": checks, downloads, configs - everything except the switch itself. */
    Plan prepare(Map<String, Object> a) throws Exception {
        if (core.p.isProxy()) throw new JobLink.JobError(400, "A proxy can't be split - split one of the servers behind it");
        String id = core.p.id();
        boolean fabric = "fabric".equals(id) || "quilt".equals(id);
        if (!fabric && !"paper".equals(id) && !"folia".equals(id)) {
            throw new JobLink.JobError(400, "Splitting works on Paper, Purpur, Folia and Fabric servers for now (this one runs " + core.p.displayName() + ")");
        }
        if (!fabric && !new File(root, "config/paper-global.yml").isFile() && !new File(root, "paper.yml").isFile()) {
            throw new JobLink.JobError(400, "Splitting needs Paper (or Purpur / Folia): plain Spigot can't take Velocity's secure forwarding. Install Paper with the Version tab first.");
        }
        File splitDir = new File(root, DIR);
        if (part() != null || new File(splitDir, "split.json").isFile()) throw new JobLink.JobError(409, "This server is already split");
        File hostJar = new Installer(core, root).serverJar();

        Map<String, Object> proxy = Json.obj(a.get("proxy"));
        Map<String, Object> orig = Json.obj(a.get("orig"));
        List<Object> parts = Json.arr(a.get("parts"));
        if (parts.isEmpty() || parts.size() > 3) throw new JobLink.JobError(400, "Split into 2 to 4 parts");
        int java = Installer.javaMajor();
        needJava(java, proxy, "Velocity");
        for (Object o : parts) needJava(java, Json.obj(o), Json.s(Json.obj(o), "label"));

        Properties sp = props(new File(root, "server.properties"));
        int publicPort = intOr(sp.getProperty("server-port"), 25565);
        boolean online = !"false".equalsIgnoreCase(sp.getProperty("online-mode", "true").trim());
        String slug1 = slug(Json.s(orig, "slug"));
        Set<String> slugs = new HashSet<>();
        slugs.add(slug1);
        for (Object o : parts) if (!slugs.add(slug(Json.s(Json.obj(o), "slug")))) throw new JobLink.JobError(400, "Two parts have the same name");

        // free 127.0.0.1 ports for the parts (never the public one)
        List<Integer> ports = new ArrayList<>();
        for (int p = 25601; ports.size() < parts.size() + 1 && p < 26000; p++) if (p != publicPort && free(p)) ports.add(p);
        if (ports.size() < parts.size() + 1) throw new JobLink.JobError(409, "No free local ports for the parts");

        // the new parts connect the way this server does: same WavePanel address (and certificate pin), their own id
        String ownUrl = core.cfg.get("remote.url").trim();
        int at = ownUrl.lastIndexOf("/api/link/");
        if (at < 0) throw new JobLink.JobError(409, "This server's WavePanel connection looks unusual - connect it again with a code first");
        String linkBase = ownUrl.substring(0, at) + "/api/link/";

        String secret = secret();
        File proxyDir = new File(splitDir, "proxy"), control = new File(splitDir, "control"), stage = new File(splitDir, "stage");
        deleteTree(stage);
        if (!proxyDir.mkdirs() && !proxyDir.isDirectory() || !control.mkdirs() && !control.isDirectory() || !stage.mkdirs()) {
            throw new JobLink.JobError(500, "Could not create " + DIR);
        }
        core.log.info("Split: downloading Velocity + " + parts.size() + " new part(s)...");
        download(Json.s(proxy, "url"), new File(proxyDir, "velocity.jar"), Json.s(proxy, "sha256"), "", "");
        Files.write(new File(proxyDir, "forwarding.secret").toPath(), secret.getBytes(StandardCharsets.UTF_8));

        Plan plan = new Plan();
        plan.splitDir = splitDir;
        plan.hostJar = hostJar;
        plan.origJar = new File(splitDir, slug1 + "-server.jar");
        plan.slug1 = slug1;
        plan.secret = secret;
        plan.port1 = ports.get(0);
        plan.online = online;
        plan.fabric = fabric;
        plan.wpJar = new File(stage, "WavePanel.jar");
        Files.copy(selfJar().toPath(), plan.wpJar.toPath(), StandardCopyOption.REPLACE_EXISTING);

        // part 1 = this server: on Fabric it needs FabricProxy-Lite (+ Fabric API) to take Velocity's forwarding
        if (fabric) {
            for (Object o : Json.arr(orig.get("extra"))) {
                Map<String, Object> x = Json.obj(o);
                String file = safeName(Json.s(x, "file"));
                if (Json.b(x, "ifMissing") && hasJar(new File(root, "mods"), Json.s(x, "match"))) continue;
                File staged = new File(stage, file);
                download(Json.s(x, "url"), staged, "", "", Json.s(x, "sha512"));
                plan.origExtras.add(new File[]{staged, new File(new File(root, "mods"), file)});
            }
            if (!hasJar(new File(root, "mods"), "fabricproxy") && plan.origExtras.isEmpty()) {
                throw new JobLink.JobError(400, "FabricProxy-Lite is missing from the download list");
            }
        }

        // the new parts: fresh servers with WavePanel.jar already connected as their own server in WavePanel
        List<Object> partList = new ArrayList<>();
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("slug", slug1);
        p1.put("name", Json.s(orig, "name"));
        p1.put("dir", ".");
        p1.put("jar", DIR + "/" + slug1 + "-server.jar");
        p1.put("heapMB", intOr(Json.s(orig, "heapMB"), 2048));
        p1.put("port", plan.port1);
        p1.put("kind", fabric ? "fabric" : "paper");
        partList.add(p1);
        List<Object> resultParts = new ArrayList<>();
        resultParts.add(obj("slug", slug1, "port", plan.port1));
        for (int i = 0; i < parts.size(); i++) {
            Map<String, Object> np = Json.obj(parts.get(i));
            String slug = slug(Json.s(np, "slug"));
            String addons = Json.s(np, "addons");
            if (!"plugins".equals(addons) && !"mods".equals(addons)) throw new JobLink.JobError(400, "Unknown add-ons folder " + addons);
            int port = ports.get(i + 1);
            File dir = new File(new File(splitDir, "parts"), slug);
            if (dir.exists()) deleteTree(dir);
            if (!new File(dir, addons).mkdirs()) throw new JobLink.JobError(500, "Could not create " + dir);
            core.log.info("Split: " + Json.s(np, "label") + " for part '" + Json.s(np, "name") + "'");
            download(Json.s(np, "url"), new File(dir, "server.jar"), Json.s(np, "sha256"), Json.s(np, "md5"), "");
            for (Object o : Json.arr(np.get("extra"))) {
                Map<String, Object> x = Json.obj(o);
                download(Json.s(x, "url"), new File(new File(dir, "mods"), safeName(Json.s(x, "file"))), "", "", Json.s(x, "sha512"));
            }
            Map<String, String> props = new LinkedHashMap<>();
            props.put("server-ip", "127.0.0.1");
            props.put("server-port", String.valueOf(port));
            props.put("online-mode", "false");
            props.put("motd", Json.s(np, "name"));
            props.put("max-players", sp.getProperty("max-players", "20"));
            setProps(new File(dir, "server.properties"), props);
            if (Json.b(np, "eula")) {
                String who = Json.s(a, "who");
                write(new File(dir, "eula.txt"), "#By changing the setting below to TRUE you are indicating your agreement to the Minecraft EULA "
                        + "(https://aka.ms/MinecraftEULA).\n#Accepted in WavePanel" + (who.isEmpty() ? "" : " by " + who) + " on "
                        + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date()) + "\neula=true\n");
            }
            boolean fab = "mods".equals(addons);
            Files.copy(selfJar().toPath(), new File(new File(dir, addons), "WavePanel.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
            File dataDir = fab ? new File(new File(dir, "config"), "wavepanel") : new File(new File(dir, "plugins"), "WavePanel");
            dataDir.mkdirs();
            Map<String, Object> link = Json.obj(np.get("link"));
            String linkId = Json.s(link, "id");
            if (!linkId.matches("[a-z0-9-]{1,64}") || Json.s(link, "secret").length() < 16) throw new JobLink.JobError(400, "Bad connection for part " + slug);
            Config c = new Config(new File(dataDir, "config.yml"));
            c.set("panel", core.cfg.get("panel"));
            c.set("remote.enabled", true);
            c.set("remote.url", linkBase + linkId);
            c.set("remote.secret", Json.s(link, "secret"));
            c.set("remote.name", Json.s(np, "name"));
            c.set("remote.cert-sha256", core.cfg.get("remote.cert-sha256"));
            c.save();
            if (fab) writeFabricProxy(dir, secret);

            Map<String, Object> pp = new LinkedHashMap<>();
            pp.put("slug", slug);
            pp.put("name", Json.s(np, "name"));
            pp.put("dir", DIR + "/parts/" + slug);
            pp.put("jar", "server.jar");
            pp.put("heapMB", intOr(Json.s(np, "heapMB"), 2048));
            pp.put("port", port);
            pp.put("kind", fab ? "fabric" : "paper");
            pp.put("first", !fab);                         // Paper: one first start makes its configs, then forwarding goes on
            partList.add(pp);
            resultParts.add(obj("slug", slug, "port", port));
        }

        Map<String, Object> restore = new LinkedHashMap<>();
        restore.put("hostJar", rel(hostJar));
        restore.put("origJar", DIR + "/" + slug1 + "-server.jar");
        restore.put("serverIp", sp.getProperty("server-ip", ""));
        restore.put("serverPort", String.valueOf(publicPort));
        restore.put("onlineMode", String.valueOf(online));
        restore.put("kind", fabric ? "fabric" : "paper");
        List<Object> extras = new ArrayList<>();
        for (File[] x : plan.origExtras) extras.add(rel(x[1]));
        restore.put("addedMods", extras);

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("v", 1);
        s.put("pending", true);
        s.put("restartCode", core.cfg.integer("remote.restart-exit-code", 1));
        s.put("proxy", obj("dir", DIR + "/proxy", "jar", "velocity.jar", "heapMB", intOr(Json.s(a, "proxyHeapMB"), 384),
                "port", publicPort, "bind", sp.getProperty("server-ip", "").trim().isEmpty() ? "0.0.0.0" : sp.getProperty("server-ip").trim(),
                "online", online, "maxPlayers", intOr(sp.getProperty("max-players"), 20)));
        s.put("parts", partList);
        s.put("hosts", Json.obj(a.get("hosts")));
        s.put("restore", restore);
        s.put("who", Json.s(a, "who"));
        s.put("since", System.currentTimeMillis());
        write(new File(splitDir, "split.json"), Json.write(s));

        plan.label = (parts.size() + 1) + " parts behind a proxy on port " + publicPort;
        plan.result.put("parts", resultParts);
        plan.result.put("proxyPort", publicPort);
        plan.result.put("java", java);
        return plan;
    }

    // ------------------------------------------------------------------ the panel's small requests to a split

    /** split.hosts / split.start / server.unsplit: a note for the supervisor (control/), from any part of the split. */
    static Map<String, Object> control(String name, String content) throws IOException {
        File dir = splitDir();
        if (dir == null) throw new JobLink.JobError(409, "This server isn't part of a split");
        File control = new File(dir, "control");
        control.mkdirs();
        File tmp = new File(control, name + ".tmp");
        write(tmp, content);
        Files.move(tmp.toPath(), new File(control, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return obj("ok", true);
    }

    /** The split this JVM runs in (the supervisor tells every part), or null. */
    static File splitDir() {
        String env = System.getenv("WAVEPANEL_SPLIT");
        if (env != null && new File(env, "split.json").isFile()) return new File(env);
        File here = new File(DIR);
        return new File(here, "split.json").isFile() ? here.getAbsoluteFile() : null;
    }

    static String part() { return System.getenv("WAVEPANEL_PART"); }

    // ------------------------------------------------------------------ config files

    static Properties props(File f) {
        Properties p = new Properties();
        if (f.isFile()) {
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException ignored) {
                // an unreadable file = the defaults
            }
        }
        return p;
    }

    /** Sets keys in a server.properties, keeping every other line (and its order / escapes) as it is. */
    static void setProps(File f, Map<String, String> set) throws IOException {
        List<String> lines = f.isFile() ? new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) : new ArrayList<String>();
        Set<String> done = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            int eq = l.indexOf('=');
            if (eq <= 0 || l.trim().startsWith("#")) continue;
            String k = l.substring(0, eq).trim();
            if (set.containsKey(k)) {
                lines.set(i, k + "=" + set.get(k));
                done.add(k);
            }
        }
        for (Map.Entry<String, String> e : set.entrySet()) if (!done.contains(e.getKey())) lines.add(e.getKey() + "=" + e.getValue());
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
    }

    /** Paper's Velocity forwarding (config/paper-global.yml proxies.velocity, or paper.yml settings.velocity-support before 1.19). */
    static boolean paperForwarding(File serverDir, boolean enabled, boolean online, String secret) throws IOException {
        File global = new File(serverDir, "config/paper-global.yml");
        if (global.isFile()) return yamlBlock(global, new String[]{"proxies:", "  velocity:"}, "    ", enabled, online, secret);
        File old = new File(serverDir, "paper.yml");
        if (old.isFile()) return yamlBlock(old, new String[]{"settings:", "  velocity-support:"}, "    ", enabled, online, secret);
        return false;
    }

    private static boolean yamlBlock(File f, String[] path, String indent, boolean enabled, boolean online, String secret) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        int at = -1;
        for (int i = 0, depth = 0; i < lines.size(); i++) {
            if (lines.get(i).equals(path[depth])) {
                if (++depth == path.length) { at = i; break; }
            }
        }
        if (at < 0) return false;
        for (int i = at + 1; i < lines.size() && lines.get(i).startsWith(indent); i++) {
            String l = lines.get(i), key = l.trim();
            if (key.startsWith("enabled:")) lines.set(i, indent + "enabled: " + enabled);
            else if (key.startsWith("online-mode:")) lines.set(i, indent + "online-mode: " + online);
            else if (key.startsWith("secret:") && secret != null) lines.set(i, indent + "secret: '" + secret + "'");
        }
        Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
        return true;
    }

    /** FabricProxy-Lite: Velocity modern forwarding for Fabric (config/FabricProxy-Lite.toml). */
    static void writeFabricProxy(File serverDir, String secret) throws IOException {
        File f = new File(serverDir, "config/FabricProxy-Lite.toml");
        f.getParentFile().mkdirs();
        write(f, "hackOnlineMode = true\nhackEarlySend = false\nhackMessageChain = true\n"
                + "disconnectMessage = \"This server requires you to connect with Velocity.\"\nsecret = \"" + secret + "\"\n");
    }

    // ------------------------------------------------------------------ helpers

    private void needJava(int have, Map<String, Object> m, String label) throws JobLink.JobError {
        int need = intOr(Json.s(m, "javaMin"), 0);
        if (need > have) {
            throw new JobLink.JobError(409, "This server runs Java " + have + ", " + label + " needs Java " + need
                    + " or newer. Change the Java version in your host's panel first (often under Startup / Docker image).");
        }
    }

    private void download(String url, File to, String sha256, String md5, String sha512) throws Exception {
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || !HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT))) {
            throw new JobLink.JobError(400, "Not an official download site: " + u.getHost());
        }
        // Fabric's server launcher is put together on request: its site has no checksum for it (the installer takes it the same way)
        if (sha256.isEmpty() && md5.isEmpty() && sha512.isEmpty() && !"meta.fabricmc.net".equals(u.getHost())) {
            throw new JobLink.JobError(400, "No checksum for " + to.getName());
        }
        to.getParentFile().mkdirs();
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", core.userAgent() + " (+https://wavepanel.xyz)");
        int code = c.getResponseCode();
        if (code != 200) throw new JobLink.JobError(502, "The download site answered " + code + " for " + u);
        MessageDigest d256 = MessageDigest.getInstance("SHA-256"), d5 = MessageDigest.getInstance("MD5"), d512 = MessageDigest.getInstance("SHA-512");
        long total = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_DOWNLOAD) throw new JobLink.JobError(413, "That download is bigger than " + (MAX_DOWNLOAD >> 20) + " MB");
                d256.update(buf, 0, n);
                d5.update(buf, 0, n);
                d512.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        if (!sha256.isEmpty() && !hex(d256.digest()).equalsIgnoreCase(sha256)
                || !md5.isEmpty() && !hex(d5.digest()).equalsIgnoreCase(md5)
                || !sha512.isEmpty() && !hex(d512.digest()).equalsIgnoreCase(sha512)) {
            Files.deleteIfExists(to.toPath());
            throw new JobLink.JobError(502, "The download of " + to.getName() + " is damaged (checksum doesn't match) - try again");
        }
        try (InputStream in = new FileInputStream(to)) {
            byte[] head = new byte[2];
            if (in.read(head) != 2 || head[0] != 'P' || head[1] != 'K') throw new JobLink.JobError(502, to.getName() + " isn't a jar file");
        }
        core.log.info("Split: " + to.getName() + " (" + (total >> 10) + " KB) from " + u.getHost() + ", checksum OK");
    }

    private static boolean hasJar(File dir, String part) {
        File[] kids = dir.listFiles();
        if (kids == null || part.isEmpty()) return false;
        for (File k : kids) if (k.getName().toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT)) && k.getName().endsWith(".jar")) return true;
        return false;
    }

    private static boolean free(int port) {
        try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String secret() {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 24; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    static String slug(String s) throws JobLink.JobError {
        if (!SLUG.matcher(s).matches()) throw new JobLink.JobError(400, "Bad part name '" + s + "'");
        return s;
    }

    private static String safeName(String s) throws JobLink.JobError {
        if (!s.matches("[\\w.+-]{1,120}\\.jar")) throw new JobLink.JobError(400, "Bad file name " + s);
        return s;
    }

    private String rel(File f) throws IOException {
        String r = root.getCanonicalPath(), p = f.getCanonicalPath();
        return p.startsWith(r + File.separator) ? p.substring(r.length() + 1).replace(File.separatorChar, '/') : p;
    }

    static int intOr(String s, int dflt) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    private static File selfJar() throws Exception {
        File f = new File(Split.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!f.isFile()) throw new JobLink.JobError(500, "Can't find WavePanel.jar itself (" + f + ")");
        return f;
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    static void deleteTree(File f) throws IOException {
        if (!f.exists()) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        Files.deleteIfExists(f.toPath());
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** The game's main thread finishes saving first - unless it's the one ending the JVM. */
    static void waitForGame() throws InterruptedException {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!"Server thread".equals(t.getName()) || t == Thread.currentThread()) continue;
            boolean exiting = false;
            for (StackTraceElement e : t.getStackTrace()) {
                if ("java.lang.Shutdown".equals(e.getClassName()) || "java.lang.Runtime".equals(e.getClassName()) && "exit".equals(e.getMethodName())) exiting = true;
            }
            if (!exiting) t.join(120_000);
        }
        Thread.sleep(1500);
    }

    /**
     * Windows can't replace a running jar: a small helper process does it right after this JVM is gone. It runs from
     * its own copy of a WavePanel.jar ({@code wavepanel}, copied to the temp folder) - a jar it has open is one it
     * couldn't move either. Runs in shutdown hooks: nothing in here may load a class from the plugin's (closed) jar.
     */
    static void helper(File from, File to, File log, File wavepanel) throws IOException {
        File bin = new File(System.getProperty("java.home"), "bin");
        File exe = new File(bin, "java.exe").isFile() ? new File(bin, "java.exe") : new File(bin, "java");
        File cp = File.createTempFile("wavepanel-finisher-", ".jar");
        Files.copy(wavepanel.toPath(), cp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        File keep = new File(to.getParentFile(), ".wavepanel-swap-" + System.currentTimeMillis() + ".jar");
        Files.copy(from.toPath(), keep.toPath(), StandardCopyOption.REPLACE_EXISTING);
        new ProcessBuilder(exe.getPath(), "-cp", cp.getPath(), InstallFinisher.NAME, keep.getPath(), to.getPath(), "")
                .directory(to.getParentFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start();
    }

    /** " (at Class.method:line)" - the first of WavePanel's own frames, for the install log. */
    static String where(Throwable t) {
        for (StackTraceElement e : t.getStackTrace()) {
            if (e.getClassName().startsWith("me.nv08.")) return " (at " + e.getClassName().replaceFirst(".*\\.", "") + "." + e.getMethodName() + ":" + e.getLineNumber() + ")";
        }
        return "";
    }

    static void say(File log, String msg) {
        System.out.println("[WavePanel] " + msg);
        System.out.flush();
        try {
            Files.write(log.toPath(), (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + msg + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the console line is all there is then
        }
    }
}
