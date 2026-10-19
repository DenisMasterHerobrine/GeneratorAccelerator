package dev.denismasterherobrine.generatoraccelerator2;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;

/**
 * GA2's loggers, one per area, named {@code GA2/<area>}. NeoForge's log layout prints the logger name, so its lines carry the
 * area once. Vanilla's layout prints no logger name, so there the area goes at the head of the message
 * instead. Messages themselves never carry the area. Safe to use from the mixin plugin, since it uses no Minecraft types.
 */
public final class Ga2Log {
    private Ga2Log() {}

    private static final boolean LAYOUT_NAMES_LOGGER = present("net.neoforged.fml.loading.FMLLoader");

    public static Logger get(String area) {
        Logger logger = LoggerFactory.getLogger("GA2/" + area);
        return LAYOUT_NAMES_LOGGER ? logger : new Prefixed(logger, "[GA2/" + area + "] ");
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, Ga2Log.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** The logger with {@code prefix} put before every message. Levels are the delegate's. */
    private static final class Prefixed extends AbstractLogger {
        private final transient Logger delegate;
        private final String prefix;

        Prefixed(Logger delegate, String prefix) {
            this.delegate = delegate;
            this.prefix = prefix;
            this.name = delegate.getName();
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern, Object[] arguments, Throwable throwable) {
            String message = this.prefix + messagePattern;
            Object[] args = arguments == null ? new Object[0] : arguments;
            if (throwable != null) {
                args = Arrays.copyOf(args, args.length + 1);
                args[args.length - 1] = throwable;
            }
            switch (level) {
                case ERROR -> this.delegate.error(marker, message, args);
                case WARN -> this.delegate.warn(marker, message, args);
                case INFO -> this.delegate.info(marker, message, args);
                case DEBUG -> this.delegate.debug(marker, message, args);
                case TRACE -> this.delegate.trace(marker, message, args);
            }
        }

        @Override
        public boolean isTraceEnabled() {
            return this.delegate.isTraceEnabled();
        }

        @Override
        public boolean isTraceEnabled(Marker marker) {
            return this.delegate.isTraceEnabled(marker);
        }

        @Override
        public boolean isDebugEnabled() {
            return this.delegate.isDebugEnabled();
        }

        @Override
        public boolean isDebugEnabled(Marker marker) {
            return this.delegate.isDebugEnabled(marker);
        }

        @Override
        public boolean isInfoEnabled() {
            return this.delegate.isInfoEnabled();
        }

        @Override
        public boolean isInfoEnabled(Marker marker) {
            return this.delegate.isInfoEnabled(marker);
        }

        @Override
        public boolean isWarnEnabled() {
            return this.delegate.isWarnEnabled();
        }

        @Override
        public boolean isWarnEnabled(Marker marker) {
            return this.delegate.isWarnEnabled(marker);
        }

        @Override
        public boolean isErrorEnabled() {
            return this.delegate.isErrorEnabled();
        }

        @Override
        public boolean isErrorEnabled(Marker marker) {
            return this.delegate.isErrorEnabled(marker);
        }
    }
}
