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
 *
 * <p><strong>This context is the whole server-side surface.</strong> Note what is not on it: there is no
 * way to register an HTTP route or handler. v1 plugins do not define endpoints — they persist through
 * {@link #store()} and precompute through {@link #onSchedule(Duration, Runnable)}. See
 * {@link PluginBackend} and {@link #store()}.
 */
public interface PluginContext {

    /**
     * The generic, hard-scoped JSON document store — the default storage for plugins, and the
     * <strong>sole persistence path</strong> of a v1 plugin (barring an explicitly declared
     * {@link #schema() schema}).
     *
     * <p><strong>How the frontend reaches this data.</strong> A plugin's Web Component does not call
     * plugin-authored routes — none exist. It calls {@code ctx.api} (the TypeScript
     * {@code PluginApiClient}), which targets a fixed, generic, per-plugin-namespaced HTTP surface the
     * <em>host</em> exposes over this very store. That surface mirrors this interface exactly — get, put,
     * list, nothing more:
     *
     * <pre>{@code
     * GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}
     *       → one JSON doc; 404 if absent
     * GET /api/plugins/{id}/data/{scopeType}/{scopeId}?prefix=&page=&size=
     *       → { items: [{ key, value }], page, size, totalElements, totalPages }
     * PUT /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}   (JSON body)
     *       → upsert, last-write-wins
     * }</pre>
     *
     * <p>{@code scopeType} and {@code scopeId} mirror {@link ScopeType} and {@link Scope#id()}. For
     * {@link ScopeType#SITE} the host normalizes to a single literal id, {@code main} — there is only one
     * site, so {@code store().put(Scope.site(anything), …)} and {@code …/data/site/main/{key}} address the
     * same row, and the path always has four non-empty segments. Keys must match
     * {@code ^[A-Za-z0-9._:-]{1,200}$} (the host answers 400 otherwise) and travel as the final path
     * segment verbatim: no {@code /}, so structure them with {@code :}/{@code .}/{@code -}, e.g.
     * {@code mark:userId:cell}. The list endpoint is paginated and — unlike
     * {@link DocStore#query(Scope, String)}, which returns values only — carries each document's key,
     * because the frontend cannot address a document without it.
     *
     * <p>So the document a backend writes with {@code ctx.store().put(scope, key, value)} is exactly the
     * one the frontend reads at {@code GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}} — backend
     * and frontend see one store, not two.
     *
     * <p><strong>No delete in v1:</strong> this interface has none, and the HTTP surface stays symmetric
     * with it rather than granting the frontend a capability the backend lacks. Removal arrives with
     * {@code DocStore.delete(scope, key)} in 0.3.0, with the {@code DELETE} endpoint alongside it; do not
     * model deletion as a tombstone value in the meantime.
     *
     * <p>The host enforces the boundaries on that surface: data is hard-scoped to the plugin id (a plugin
     * only ever sees its own), reads are gated by the slot's {@code visibleTo} and writes by the mapped
     * {@link Role}, the scope must exist (and its feed be enabled), and the call carries the user's
     * authentication (session or personal access token).
     *
     * <p><strong>No request-time server logic in v1:</strong> a write through that surface is plain
     * persistence — no plugin code runs on the request. Derive, validate or aggregate in
     * {@link PluginBackend#register(PluginContext)} or {@link #onSchedule(Duration, Runnable)}, store the
     * result, and let the frontend read it back.
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
