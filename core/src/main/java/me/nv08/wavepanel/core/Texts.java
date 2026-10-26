package me.nv08.wavepanel.core;

/** Messages are written with Minecraft's § colour codes; places that can't show colour get them stripped. */
public final class Texts {
    private Texts() {}

    public static final String AQUA = "§b", WHITE = "§f", GREEN = "§a", RED = "§c", GRAY = "§7", YELLOW = "§e";

    public static String plain(String s) {
        return s == null ? "" : s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
}
