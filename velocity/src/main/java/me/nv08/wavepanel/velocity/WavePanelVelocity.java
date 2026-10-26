package me.nv08.wavepanel.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.nv08.wavepanel.core.Core;
import me.nv08.wavepanel.core.Log;
import me.nv08.wavepanel.core.Platform;
import me.nv08.wavepanel.core.Snapshot;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.google.inject.Inject;
import java.io.File;
import java.nio.file.Path;

/**
 * WavePanel.jar on Velocity (3.x and 4.x, and forks like Velocity-CTD): the proxy's players (and which backend each
 * is on), its backend servers as the "worlds" list, its console and its commands. Velocity's API is thread-safe.
 */
public final class WavePanelVelocity implements Platform {
    private final ProxyServer proxy;
    private final Log log;
    private final Path dir;
    private Core core;

    @Inject
    public WavePanelVelocity(ProxyServer proxy, org.slf4j.Logger logger, @DataDirectory Path dir) {
        this.proxy = proxy;
        this.log = Log.reflective(logger);
        this.dir = dir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        core = Core.start(this);
        if (core == null) return;
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("wavepanel").build(), new SimpleCommand() {
            @Override
            public void execute(final Invocation inv) {
                core.command(inv.arguments(), msg -> inv.source().sendMessage(LegacyComponentSerializer.legacySection().deserialize(msg)));
            }

            @Override
            public boolean hasPermission(Invocation inv) {
                return inv.source().hasPermission("wavepanel.admin");
            }
        });
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (core != null) core.stop();
        core = null;
    }

    @Override public String id() { return "velocity"; }

    @Override
    public String displayName() {
        return proxy.getVersion().getName() + " " + proxy.getVersion().getVersion().replaceFirst("\\s.*$", "");
    }

    @Override public File dataDir() { return dir.toFile(); }
    @Override public File logFile() { return new File("logs", "latest.log"); }
    @Override public String addonsFolder() { return "plugins"; }
    @Override public boolean isProxy() { return true; }
    @Override public Log log() { return log; }
    @Override public void runSync(Runnable r) { r.run(); }

    @Override
    public void dispatch(String command) {
        proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command);
    }

    @Override
    public void collect(Snapshot s) {
        s.software = proxy.getVersion().getName() + " " + proxy.getVersion().getVersion();
        s.online = proxy.getPlayerCount();
        s.max = proxy.getConfiguration().getShowMaxPlayers();
        for (Player pl : proxy.getAllPlayers()) {
            Snapshot.Player x = new Snapshot.Player();
            x.name = pl.getUsername();
            x.uuid = pl.getUniqueId().toString();
            x.ping = (int) pl.getPing();
            x.server = pl.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("?");
            s.players.add(x);
        }
        for (RegisteredServer rs : proxy.getAllServers()) {
            Snapshot.World w = new Snapshot.World();
            w.name = rs.getServerInfo().getName();
            w.env = "server";
            w.players = rs.getPlayersConnected().size();
            s.worlds.add(w);
        }
    }

    @Override
    public void restart(int exitCode) {
        Core.haltOnExit(exitCode);
        proxy.shutdown();
    }
}
