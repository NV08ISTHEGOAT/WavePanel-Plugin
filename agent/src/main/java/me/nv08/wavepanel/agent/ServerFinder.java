package me.nv08.wavepanel.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Vanilla keeps no static handle on its server: MinecraftServer.spin() starts the "Server thread" with a lambda that
 * holds an AtomicReference to it. That Runnable sits in a private field of java.lang.Thread, which the agent may open
 * for itself (Instrumentation.redefineModule) - nothing else in java.base is touched.
 */
final class ServerFinder {
    private static boolean opened;

    private ServerFinder() {}

    static Object find(Instrumentation inst) {
        open(inst);
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!"Server thread".equals(t.getName())) continue;
            Object server = search(task(t), 0);
            if (server != null) return server;
        }
        return null;
    }

    /** Java 9+: let this jar read Thread's fields (Java 8 has no modules - setAccessible just works). */
    private static synchronized void open(Instrumentation inst) {
        if (opened) return;
        opened = true;
        try {
            Class<?> module = Class.forName("java.lang.Module");
            Method getModule = Class.class.getMethod("getModule");
            Object javaBase = getModule.invoke(Thread.class);
            Object me = getModule.invoke(ServerFinder.class);
            Method redefine = Instrumentation.class.getMethod("redefineModule", module, Set.class, Map.class, Map.class, Set.class, Map.class);
            redefine.invoke(inst, javaBase, Collections.emptySet(), Collections.emptyMap(),
                    Collections.singletonMap("java.lang", Collections.singleton(me)), Collections.emptySet(), Collections.emptyMap());
        } catch (ClassNotFoundException java8) {
            // nothing to open
        } catch (Throwable ignored) {
            // the basic version then
        }
    }

    /** The thread's Runnable: Thread.target (Java 8-18) or Thread.holder.task (19+). */
    private static Object task(Thread t) {
        Object r = get(t, Thread.class, "target");
        if (r == null) {
            Object holder = get(t, Thread.class, "holder");
            if (holder != null) r = get(holder, holder.getClass(), "task");
        }
        return r;
    }

    private static Object search(Object o, int depth) {
        if (o == null || depth > 3) return null;
        if (isServer(o.getClass())) return o;
        if (o instanceof AtomicReference) return search(((AtomicReference<?>) o).get(), depth + 1);
        if (depth > 1) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                Object found = search(get(o, c, f.getName()), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isServer(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals("net.minecraft.server.MinecraftServer")) return true;
        return false;
    }

    private static Object get(Object o, Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return null;
        }
    }
}
