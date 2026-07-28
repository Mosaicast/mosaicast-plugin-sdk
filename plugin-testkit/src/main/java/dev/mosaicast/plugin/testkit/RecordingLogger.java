// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * An SLF4J {@link org.slf4j.Logger} that records what a plugin logs instead of printing it
 * (ARCHITECTURE §13.5).
 *
 * <p>This is what {@link FakePluginContext#logger()} hands out, so a test can assert on a plugin's
 * logging the same way it asserts on its stored documents:
 *
 * <pre>{@code
 * FakePluginContext ctx = new FakePluginContext();
 * plugin.register(ctx);
 *
 * assertEquals(1, ctx.logger().events(Level.WARN).size());
 * assertEquals("feed 42 had no items", ctx.logger().events(Level.WARN).get(0).message());
 * }</pre>
 *
 * <p>Every level is enabled, so nothing a plugin logs is silently dropped in a test. Messages are
 * recorded <strong>formatted</strong> — {@code log.warn("no items in {}", feedId)} lands as
 * {@code "no items in 42"} — because that is what an operator would read. A throwable passed as the
 * trailing argument is recorded separately in {@link LogEvent#error()}, as SLF4J itself treats it.
 *
 * <p>Not thread-safe — test doubles run single-threaded.
 */
public final class RecordingLogger extends AbstractLogger {

    private static final long serialVersionUID = 1L;

    /**
     * One recorded logging call.
     *
     * @param level   the level it was logged at; never {@code null}
     * @param message the message with its {@code {}} placeholders already substituted; never {@code null}
     * @param error   the throwable passed to the call, or {@code null} if there was none
     */
    public record LogEvent(Level level, String message, Throwable error) {
    }

    private final transient List<LogEvent> events = new ArrayList<>();

    /**
     * Creates a logger named as the host names a plugin's logger.
     *
     * @param name the logger name, e.g. {@code plugin.bingo}; never {@code null}
     */
    public RecordingLogger(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Every recorded call, in the order it was made.
     *
     * @return the recorded events; never {@code null}, and a live view is not exposed — the list is
     *         copied, so asserting on it is stable
     */
    public List<LogEvent> events() {
        return List.copyOf(events);
    }

    /**
     * The recorded calls at one level.
     *
     * @param level the level to filter by; never {@code null}
     * @return the matching events, in order; never {@code null}, empty when nothing was logged at that
     *         level
     */
    public List<LogEvent> events(Level level) {
        Objects.requireNonNull(level, "level");
        return events.stream().filter(e -> e.level() == level).toList();
    }

    /** Discards everything recorded so far, e.g. between phases of a longer test. */
    public void clear() {
        events.clear();
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        // No caller-location inference: nothing here walks the stack.
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(
            Level level, Marker marker, String messagePattern, Object[] arguments, Throwable throwable) {
        events.add(new LogEvent(level, MessageFormatter.basicArrayFormat(messagePattern, arguments), throwable));
    }

    /** {@return always {@code true} — a recording logger drops nothing} */
    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isTraceEnabled(Marker marker) {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isDebugEnabled(Marker marker) {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isInfoEnabled(Marker marker) {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isWarnEnabled(Marker marker) {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    /** {@return always {@code true}} */
    @Override
    public boolean isErrorEnabled(Marker marker) {
        return true;
    }
}
