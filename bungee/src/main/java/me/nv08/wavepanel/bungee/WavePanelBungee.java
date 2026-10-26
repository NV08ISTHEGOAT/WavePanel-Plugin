package me.nv08.wavepanel.bungee;

import me.nv08.wavepanel.core.Core;
import me.nv08.wavepanel.core.Log;
import me.nv08.wavepanel.core.Platform;
import me.nv08.wavepanel.core.Snapshot;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.File;

/**
 * WavePanel.jar on BungeeCord and Waterfall: the proxy's players (and which backend each is on), its backend servers
 * as the "worlds" list, its console and its commands. A proxy has no worlds or TPS of its own.
 */
public final class WavePanelBungee extends Plugin implements Platform {
    private Core core;
    private Log log;

    @Override
    public void onEnable() {
        log = Log.of(getLogger());
        core = Core.start(this);
        if (core == null) return;
        getProxy().getPluginManager().registerCommand(this, new Command("wavepanel", "wavepanel.admin") {
            @Override
            public void execute(final CommandSender sender, String[] args) {
                core.command(args, msg -> sender.sendMessage(TextComponent.fromLegacyText(msg)));
            }
        });
    }

    @Override
    public void onDisable() {
        if (core != null) core.stop();
        core = null;
    }

    @Override public String id() { return "bungee"; }

    @Override
    public String displayName() {
        String[] v = getProxy().getGameVersion().split(",\\s*");                 // Waterfall lists every version
        return getProxy().getName() + " (" + (v.length > 1 ? v[0] + "-" + v[v.length - 1] : v[0]) + ")";   // "Waterfall (1.8.x-26.x)"
    }

    @Override public File dataDir() { return getDataFolder(); }

    /** Waterfall writes logs/latest.log; plain BungeeCord writes proxy.log.0. */
    @Override
    public File logFile() {
        File latest = new File("logs", "latest.log");
        return latest.isFile() ? latest : new File("proxy.log.0");
    }

    @Override public String addonsFolder() { return "plugins"; }
    @Override public boolean isProxy() { return true; }
    @Override public Log log() { return log; }

    /** BungeeCord's API is thread-safe: no main thread to hop to. */
    @Override public void runSync(Runnable r) { r.run(); }

    @Override
    public void dispatch(String command) {
        ProxyServer p = getProxy();
        p.getPluginManager().dispatchCommand(p.getConsole(), command);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void collect(Snapshot s) {
        ProxyServer p = getProxy();
        s.software = p.getVersion();
        s.online = p.getOnlineCount();
        int max = 0;
        for (ListenerInfo l : p.getConfigurationAdapter().getListeners()) max = Math.max(max, l.getMaxPlayers());
        s.max = max;
        for (ProxiedPlayer pl : p.getPlayers()) {
            Snapshot.Player x = new Snapshot.Player();
            x.name = pl.getName();
            x.uuid = pl.getUniqueId().toString();
            x.ping = pl.getPing();
            x.server = pl.getServer() == null ? "?" : pl.getServer().getInfo().getName();
            s.players.add(x);
        }
        for (ServerInfo si : p.getServers().values()) {
            Snapshot.World w = new Snapshot.World();
            w.name = si.getName();
            w.env = "server";
            w.players = si.getPlayers().size();
            s.worlds.add(w);
        }
    }

    @Override
    public void restart(int exitCode) {
        Core.haltOnExit(exitCode);
        getProxy().stop("Restarting (WavePanel)");
    }
}
