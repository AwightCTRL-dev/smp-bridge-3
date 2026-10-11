package dev.smpbridge.common;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.function.Consumer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/** The only class that touches log4j-core. Loaded lazily so a missing log4j can't break the plugin. */
final class Log4jHook {
    private Log4jHook() {}

    static Object install(Consumer<String> sink) {
        org.apache.logging.log4j.core.Logger root = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
        AbstractAppender appender = new AbstractAppender("SMPBridgeConsole", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                try {
                    Level level = event.getLevel();
                    if (level.isMoreSpecificThan(Level.INFO)) { // INFO, WARN, ERROR, FATAL
                        sink.accept(format(event));
                    }
                } catch (Throwable ignored) {
                    // never throw into the logging system
                }
            }
        };
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    static void uninstall(Object handle) {
        AbstractAppender appender = (AbstractAppender) handle;
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender((Appender) appender);
        appender.stop();
    }

    private static String format(LogEvent e) {
        LocalTime t = Instant.ofEpochMilli(e.getTimeMillis()).atZone(ZoneId.systemDefault()).toLocalTime();
        String line = String.format("[%02d:%02d:%02d %s]: %s", t.getHour(), t.getMinute(), t.getSecond(),
            e.getLevel().name(), e.getMessage().getFormattedMessage());
        if (e.getThrown() != null) line += " " + e.getThrown();
        return line;
    }
}
