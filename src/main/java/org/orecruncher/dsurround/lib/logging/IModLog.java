package org.orecruncher.dsurround.lib.logging;

import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public interface IModLog {

    boolean isDebugging();

    boolean isTracing(int mask);

    default void info(final String msg, @Nullable final Object... parms) {
    }

    default void info(final Supplier<String> message) {
        info(message.get());
    }

    default void warn(final String msg, @Nullable final Object... parms) {
    }

    default void warn(final Supplier<String> message) {
        warn(message.get());
    }

    default void debug(final String msg, @Nullable final Object... parms) {
    }

    default void debug(final Supplier<String> message) {
        debug(message.get());
    }

    default void debug(final int mask, final String msg, @Nullable final Object... parms) {
    }

    default void debug(final int mask, final Supplier<String> message) {
        if (this.isTracing(mask))
            debug(message.get());
    }

    /** Debug with a throwable stack; implementations print it only when debugging is on. */
    default void debug(final Throwable e, final String msg, @Nullable final Object... parms) {
    }

    default void debug(final Throwable e, final Supplier<String> message) {
        debug(e, message.get());
    }

    default void error(final Throwable e, final String msg, @Nullable final Object... parms) {
    }

    default void error(final Throwable e, final Supplier<String> message) {
        error(e, message.get());
    }
}