package me.nv08.wavepanel.mod;

import me.nv08.wavepanel.core.Snapshot;
import me.nv08.wavepanel.core.Texts;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft's own server, reached by name - the same code on Fabric, Quilt, Forge, NeoForge and vanilla 26.x, 1.21.x
 * to 26.x. Every member has two names: Mojang's (what 26.x runs with everywhere, and what Forge / NeoForge run with) and
 * Fabric's intermediary name (what Fabric runs with up to 1.21.11). Both were looked up in the official mapping files;
 * whichever exists at runtime is used. A member that's missing on some version just leaves that number unknown.
 */
public final class Bridge {
    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public final Object server;
    private final ClassLoader loader;
    private final Map<String, Method> cache = new HashMap<>();

    public Bridge(Object server) {
        this.server = server;
        this.loader = server.getClass().getClassLoader();
    }

    // ------------------------------------------------------------------ the server

    public String mcVersion() { return str(call(server, "getServerVersion", "method_3827")); }

    /** "fabric", "forge", "neoforge", "vanilla"... - what the server calls itself. */
    public String modName() { return str(call(server, "getServerModName")); }

    public boolean dedicated() { return Boolean.TRUE.equals(call(server, "isDedicatedServer", "method_3816")); }

    public boolean running() { return !Boolean.FALSE.equals(call(server, "isRunning", "method_3806")); }

    /** Ticking: the levels are loaded and the server runs. */
    public boolean started() {
        return tickCount() > 0 && call(server, "getPlayerList", "method_3760") != null;
    }

    public int tickCount() {
        Object t = call(server, "getTickCount", "method_3780");
        return t instanceof Integer ? (Integer) t : 0;
    }

    /** Average tick time in ms, or null where the server can't tell (before 1.20.3). */
    public Double mspt() {
        Object n = call(server, "getAverageTickTimeNanos", "method_54834");
        return n instanceof Number ? ((Number) n).longValue() / 1_000_000.0 : null;
    }

    /** Runs r on the server thread (right away when already on it). */
    public void execute(Runnable r) {
        if (server instanceof Executor) {
            ((Executor) server).execute(r);
            return;
        }
        Method m = method(server.getClass(), new String[]{"execute"}, 1, Runnable.class);
        if (m == null) throw new IllegalStateException("this server has no task queue");
        invoke(m, server, r);
    }

