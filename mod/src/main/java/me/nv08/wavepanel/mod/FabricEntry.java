package me.nv08.wavepanel.mod;

import net.fabricmc.api.DedicatedServerModInitializer;

/** Fabric and Quilt (fabric.mod.json, "server" entry point): no Fabric API needed. */
public final class FabricEntry implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        boolean quilt = ModPlatform.hasClass("org.quiltmc.loader.api.QuiltLoader");
        ModPlatform.launch(quilt ? "quilt" : "fabric", quilt ? "Quilt" : "Fabric", ModPlatform::fabricServer);
    }
}
