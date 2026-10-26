package net.fabricmc.api;

/** Compile-time stand-in for Fabric Loader's interface (the real one is there at runtime; this is never shipped). */
@FunctionalInterface
public interface DedicatedServerModInitializer {
    void onInitializeServer();
}
