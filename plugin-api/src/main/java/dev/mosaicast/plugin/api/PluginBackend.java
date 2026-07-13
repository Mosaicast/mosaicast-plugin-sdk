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
 *
 * <p><strong>A plugin's server side is {@code register(ctx)} — nothing else.</strong> There is no
 * route-registration or HTTP-handler API in this contract, and that is deliberate: a v1 plugin does
 * <em>not</em> declare its own HTTP endpoints. All persistence goes through {@link PluginContext#store()}
 * (the {@link DocStore}), and anything derived, aggregated or validated server-side is precomputed here
 * or in a {@link PluginContext#onSchedule(java.time.Duration, Runnable) scheduled task} and then read
 * back from the store. The frontend reaches that same data through the host's fixed, generic data
 * endpoints — see {@link PluginContext#store()} for the full picture.
 *
 * <p>Custom plugin-defined server routes may arrive in a later {@code platformApi} version; v1 plugins
 * use the doc store.
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
