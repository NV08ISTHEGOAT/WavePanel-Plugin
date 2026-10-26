package me.nv08.wavepanel.bukkit;

import me.nv08.wavepanel.core.Config;
import me.nv08.wavepanel.core.Core;
import me.nv08.wavepanel.core.Log;
import me.nv08.wavepanel.core.Platform;
import me.nv08.wavepanel.core.Snapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * WavePanel.jar on Bukkit-family servers: Spigot, Paper, Purpur, Pufferfish, Leaf, Leaves, DivineMC, ASPaper, the
 * hybrids (Arclight, Mohist, Magma) - 1.8 to today - and Folia / Canvas, whose regions run on many threads (no Bukkit
 * scheduler there: work goes through the global region scheduler, read through reflection so one jar fits all).
 *
 * Paper-only numbers (TPS, MSPT, ping, entity/chunk counts) are read through reflection, so a plain Spigot server
 * just reports what it has instead of failing to load.
 */
public final class WavePanelBukkit extends JavaPlugin implements Listener, Platform {
    private static final Method TPS = staticMethod(Bukkit.class, "getTPS");
    private static final Method MSPT = staticMethod(Bukkit.class, "getAverageTickTime");
    private static final boolean FOLIA = hasClass("io.papermc.paper.threadedregions.RegionizedServer");

    private Core core;
    private Log log;
    private Object globalScheduler;                           // Folia: Bukkit.getGlobalRegionScheduler()
    private Method globalExecute;

    @Override
    public void onEnable() {
        log = Log.of(getLogger());
        if (FOLIA) {
            try {
                globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                globalExecute = globalScheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
            } catch (Exception e) {
                log.error("Folia detected but its scheduler isn't reachable (" + e + ") - WavePanel stays off.");
                return;
            }
        }
        takeOverPanelLink();
        core = Core.start(this);
        if (core == null) return;
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (core != null) core.stop();
        core = null;
    }

    /**
     * Up to 2.3 this plugin was PanelLink: carry its config (and so its connection) and its backups over the first time,
     * and switch an old PanelLink.jar off if it's still installed - two of them would both push and run every command
     * from the panel twice. (plugin.yml softdepends on PanelLink, so it is already enabled by now.)
     */
    private void takeOverPanelLink() {
        File mine = getDataFolder();
        File old = new File(mine.getParentFile(), "PanelLink");
        File oldCfg = new File(old, "config.yml");
        if (!new File(mine, "config.yml").exists() && oldCfg.isFile()) {
            try {
                if (!mine.isDirectory() && !mine.mkdirs()) throw new IOException("could not create " + mine);
                Config c = new Config(new File(mine, "config.yml"));
                for (java.util.Map.Entry<String, String> e : Config.read(Files.readAllLines(oldCfg.toPath(), StandardCharsets.UTF_8)).entrySet()) {
                    c.set(e.getKey(), e.getValue());
                }
                c.save();
                File oldBk = new File(old, "backups"), newBk = new File(mine, "backups");
                if (oldBk.isDirectory() && !newBk.exists() && !oldBk.renameTo(newBk)) {
                    log.warn("Your old backups stay in plugins/PanelLink/backups (could not move them).");
                }
                log.info("Took over PanelLink's settings - this server stays connected to WavePanel.");
            } catch (IOException e) {
                log.warn("Could not take over plugins/PanelLink/config.yml (" + e.getMessage() + ") - connect again with /wavepanel connect <code>.");
            }
        }
        Plugin pl = getServer().getPluginManager().getPlugin("PanelLink");
        if (pl != null && pl != this) {
            if (pl.isEnabled()) getServer().getPluginManager().disablePlugin(pl);
            log.warn("PanelLink.jar is still in your plugins folder - WavePanel.jar replaces it, so it has been switched off. Delete PanelLink.jar.");
        }
    }

    // ------------------------------------------------------------------ Platform

    @Override public String id() { return FOLIA ? "folia" : "paper"; }

    @Override
    public String displayName() {
        String name = Bukkit.getName();                       // "Paper", "Purpur", "Folia", "CraftBukkit"...
        if ("CraftBukkit".equals(name) && Bukkit.getVersion().contains("Spigot")) name = "Spigot";
        return (FOLIA && !"Folia".equalsIgnoreCase(name) ? name + " (Folia)" : name) + " " + Bukkit.getBukkitVersion().replaceFirst("(-R\\d.*|\\.build\\..*|-SNAPSHOT.*)$", "");   // "1.21.4-R0.1-SNAPSHOT", "26.3.build.222-stable"
    }

    @Override public File dataDir() { return getDataFolder(); }
    @Override public File logFile() { return new File("logs", "latest.log"); }
    @Override public String addonsFolder() { return "plugins"; }
    @Override public boolean isProxy() { return false; }
    @Override public Log log() { return log; }