    public void collect(Snapshot s) {
        Object list = call(server, "getPlayerList", "method_3760");
        Object online = call(server, "getPlayerCount", "method_3788");
        s.online = online instanceof Integer ? (Integer) online : 0;
        Object max = list == null ? null : call(list, "getMaxPlayers", "method_14592");
        if (!(max instanceof Integer)) max = call(server, "getMaxPlayers", "method_3802");
        s.max = max instanceof Integer ? (Integer) max : 0;

        Object players = list == null ? null : call(list, "getPlayers", "method_14571");
        if (players instanceof List) {
            for (Object p : new ArrayList<>((List<?>) players)) {
                Snapshot.Player x = new Snapshot.Player();
                Object prof = call(p, "getGameProfile", "method_7334");
                x.name = str(prof == null ? null : call(prof, "getName", "name"));
                Object uuid = call(p, "getUUID", "method_5667");
                x.uuid = uuid instanceof UUID ? uuid.toString() : "";
                x.x = (int) Math.floor(dbl(call(p, "getX", "method_23317")));
                x.y = (int) Math.floor(dbl(call(p, "getY", "method_23318")));
                x.z = (int) Math.floor(dbl(call(p, "getZ", "method_23321")));
                Object level = call(p, "level", "serverLevel", "method_51469", "method_37908", "method_73183");
                x.world = level == null ? "?" : dimension(level);
                x.health = dbl(call(p, "getHealth", "method_6032"));
                x.maxHealth = dbl(call(p, "getMaxHealth", "method_6063"));
                Object food = call(p, "getFoodData", "method_7344");
                Object fl = food == null ? null : call(food, "getFoodLevel", "method_7586");
                x.food = fl instanceof Integer ? (Integer) fl : -1;
                Object gm = field(p, "gameMode", "field_13974");
                Object type = gm == null ? null : call(gm, "getGameModeForPlayer", "method_14257");
                x.gamemode = type == null ? "" : type instanceof Enum ? ((Enum<?>) type).name().toLowerCase(Locale.ROOT) : str(type);
                Object conn = field(p, "connection", "field_13987");
                Object lat = conn == null ? null : call(conn, "latency", "method_52405");
                x.ping = lat instanceof Integer ? (Integer) lat : 0;
                s.players.add(x);
            }
        }

        Object levels = call(server, "getAllLevels", "method_3738");
        if (levels instanceof Iterable) {
            for (Object lv : (Iterable<?>) levels) {
                Snapshot.World w = new Snapshot.World();
                w.name = dimension(lv);
                w.env = w.name.endsWith("nether") ? "nether" : w.name.endsWith("the_end") ? "the_end" : "normal";
                Object ps = call(lv, "players", "method_18456");
                w.players = ps instanceof List ? ((List<?>) ps).size() : 0;
                Object chunks = call(lv, "getChunkSource", "method_14178");
                Object loaded = chunks == null ? null : call(chunks, "getLoadedChunksCount", "method_14151");
                w.chunks = loaded instanceof Integer ? (Integer) loaded : -1;
                Object all = call(lv, "getAllEntities", "method_27909");
                if (all instanceof Iterable) {
                    int n = 0;
                    for (Object ignored : (Iterable<?>) all) if (++n >= 100_000) break;
                    w.entities = n;
                }
                s.worlds.add(w);
            }
        }
    }

    /** "minecraft:overworld" - the last id in the level's ResourceKey ("ResourceKey[minecraft:dimension / minecraft:overworld]"). */
    private String dimension(Object level) {
        String k = String.valueOf(call(level, "dimension", "method_27983"));
        Matcher m = KEY.matcher(k);
        String last = null;
        while (m.find()) last = m.group();
        return last != null ? last : k;
    }

    // ------------------------------------------------------------------ commands

    /** A console command, as the server's console (the leading "/" is fine). Call on the server thread. */
    public void runCommand(String cmd) {
        Object commands = call(server, "getCommands", "method_3734");
        Object source = call(server, "createCommandSourceStack", "method_3739");
        if (commands == null || source == null) throw new IllegalStateException("this server's command system isn't reachable");
        Method m = method(commands.getClass(), new String[]{"performPrefixedCommand", "method_44252"}, 2);
        if (m == null) throw new IllegalStateException("performPrefixedCommand not found");
        invoke(m, commands, source, cmd);
    }

    public Object dispatcher() {
        Object commands = call(server, "getCommands", "method_3734");
        return commands == null ? null : call(commands, "getDispatcher", "method_9235");
    }

    /**
     * May this command source run /wavepanel? The console, RCON and command blocks (no entity) always; a player when
     * they have admin rights - checked the 26.x / 1.21.11 way (permission sets) or the older way (level 3+).
     */
    public boolean isAdmin(Object source) {
        if (call(source, "getEntity", "method_9228") == null) return true;
        try {
            Object set = call(source, "permissions", "method_75037");
            if (set != null) {
                Object admin = staticField(new String[]{"net.minecraft.server.permissions.Permissions", "net.minecraft.class_12099"},
                        "COMMANDS_ADMIN", "field_63211");
                if (admin != null) {
                    Method has = method(set.getClass(), new String[]{"hasPermission"}, 1);
                    if (has != null) return Boolean.TRUE.equals(invoke(has, set, admin));
                }
            }
        } catch (Throwable ignored) {
            // no permission sets on this version
        }
        Method lvl = method(source.getClass(), new String[]{"hasPermission", "method_9259"}, 1, int.class);
        return lvl != null && Boolean.TRUE.equals(invoke(lvl, source, 3));
    }

