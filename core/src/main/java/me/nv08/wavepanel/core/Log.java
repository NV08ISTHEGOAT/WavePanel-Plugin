package me.nv08.wavepanel.core;

import java.lang.reflect.Method;

/** The server's own logger, whichever kind it has (java.util.logging, SLF4J, Log4j) - or plain stdout. */
public interface Log {
    void info(String msg);

    void warn(String msg);

    void error(String msg);

    /** Bukkit / BungeeCord. */
    static Log of(final java.util.logging.Logger l) {
        return new Log() {
            public void info(String m) { l.info(Texts.plain(m)); }
            public void warn(String m) { l.warning(Texts.plain(m)); }
            public void error(String m) { l.severe(Texts.plain(m)); }
        };
    }

    /** Any logger with info/warn/error(String) methods (SLF4J on Velocity, Log4j on Sponge and the mod loaders). */
    static Log reflective(final Object logger) {
        try {
            final Method info = logger.getClass().getMethod("info", String.class);
            final Method warn = logger.getClass().getMethod("warn", String.class);
            final Method error = logger.getClass().getMethod("error", String.class);
            return new Log() {
                public void info(String m) { call(info, m); }
                public void warn(String m) { call(warn, m); }
                public void error(String m) { call(error, m); }

                private void call(Method x, String m) {
                    try {
                        x.invoke(logger, Texts.plain(m));
                    } catch (Exception e) {
                        System.out.println("[WavePanel] " + Texts.plain(m));
                    }
                }
            };
        } catch (Exception e) {
            return stdout();
        }
    }

    /** Log4j's "WavePanel" logger when the server has Log4j (every modern Minecraft server does), else stdout. */
    static Log log4j() {
        return log4j(Log.class.getClassLoader());
    }

    /** The same through another class loader (vanilla's bundler keeps Log4j in its own). */
    static Log log4j(ClassLoader loader) {
        try {
            Class<?> lm = Class.forName("org.apache.logging.log4j.LogManager", true, loader);
            return reflective(lm.getMethod("getLogger", String.class).invoke(null, "WavePanel"));
        } catch (Throwable t) {
            return stdout();
        }
    }

    static Log stdout() {
        return new Log() {
            public void info(String m) { System.out.println(tag("", m)); }
            public void warn(String m) { System.out.println(tag("WARN ", m)); }
            public void error(String m) { System.out.println(tag("ERROR ", m)); }

            private String tag(String level, String m) {
                String s = Texts.plain(m);
                return s.startsWith("[WavePanel]") ? (level.isEmpty() ? s : "[WavePanel] " + level + s.substring(12)) : "[WavePanel] " + level + s;
            }
        };
    }
}
