// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import org.pf4j.ExtensionPoint;

/**
 * The one entry point every plugin backend implements (ARCHITECTURE §7.4).
 *
 * <p>A plugin's backend is a plain <a href="https://pf4j.org/">PF4J</a> extension — no Spring, no
 * host code. It extends {@link ExtensionPoint} so the host can discover it via PF4J, and receives a
 * fully wired {@link PluginContext} exactly once at registration time.
 *
 * <p>Implementations do their setup in {@link #register(PluginContext)}: read config, seed the doc
 * store, register scheduled tasks. They must not reach outside the provided context.
 */
public interface PluginBackend extends ExtensionPoint {

    /**
     * Called once by the host during plugin startup, after the context is fully wired.
     *
     * @param ctx the host-provided context granting scoped access to storage, config, feeds and
     *            scheduling; never {@code null}
     */
    void register(PluginContext ctx);
}