    @Override
    public void runSync(Runnable r) {
        if (!isEnabled()) return;
        if (FOLIA) {
            try {
                globalExecute.invoke(globalScheduler, this, r);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        } else {
            getServer().getScheduler().runTask(this, r);
        }
    }

    @Override
    public void dispatch(String command) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public void collect(Snapshot s) {
        s.software = Bukkit.getVersion();
        double[] tps = tps();
        if (tps != null) s.tps = new double[]{tps[0], tps[1], tps[2]};
        Double mspt = invokeDouble(MSPT, null);
        if (mspt != null) s.mspt = mspt;
        s.online = Bukkit.getOnlinePlayers().size();
        s.max = Bukkit.getMaxPlayers();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Snapshot.Player x = new Snapshot.Player();
            x.name = p.getName();
            x.uuid = p.getUniqueId().toString();
            try {                                             // Folia: another region's player - read what we can
                Location l = p.getLocation();
                x.world = l.getWorld() == null ? "?" : l.getWorld().getName();
                x.x = l.getBlockX();
                x.y = l.getBlockY();
                x.z = l.getBlockZ();
                x.gamemode = p.getGameMode().name().toLowerCase(Locale.ROOT);
                x.health = p.getHealth();
                x.maxHealth = maxHealth(p);
                x.food = p.getFoodLevel();
            } catch (Throwable ignored) {
                // left unknown
            }
            x.ping = ping(p);
            x.op = p.isOp();
            s.players.add(x);
        }
        for (World w : Bukkit.getWorlds()) {
            Snapshot.World x = new Snapshot.World();
            x.name = w.getName();
            x.env = w.getEnvironment().name().toLowerCase(Locale.ROOT);
            try {
                x.players = w.getPlayers().size();
                x.entities = entities(w);
                x.chunks = chunks(w);
            } catch (Throwable ignored) {
                // Folia: a world's lists belong to its regions - counts left unknown
            }
            s.worlds.add(x);
        }
    }

    /**
     * A real restart script (spigot.yml settings.restart-script exists - a VPS / own machine): Spigot's restart.
     * Otherwise (ZAP-Hosting, Pterodactyl, PebbleHost...): save and stop, ending with the exit code the host restarts on.
     */
    @Override
    public void restart(int exitCode) {
        String script = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File("spigot.yml"))
                .getString("settings.restart-script", "./start.sh");
        File f = script == null ? null : new File(script.trim().split("\\s+")[0]);
        if (!FOLIA && !Core.installing() && f != null && f.isFile()) {
            log.info("Restarting with Spigot's restart script " + f.getPath() + ".");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "restart");
            return;
        }
        Core.haltOnExit(exitCode);
        Bukkit.shutdown();
    }

    // ------------------------------------------------------------------ /wavepanel

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (core == null) {
            s.sendMessage("§c[WavePanel] Not running - see the server log.");
            return true;
        }
        final CommandSender who = s;
        Consumer<String> reply = who::sendMessage;
        core.command(a, reply);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : new String[]{"connect", "status", "disconnect"}) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        }
        return out;
    }

    /** "/wavepanel connect ..." pasted into the console with its slash: the console wants it without one. */
    @EventHandler(ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        String c = e.getCommand();
        if (c != null && c.regionMatches(true, 0, "/wavepanel", 0, 10)) e.setCommand(c.substring(1));
    }

    /** The same over RCON (some hosts' web consoles) - that event has its own listener list. */
    @EventHandler(ignoreCancelled = true)
    public void onRemoteConsoleCommand(RemoteServerCommandEvent e) {
        onConsoleCommand(e);
    }

    // ------------------------------------------------------------------ version-safe reads

    private static double[] tps() {
        if (TPS != null) {
            try {
                Object o = TPS.invoke(null);
                if (o instanceof double[] && ((double[]) o).length >= 3) return (double[]) o;
            } catch (Throwable ignored) {
                // Folia: no server-wide TPS - try the spawn region below
            }
        }
        if (FOLIA) {
            try {
                World w = Bukkit.getWorlds().get(0);
                Object o = Bukkit.getServer().getClass().getMethod("getRegionTPS", Location.class).invoke(Bukkit.getServer(), w.getSpawnLocation());
                if (o instanceof double[] && ((double[]) o).length >= 3) return (double[]) o;
            } catch (Throwable ignored) {
                // no region TPS either
            }
        }
        return null;
    }

    private static int ping(Player p) {
        try {
            return (Integer) p.getClass().getMethod("getPing").invoke(p);      // Spigot/Paper 1.17+
        } catch (Throwable t) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static double maxHealth(Player p) {
        try {
            return p.getMaxHealth();                                           // there since 1.6, deprecated but kept
        } catch (Throwable t) {
            return 20.0;
        }
    }

    private static int entities(World w) {
        try {
            return (Integer) w.getClass().getMethod("getEntityCount").invoke(w); // Paper: no list copy
        } catch (Throwable t) {
            return w.getEntities().size();
        }
    }

    private static int chunks(World w) {
        try {
            return (Integer) w.getClass().getMethod("getChunkCount").invoke(w);  // Paper
        } catch (Throwable t) {
            return w.getLoadedChunks().length;
        }
    }

    private static Method staticMethod(Class<?> c, String name) {
        try {
            return c.getMethod(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Double invokeDouble(Method m, Object on) {
        if (m == null) return null;
        try {
            Object o = m.invoke(on);
            return o instanceof Number ? ((Number) o).doubleValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
