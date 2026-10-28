package me.nv08.wavepanel.agent;

import me.nv08.wavepanel.core.Core;
import me.nv08.wavepanel.core.Supervisor;
import me.nv08.wavepanel.mod.Bridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * WavePanel.jar as a Java agent, for servers that load neither plugins nor mods: vanilla, SpongeVanilla, limbo
 * servers, anything with a Minecraft port. Start the server with {@code java -javaagent:WavePanel.jar -jar server.jar}
 * (optionally {@code -javaagent:WavePanel.jar=WP-XXXX-XXXX} to connect on the first start, and {@code ,port=25566}
 * for a server that keeps its port somewhere other than server.properties).
 *
 * Once the server is up: a plugin/mod copy of the jar already running wins. Otherwise, where the game's own server
 * object can be read (vanilla 26.x and later is no longer obfuscated) the full mod version runs on it - TPS, players,
 * worlds and an in-game /wavepanel. Anything else gets the basic version: players from the server list ping, the
 * console, and commands typed into the console for it.
 */
public final class Agent {
    enum Kind { NATIVE, VANILLA, SPONGE, OTHER }

    private static Instrumentation inst;
    private static Kind kind;
    private static ConsoleTap tap;
    private static String code = "";
    private static boolean jline;
    private static final java.util.Map<String, String> options = new java.util.HashMap<>();

    private Agent() {}

    public static void premain(String args, Instrumentation i) { boot(args, i, true); }

    public static void agentmain(String args, Instrumentation i) { boot(args, i, false); }

    private static synchronized void boot(String args, Instrumentation i, boolean early) {
        if (inst != null || hostMode()) return;
        inst = i;
        for (String a : (args == null ? "" : args).trim().split("[,;\\s]+")) {
            int eq = a.indexOf('=');
            if (eq > 0) options.put(a.substring(0, eq).toLowerCase(Locale.ROOT), a.substring(eq + 1));
            else if (!a.isEmpty()) code = a;
        }
        kind = detect();
        // the console is only shared with servers that read it through System.in (vanilla's plain reader): JLine
        // (Sponge, Paper, proxies...) reads the stdin handle itself - two readers would each get half the typed lines
        if (early && (kind == Kind.VANILLA || (kind == Kind.OTHER && !jline && !terminal()))) tap = ConsoleTap.install();
        Thread t = new Thread(Agent::watch, "WavePanel-agent");
        t.setDaemon(true);
        t.start();
    }

