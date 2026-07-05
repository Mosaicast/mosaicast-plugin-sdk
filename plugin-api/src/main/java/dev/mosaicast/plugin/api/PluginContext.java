// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Duration;

/**
 * The host-provided handle a plugin backend uses to reach everything it is allowed to touch
 * (ARCHITECTURE §7.4).
 *
 * <p>Everything a plugin may do on the backend goes through this context. The host owns the
 * implementation and enforces scoping: a plugin can never read or write outside the boundaries the
 * host draws.
 */
public interface PluginContext {

    /**
     * The generic, hard-scoped JSON document store — the default storage for plugins.
     *
     * @return the doc store; never {@code null}
     */
    DocStore store();

    /**
     * The relational store for plugins that declare a schema in their manifest (ARCHITECTURE §7.6).
     *
     * @return the schema store, or {@code null} when the manifest declares no schema (most plugins)
     */
    SchemaStore schema();

    /**
     * Read access to this plugin's declared configuration values (ARCHITECTURE §7.2).
     *
     * @return the config accessor; never {@code null}
     */
    PluginConfig config();

    /**
     * Host-resolved access to episodes and their display snapshots by scope (ARCHITECTURE §6.1).
     *
     * @return the feed access; never {@code null}
     */
    FeedAccess feeds();

    /**
     * Registers a periodic background task.
     *
     * <p>The host wraps execution in <a href="https://github.com/lukas-krecan/ShedLock">ShedLock</a> so
     * the task runs at most once across all instances (ARCHITECTURE §5.4/§7.4).
     *
     * @param every how often the task should run; must be positive
     * @param task  the work to run on each tick; exceptions it throws are isolated by the host and must
     *              not take the site down
     */
    void onSchedule(Duration every, Runnable task);
}
