package me.nv08.wavepanel.mod;

import net.minecraftforge.fml.common.Mod;

/** Forge (META-INF/mods.toml). Also what SpongeForge and the Forge-based hybrids load. */
@Mod("wavepanel")
public final class ForgeEntry {
    public ForgeEntry() {
        ModPlatform.launch("forge", "Forge", () -> ModPlatform.staticCall("net.minecraftforge.server.ServerLifecycleHooks", "getCurrentServer"));
    }
}
