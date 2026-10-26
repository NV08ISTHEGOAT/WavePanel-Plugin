package me.nv08.wavepanel.mod;

import net.neoforged.fml.common.Mod;

/** NeoForge (META-INF/neoforge.mods.toml). */
@Mod("wavepanel")
public final class NeoForgeEntry {
    public NeoForgeEntry() {
        ModPlatform.launch("neoforge", "NeoForge", () -> ModPlatform.staticCall("net.neoforged.neoforge.server.ServerLifecycleHooks", "getCurrentServer"));
    }
}