    /** A reply to whoever ran the command: colours for a player, plain text for the console's log. */
    public boolean tell(Object source, String msg) {
        try {
            Class<?> comp = firstClass("net.minecraft.network.chat.Component", "net.minecraft.class_2561");
            Method literal = comp == null ? null : staticMethod(comp, new String[]{"literal", "method_43470"}, String.class);
            Method send = method(source.getClass(), new String[]{"sendSystemMessage", "method_45068"}, 1);
            if (literal == null || send == null) return false;
            boolean player = call(source, "getEntity", "method_9228") != null;
            invoke(send, source, invoke(literal, null, player ? msg : Texts.plain(msg)));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ reflection

    private Object call(Object target, String... names) {
        if (target == null) return null;
        Method m = method(target.getClass(), names, 0);
        return m == null ? null : invoke(m, target);
    }

    /**
     * The first of {@code names} taking {@code params} parameters (of these exact types, when given): an interface's
     * own declaration where there is one - it works on lambda / hidden classes too and dispatches to the real one -
     * else the class's or a parent's.
     */
    private Method method(Class<?> cls, String[] names, int params, Class<?>... types) {
        String key = cls.getName() + "#" + String.join("|", names) + "/" + params + Arrays.toString(types);
        synchronized (cache) {
            if (cache.containsKey(key)) return cache.get(key);
        }
        Method found = null;
        for (String n : names) {
            found = inInterfaces(cls, n, params, types, new HashSet<Class<?>>());
            for (Class<?> c = cls; c != null && found == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (matches(m, n, params, types)) {
                        found = m;
                        break;
                    }
                }
            }
            if (found != null) break;
        }
        if (found != null) {
            try {
                found.setAccessible(true);
            } catch (Throwable ignored) {
                // public anyway
            }
        }
        synchronized (cache) {
            cache.put(key, found);
        }
        return found;
    }

    private static Method inInterfaces(Class<?> cls, String n, int params, Class<?>[] types, Set<Class<?>> seen) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (!seen.add(i)) continue;
                for (Method m : i.getDeclaredMethods()) if (matches(m, n, params, types)) return m;
                Method deeper = inInterfaces(i, n, params, types, seen);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }

    private static boolean matches(Method m, String n, int params, Class<?>[] types) {
        if (!m.getName().equals(n) || m.getParameterCount() != params || Modifier.isStatic(m.getModifiers())) return false;
        if (types == null || types.length == 0) return true;
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < types.length; i++) if (p[i] != types[i]) return false;
        return true;
    }

    private static Method staticMethod(Class<?> cls, String[] names, Class<?>... types) {
        for (String n : names) {
            try {
                Method m = cls.getMethod(n, types);
                if (Modifier.isStatic(m.getModifiers())) return m;
            } catch (NoSuchMethodException ignored) {
                // the other name
            }
        }
        return null;
    }

    private static Object field(Object target, String... names) {
        for (String n : names) {
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {
                    // up the chain
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    private Object staticField(String[] classes, String... names) {
        Class<?> c = firstClass(classes);
        if (c == null) return null;
        for (String n : names) {
            try {
                Field f = c.getField(n);
                return f.get(null);
            } catch (Throwable ignored) {
                // the other name
            }
        }
        return null;
    }

    /** By name through the server's own class loader first (vanilla's bundler loads the game apart from this jar). */
    private Class<?> firstClass(String... names) {
        ClassLoader[] loaders = {loader, Bridge.class.getClassLoader(), Thread.currentThread().getContextClassLoader()};
        for (String n : names) {
            for (ClassLoader l : loaders) {
                if (l == null) continue;
                try {
                    return Class.forName(n, false, l);
                } catch (Throwable ignored) {
                    // the next loader / name
                }
            }
        }
        return null;
    }

    private static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    private static double dbl(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : -1; }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