    /**
     * This JVM is WavePanel's host mode (a split server: the host still passes -javaagent, but the jar it starts is
     * WavePanel.jar now) - no Minecraft server here; the parts it starts run their own copies.
     */
    private static boolean hostMode() {
        String cmd = System.getProperty("sun.java.command", "").trim();
        if (cmd.startsWith(Supervisor.class.getName())) return true;
        int end = cmd.toLowerCase(Locale.ROOT).indexOf(".jar");
        if (end < 0) return false;
        try (JarFile jar = new JarFile(cmd.substring(0, end + 4))) {
            Manifest m = jar.getManifest();
            return m != null && Supervisor.class.getName().equals(m.getMainAttributes().getValue("Main-Class"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Waits for the server to come up, then runs the best version of WavePanel there is for it. */
    private static void watch() {
        long upSince = 0;
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            if (System.getProperty("wavepanel.platform") != null) return;      // the plugin / mod copy runs here
            try {
                Object server = ServerFinder.find(inst);
                if (server != null && new Bridge(server).started()) {
                    Thread.sleep(3000);                                         // a mods/ copy starts at the same moment
                    if (System.getProperty("wavepanel.platform") != null) return;
                    if (startFull(server)) return;
                }
            } catch (Throwable ignored) {
                // not readable: the basic version below
            }
            boolean up = (tap != null && tap.sawDone()) || AgentPlatform.answers();
            if (!up) continue;
            if (upSince == 0) upSince = System.currentTimeMillis();
            if (System.currentTimeMillis() - upSince < 8000) continue;          // the full version gets the first go
            if (System.getProperty("wavepanel.platform") != null) return;
            AgentPlatform p = new AgentPlatform(kind, tap);
            Core c = Core.start(p);
            if (c != null) {
                if (tap != null) tap.handTo(c, p.log());
                c.connectOnce(code);
            }
            return;
        }
    }

    /**
     * The mod version on the server object itself, loaded next to the game's classes (on vanilla those sit in the
     * bundler's own class loader, which this jar's classes can't see from here).
     */
    private static boolean startFull(Object server) throws Exception {
        String mod = new Bridge(server).modName();
        String id = mod.isEmpty() ? "vanilla" : mod.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String name = id.equals("neoforge") ? "NeoForge" : mod.isEmpty() ? "Vanilla" : Character.toUpperCase(mod.charAt(0)) + mod.substring(1);
        URL jar = Agent.class.getProtectionDomain().getCodeSource().getLocation();
        ClassLoader cl = new ChildFirst(new URL[]{jar}, server.getClass().getClassLoader());
        Class<?> logType = Class.forName("me.nv08.wavepanel.core.Log", true, cl);
        Object log = logType.getMethod("log4j").invoke(null);
        Class.forName("me.nv08.wavepanel.mod.ModPlatform", true, cl)
             .getMethod("startOn", Object.class, String.class, String.class, logType, File.class)
             .invoke(null, server, id, name, log, new File("wavepanel"));        // the same folder as the basic version
        if (tap != null) tap.passThrough();                                     // the game's console knows /wavepanel now
        if (!code.isEmpty()) {
            Class<?> core = Class.forName("me.nv08.wavepanel.core.Core", true, cl);
            for (int i = 0; i < 40; i++) {                                      // started on the server thread: wait for it
                Object c = core.getMethod("get").invoke(null);
                if (c != null) {
                    core.getMethod("connectOnce", String.class).invoke(c, code);
                    break;
                }
                Thread.sleep(250);
            }
        }
        return true;
    }

    /** An option from the agent's arguments (port=...), or null. */
    static synchronized String option(String key) { return options.get(key); }

    // ------------------------------------------------------------------ what kind of server this is

    /** From the jar / main class being run: does a plugin or mod copy belong here instead, and which game is it. */
    static Kind detect() {
        String cmd = System.getProperty("sun.java.command", "").trim();
        String first = cmd.isEmpty() ? "" : cmd.split("\\s+")[0];
        String main = first;
        String inner = "";
        if (first.toLowerCase(Locale.ROOT).endsWith(".jar") && new File(first).isFile()) {
            try (JarFile jf = new JarFile(first)) {
                Manifest mf = jf.getManifest();
                String m = mf == null ? null : mf.getMainAttributes().getValue("Main-Class");
                main = m == null ? "" : m.trim();
                for (java.util.Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements() && !jline; ) {
                    String n = en.nextElement().getName();
                    jline = n.startsWith("org/jline/") || n.startsWith("jline/");
                }
                JarEntry versions = jf.getJarEntry("META-INF/versions.list");
                if (versions != null) {
                    try (InputStream in = jf.getInputStream(versions)) {
                        inner = new String(readAll(in), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    }
                }
            } catch (IOException ignored) {
                // judged by the name alone
            }
        }
        String[] nativePrefixes = {"io.papermc", "org.bukkit", "org.spigotmc", "net.md_5", "com.velocitypowered", "net.fabricmc",
                "org.quiltmc", "net.minecraftforge", "net.neoforged", "cpw.mods", "com.mohistmc", "io.izzel", "org.purpurmc"};
        for (String p : nativePrefixes) if (main.startsWith(p)) return Kind.NATIVE;
        if (main.startsWith("org.spongepowered")) return Kind.SPONGE;
        if (main.equals("net.minecraft.bundler.Main")) {
            return inner.contains("spigot") || inner.contains("craftbukkit") || inner.contains("paper") ? Kind.NATIVE : Kind.VANILLA;
        }
        if (main.startsWith("net.minecraft.server")) return Kind.VANILLA;
        return Kind.OTHER;
    }

    /** A real terminal on stdin: then a console library (JLine) may own it - only vanilla's plain reader is safe to share. */
    private static boolean terminal() {
        Object console = System.console();
        if (console == null) return false;
        try {
            return Boolean.TRUE.equals(console.getClass().getMethod("isTerminal").invoke(console));   // Java 22+
        } catch (Throwable t) {
            return true;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** This jar's classes again, but linked against the game's class loader (Brigadier, the game's own classes). */
    static final class ChildFirst extends URLClassLoader {
        ChildFirst(URL[] urls, ClassLoader parent) { super(urls, parent); }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("me.nv08.wavepanel.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }
}
