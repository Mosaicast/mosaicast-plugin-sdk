// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.PluginBackend;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/** Self-tests for the logging half of the contract (ARCHITECTURE §13.5). */
class RecordingLoggerTest {

    @Test
    void contextExposesALoggerNamedLikeTheHostNamesIt() {
        FakePluginContext ctx = new FakePluginContext();

        // The host hands out `plugin.<pluginId>`; attribution rides in the name, so the fake keeps the
        // shape even though it stands in for no concrete plugin.
        assertTrue(ctx.logger().getName().startsWith("plugin."));
        assertTrue(ctx.logger().events().isEmpty());
    }

    @Test
    void recordsEveryLevelInOrder() {
        RecordingLogger log = new RecordingLogger("plugin.bingo");

        log.trace("t");
        log.debug("d");
        log.info("i");
        log.warn("w");
        log.error("e");

        assertEquals(
                List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR),
                log.events().stream().map(RecordingLogger.LogEvent::level).toList());
        assertEquals("w", log.events(Level.WARN).get(0).message());
        assertEquals(1, log.events(Level.ERROR).size());
    }

    @Test
    void formatsParameterisedMessages() {
        RecordingLogger log = new RecordingLogger("plugin.bingo");

        log.info("indexed {} pages of {}", 12, "wiki");

        // Recorded as an operator would read it, not as the raw pattern plus arguments.
        assertEquals("indexed 12 pages of wiki", log.events().get(0).message());
        assertNull(log.events().get(0).error());
    }

    @Test
    void capturesTheThrowableSeparately() {
        RecordingLogger log = new RecordingLogger("plugin.bingo");
        RuntimeException boom = new IllegalStateException("boom");

        log.error("could not reach {}", "plausible.example", boom);

        RecordingLogger.LogEvent event = log.events(Level.ERROR).get(0);
        // SLF4J peels a trailing throwable off the arguments; the message keeps its placeholders filled.
        assertEquals("could not reach plausible.example", event.message());
        assertSame(boom, event.error());
    }

    @Test
    void everyLevelIsEnabledSoNothingIsSilentlyDropped() {
        RecordingLogger log = new RecordingLogger("plugin.bingo");

        assertTrue(log.isTraceEnabled());
        assertTrue(log.isDebugEnabled());
        assertTrue(log.isInfoEnabled());
        assertTrue(log.isWarnEnabled());
        assertTrue(log.isErrorEnabled());
    }

    @Test
    void clearDiscardsWhatWasRecorded() {
        RecordingLogger log = new RecordingLogger("plugin.bingo");
        log.info("before");

        log.clear();
        log.info("after");

        assertEquals(1, log.events().size());
        assertEquals("after", log.events().get(0).message());
    }

    @Test
    void aPluginsLoggingIsObservableFromRegisterAndScheduledWork() {
        FakePluginContext ctx = new FakePluginContext();
        PluginBackend plugin = c -> {
            c.logger().info("registered");
            // onSchedule runs synchronously in the fake, and the logger name carries the attribution —
            // which is the whole reason the contract hands out a named logger rather than log(level, msg).
            c.onSchedule(Duration.ofMinutes(5), () -> c.logger().warn("nothing to aggregate"));
        };

        plugin.register(ctx);

        assertEquals(List.of("registered"), ctx.logger().events(Level.INFO).stream()
                .map(RecordingLogger.LogEvent::message)
                .toList());
        assertEquals(List.of("nothing to aggregate"), ctx.logger().events(Level.WARN).stream()
                .map(RecordingLogger.LogEvent::message)
                .toList());
    }
}
