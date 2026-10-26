package me.nv08.wavepanel.mod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import me.nv08.wavepanel.core.Core;
import me.nv08.wavepanel.core.Log;
import me.nv08.wavepanel.core.Platform;
import me.nv08.wavepanel.core.Snapshot;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WavePanel.jar as a server-side mod: Fabric / Quilt (mods/, no Fabric API needed), Forge and NeoForge - and vanilla
 * 26.x when the jar runs as a Java agent. Minecraft itself is read through {@link Bridge}; /wavepanel goes straight
 * into the game's command tree (Brigadier), so it works in the console, over RCON and in-game for admins.
 */
public final class ModPlatform implements Platform {
    private static final Pattern AFTER_WORD = Pattern.compile("(?i)(?:^|[\\s/])wavepanel(?:\\s+(.*))?$");
    private final String id, name;
    private final Bridge mc;
    private final Log log;
    private final File dir;
    private final TickRate rate = new TickRate();
    private Core core;
    private Object registeredIn;                               // the command dispatcher /wavepanel is in (a /reload makes a new one)

    private ModPlatform(String id, String name, Object server, Log log, File dir) {
        this.id = id;
        this.name = name;
        this.mc = new Bridge(server);
        this.log = log;
        this.dir = dir;
    }

    /**
     * From a loader's entry point, which runs before the server exists: waits on its own thread for the server
     * ({@code finder} returns it, or null while it isn't there yet) to tick, then starts WavePanel on it.
     */
    static void launch(final String id, final String name, final Supplier<Object> finder) {
        final Log log = Log.log4j();
        if (hasClass("net.minecraft.client.Minecraft")) {
            log.info("WavePanel is a server mod - it stays off in the game client.");
            return;
        }
        Thread t = new Thread(() -> {
            long since = System.currentTimeMillis();
            boolean told = false;
            while (true) {
                Object server = null;
                try {
                    server = finder.get();
                } catch (Throwable ignored) {
                    // not there yet
                }
                if (server != null) {
                    Bridge b = new Bridge(server);
                    if (b.started()) {
                        if (!b.dedicated()) return;              // single player's built-in server
                        startOn(server, id, name, log);
                        return;
                    }
                }
                if (!told && System.currentTimeMillis() - since > 600_000) {
                    log.warn("WavePanel is still waiting for the server to finish starting.");
                    told = true;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "WavePanel-start");
        t.setDaemon(true);
        t.start();
    }

    /** The server is up: run WavePanel on it (called once it ticks - by {@link #launch}), settings in config/wavepanel. */
    public static void startOn(Object server, String id, String name, Log log) {
        startOn(server, id, name, log, new File("config", "wavepanel"));
    }

    /** The same with another settings folder: the agent keeps wavepanel/ whichever version of itself runs. */
    public static void startOn(Object server, String id, String name, Log log, File dir) {
        final ModPlatform p = new ModPlatform(id, name, server, log, dir);
        p.mc.execute(() -> {
            p.core = Core.start(p);
            if (p.core != null) p.ensureCommand();
        });
    }

    // ------------------------------------------------------------------ Platform

    @Override public String id() { return id; }

    @Override public String displayName() { return name + " " + mc.mcVersion(); }

    @Override public File dataDir() { return dir; }
    @Override public File logFile() { return new File("logs", "latest.log"); }
    @Override public String addonsFolder() { return new File("mods").isDirectory() || !"vanilla".equals(id) ? "mods" : null; }
    @Override public boolean isProxy() { return false; }
    @Override public Log log() { return log; }

    @Override
    public void runSync(Runnable r) {
        if (mc.running()) mc.execute(r);
    }

    @Override
    public void dispatch(String command) {
        mc.runCommand(command);
    }

    @Override
    public void collect(Snapshot s) {
        String loader = loaderVersion();
        s.software = name + (loader.isEmpty() ? "" : " " + loader) + " (MC: " + mc.mcVersion() + ")";
        mc.collect(s);
        long now = System.currentTimeMillis();
        rate.sample(now, mc.tickCount());
        s.tps = rate.tps();
        s.mspt = mc.mspt();
        ensureCommand();
    }

    /** Stop pressed with an exit code the host restarts on: a normal "stop", then that exit code. */
    @Override
    public void restart(int exitCode) {
        Core.haltOnExit(exitCode);
        mc.runCommand("stop");
    }

    // ------------------------------------------------------------------ /wavepanel

    /** Puts /wavepanel into the server's command tree - again after a /reload, which builds a new one. */
    @SuppressWarnings("unchecked")
    private void ensureCommand() {
        Object d = mc.dispatcher();
        if (d == null || d == registeredIn) return;
        try {
            CommandDispatcher<Object> dispatcher = (CommandDispatcher<Object>) d;
            if (dispatcher.getRoot().getChild("wavepanel") == null) {
                dispatcher.register(LiteralArgumentBuilder.<Object>literal("wavepanel")
                        .requires(mc::isAdmin)
                        .executes(this::run)
                        .then(LiteralArgumentBuilder.<Object>literal("connect")
                                .executes(this::run)
                                .then(RequiredArgumentBuilder.<Object, String>argument("code", StringArgumentType.greedyString()).executes(this::run)))
                        .then(LiteralArgumentBuilder.<Object>literal("status").executes(this::run))
                        .then(LiteralArgumentBuilder.<Object>literal("disconnect").executes(this::run)));
            }
            registeredIn = d;
        } catch (Throwable t) {
            registeredIn = d;
            log.warn("Could not add /wavepanel to the server's commands (" + t + ").");
        }
    }

    /**
     * The words after /wavepanel, from what Brigadier parsed (connect / status / disconnect + the code) - or from the
     * typed line when that says more. Not every server's parse starts at "wavepanel": Sponge hands over the part after it.
     */
    private int run(CommandContext<Object> ctx) {
        final Object source = ctx.getSource();
        List<String> words = new ArrayList<>();
        for (ParsedCommandNode<Object> n : ctx.getNodes()) {
            if (n.getNode() instanceof LiteralCommandNode) {
                String lit = ((LiteralCommandNode<?>) n.getNode()).getLiteral();
                if (!lit.equalsIgnoreCase("wavepanel")) words.add(lit);
            }
        }
        try {
            words.add(ctx.getArgument("code", String.class));
        } catch (IllegalArgumentException none) {
            // connect without a code, status, disconnect
        }
        Matcher m = AFTER_WORD.matcher(ctx.getInput().trim());
        String[] typed = m.find() && m.group(1) != null ? m.group(1).trim().split("\\s+") : new String[0];
        String[] a = typed.length > words.size() ? typed : words.toArray(new String[0]);
        Core c = core;
        if (c == null) return 0;
        c.command(a, msg -> {
            if (!mc.tell(source, msg)) log.info(msg);
        });
        return 1;
    }

    // ------------------------------------------------------------------ loaders

    /** "0.17.2" (Fabric Loader), "61.2.4" (Forge), "21.4.161" (NeoForge) - or "" when it can't be read. */
    private String loaderVersion() {
        try {
            if (id.equals("fabric") || id.equals("quilt")) {
                Class<?> api = Class.forName("net.fabricmc.loader.api.FabricLoader");
                Object loader = api.getMethod("getInstance").invoke(null);
                Object opt = api.getMethod("getModContainer", String.class).invoke(loader, id.equals("quilt") ? "quilt_loader" : "fabricloader");
                Object mod = ((java.util.Optional<?>) opt).orElse(null);
                if (mod == null) return "";
                Object meta = Class.forName("net.fabricmc.loader.api.ModContainer").getMethod("getMetadata").invoke(mod);
                Object ver = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata").getMethod("getVersion").invoke(meta);
                return String.valueOf(Class.forName("net.fabricmc.loader.api.Version").getMethod("getFriendlyString").invoke(ver));
            }
            if (id.equals("forge")) return String.valueOf(staticCall("net.minecraftforge.versions.forge.ForgeVersion", "getVersion"));
            if (id.equals("neoforge")) {
                Object v = staticCall("net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion", "getVersion");
                if (v == null) v = staticCall("net.neoforged.neoforge.versions.neoforge.NeoForgeVersion", "getVersion");
                if (v == null) v = staticCall("net.neoforged.neoforge.common.NeoForgeVersion", "getVersion");      // 26.x
                return v == null ? "" : String.valueOf(v);
            }
        } catch (Throwable ignored) {
            // shown without it
        }
        return "";
    }

    /** Fabric / Quilt: the server instance the loader keeps once it's built. */
    static Object fabricServer() {
        try {
            Class<?> api = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object loader = api.getMethod("getInstance").invoke(null);
            return api.getMethod("getGameInstance").invoke(loader);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object staticCall(String cls, String method) {
        try {
            Method m = Class.forName(cls, true, ModPlatform.class.getClassLoader()).getMethod(method);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean hasClass(String name) {
        try {
            Class.forName(name, false, ModPlatform.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
