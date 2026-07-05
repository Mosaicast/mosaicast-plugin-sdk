// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.PluginConfig;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.SchemaStore;
import java.time.Duration;
import java.util.Objects;

/**
 * A fully in-memory {@link PluginContext} for testing a plugin backend without core or a database
 * (ARCHITECTURE §13.5).
 *
 * <p>Typical flow: build the fake context, call {@code plugin.register(ctx)}, then assert against the
 * doc store. {@link #onSchedule(Duration, Runnable)} runs the task <strong>synchronously</strong> and
 * immediately, so scheduled work is exercised deterministically within the test.
 *
 * <p>By default {@link #schema()} returns {@code null} (no schema declared, like most plugins); pass a
 * {@link SchemaStore} to the full constructor to test a schema-declaring plugin. Not thread-safe.
 */
public final class FakePluginContext implements PluginContext {

    private final InMemoryDocStore store;
    private final SchemaStore schema;
    private final PluginConfig config;
    private final FeedAccess feeds;
    private int scheduledCount;

    /** Creates a context with an empty doc store, empty config, empty feeds and no schema. */
    public FakePluginContext() {
        this(new InMemoryDocStore(), new MapPluginConfig(), new FakeFeedAccess(java.util.Map.of()), null);
    }

    /**
     * Creates a context wired to the given doubles.
     *
     * @param store  the doc store; never {@code null}
     * @param config the config; never {@code null}
     * @param feeds  the feed access; never {@code null}
     * @param schema the schema store, or {@code null} for a plugin that declares no schema
     */
    public FakePluginContext(InMemoryDocStore store, PluginConfig config, FeedAccess feeds, SchemaStore schema) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.feeds = Objects.requireNonNull(feeds, "feeds");
        this.schema = schema;
    }

    @Override
    public DocStore store() {
        return store;
    }

    @Override
    public SchemaStore schema() {
        return schema;
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public FeedAccess feeds() {
        return feeds;
    }

    /**
     * Runs the task synchronously and immediately (no real scheduling), so tests observe its effects
     * without waiting.
     *
     * @param every ignored beyond a positivity check; the task is run once, now
     * @param task  the task to run; never {@code null}
     */
    @Override
    public void onSchedule(Duration every, Runnable task) {
        Objects.requireNonNull(every, "every");
        Objects.requireNonNull(task, "task");
        if (every.isNegative() || every.isZero()) {
            throw new IllegalArgumentException("schedule interval must be positive: " + every);
        }
        scheduledCount++;
        task.run();
    }

    /**
     * The number of times {@link #onSchedule(Duration, Runnable)} has been called — handy for asserting
     * a plugin registered its scheduled work.
     *
     * @return the count of scheduled tasks
     */
    public int scheduledCount() {
        return scheduledCount;
    }
}
