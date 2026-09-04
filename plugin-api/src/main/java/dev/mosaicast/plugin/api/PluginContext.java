// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Duration;
import org.slf4j.Logger;

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
     * <em>host</em> exposes over this very store. That surface mirrors {@link DocStore} one-to-one — get,
     * put, list, delete, and no more:
     *
     * <pre>{@code
     * GET    /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}
     *          → one JSON doc; 404 if absent
     * GET    /api/plugins/{id}/data/{scopeType}/{scopeId}?prefix=&page=&size=
     *          → { items: [{ key, value }], page, size, totalElements, totalPages }
     * PUT    /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}   (JSON body)
     *          → upsert, last-write-wins
     * DELETE /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}
     *          → remove; idempotent
     * }</pre>
     *
     * <p>{@code scopeType} and {@code scopeId} mirror {@link ScopeType} and {@link Scope#id()}. Two of
     * them are singletons whose id is fixed: {@link ScopeType#SITE} is always {@link Scope#SITE_ID} — so
     * {@code store().put(Scope.site(), …)} and {@code …/data/site/main/{key}} address the same document —
     * and {@link ScopeType#USER} is always {@link Scope#SELF_ID}, i.e. {@code …/data/user/me/{key}}, which
     * the host resolves to the calling user. The path therefore always has four non-empty segments. Keys
     * must match {@link DocStore#KEY_PATTERN} and travel as the final path segment verbatim: no
     * {@code /}, so structure them with {@code :}/{@code .}/{@code -}, e.g. {@code mark:s2e04:cell}. The
     * list endpoint is paginated, and returns the same keyed {@link DocEntry} shape
     * {@link DocStore#query(Scope, String)} does.
     *
     * <p>So the document a backend writes with {@code ctx.store().put(scope, key, value)} is exactly the
     * one the frontend reads at {@code GET /api/plugins/{id}/data/{scopeType}/{scopeId}/{key}} — backend
     * and frontend see one store, not two. The exception is the {@code USER} scope, which exists only on
     * this HTTP surface: a backend has no calling user, so it reads user partitions through
     * {@link DocStore#queryAcrossUsers(String)} and writes none.
     *
     * <p>The host enforces the boundaries on that surface: data is hard-scoped to the plugin id (a plugin
     * only ever sees its own), the scope must exist (and its feed be enabled), and the call carries the
     * user's authentication (session or personal access token). Access is what the manifest declares —
     * <strong>not</strong> what the slots imply:
     *
     * <pre>{@code
     * "data": {
     *   "readableBy": "fan",
     *   "writableBy": "podcaster",
     *   "backendOwned": ["stats", "agg:*"]
     * }
     * }</pre>
     *
     * <p>Values are manifest role names: {@code anonymous | fan | podcaster | admin} — the {@link Role}
     * constants plus {@code anonymous}, which is the absence of one. {@code writableBy} may
     * not be {@code anonymous}, and an absent block defaults {@code readableBy} to the <em>write</em>
     * floor rather than to anonymous, so saying nothing gets the safe answer. A slot's {@code visibleTo}
     * governs <strong>rendering only</strong> — it never governed data, and inferring the data floor from
     * unrelated UI slots is what once let a plugin with one anonymous slot expose its whole store.
     *
     * <p><strong>The floors say who, not which key.</strong> Authorization on this surface is per plugin,
     * not per document, so <em>every</em> caller above {@code writableBy} can overwrite or delete
     * <em>any</em> shared-scope key — a value your backend computed included. {@code backendOwned} is the
     * exception: an exact key or a {@code *}-terminated prefix ({@link DocStore#BACKEND_OWNED_PATTERN})
     * whose documents this store still writes freely while a client {@code PUT}/{@code DELETE} is refused
     * with a 403 the host words differently from the role-floor one, so an author can tell which rule
     * turned them down. Reads are untouched. See {@link DocStore} for what it does not do — it neither
     * removes a value forged before the declaration nor applies to {@code USER} partitions.
     *
     * <p><strong>Neither floor applies to the {@code USER} scope.</strong> {@code readableBy} does not, in
     * either direction: no floor makes somebody else's partition readable, and none stands between a caller
     * and their own. {@code writableBy} does not either — a write floor protects the <em>shared</em>
     * surface, where one caller's write is visible to others and can overwrite theirs, and a user partition
     * is unshared by construction. Gating it would force a plugin with any per-user feature to declare
     * {@code writableBy: "fan"} and thereby open its shared scopes to fan writes, which is the old
     * slot-derived coupling moved to the write side. So any authenticated caller reads and writes their own
     * {@code data/user/me/…} whatever the manifest declares. Naming any {@code USER} id other than
     * {@code me} is a 400 (never a silent substitution), and an anonymous {@code USER} call is a 401 — with
     * no session there is no partition to resolve.
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
     * <p>Most plugins declare none and get {@code null} here — {@link #store()} is the default and covers
     * nearly everything. Declare a schema when you need what a JSON document cannot give you: full-text
     * search, revisions, backlinks. The platform provisions and drops the tables; you address declared
     * entities by name and never write DDL. See {@link SchemaStore} for the surface.
     *
     * @return the schema store, or {@code null} when the manifest declares no schema (most plugins)
     */
    SchemaStore schema();

    /**
     * File storage for plugins that declare a {@code blobs} block in their manifest (ARCHITECTURE §11).
     *
     * <p>Most plugins declare none and get {@code null} here, exactly as with {@link #schema()}. Declare one
     * when your plugin has to accept a file from the site's own people rather than link to somebody else's
     * host — a wiki's diagrams, a show-notes image. What you declare is what an installing operator sees you
     * asking for, and they may grant less. See {@link PluginBlobs} for the surface.
     *
     * @return the blob store, or {@code null} when the manifest declares no {@code blobs} block (most
     *         plugins)
     * @since 0.8.0
     */
    PluginBlobs blobs();

    /**
     * The site's shared tag vocabulary, for plugins that declare a {@code tags} block in their manifest
     * (ARCHITECTURE §6.1).
     *
     * <p>{@code null} without the declaration, exactly as with {@link #schema()} and {@link #blobs()}.
     * Declare it when your plugin has something to label and wants the site's own vocabulary rather than
     * a private column — the difference between a wiki's {@code lore} and an episode's {@code lore} being
     * one tag or two unrelated strings.
     *
     * <p>Reading and tagging your own subjects is one declaration; tagging <em>episodes</em> is a second,
     * because it changes the shell's filters and core's recommendations. See {@link Tags}.
     *
     * @return the tag surface, or {@code null} when the manifest declares no {@code tags} block (most
     *         plugins)
     * @since 0.9.0
     */
    Tags tags();

    /**
     * Who the user ids this plugin holds belong to, for plugins that declare an {@code identity} block in
     * their manifest (ARCHITECTURE §8.8).
     *
     * <p>{@code null} without the declaration, exactly as with {@link #schema()}, {@link #blobs()} and
     * {@link #tags()}. Declare it when your plugin aggregates across people and has to draw them: a
     * leaderboard built from {@link DocStore#queryAcrossUsers(String)} holds {@link OwnedDocEntry} — UUIDs
     * and documents — and without this has no way to turn a row into a person.
     *
     * <p>Declared rather than derived even though the plugin already <em>has</em> the ids, because the
     * capability is not access to the UUIDs but the turning of them into people, and that is what an
     * operator should be able to read off a manifest before installing.
     *
     * <p>It resolves, it does not enumerate — there is no list call. And what comes back is
     * {@link UserRef}: store the id, resolve at render, never persist the display name. See
     * {@link Users}.
     *
     * @return the user directory, or {@code null} when the manifest declares no {@code identity} block
     *         (most plugins)
     * @since 0.13.0
     */
    Users users();

    /**
     * Which languages this site has, and which content may be authored in (ARCHITECTURE §12.7).
     *
     * <p>Always present — a site always has at least English. Read it when you need it: an admin edits these
     * lists on a page, so a copy taken at {@code register()} time goes stale.
     *
     * <p>Anything stored per locale should be validated against {@link Locales#isContentLocale(String)} here,
     * on the backend. The list the browser used is a hint; what arrives at your storage is input.
     *
     * @return the language registry; never {@code null}
     * @since 0.10.0
     */
    Locales locales();

    /**
     * Machine translation, or {@code null} (ARCHITECTURE §16, §12.7).
     *
     * <p><strong>Two independent reasons for {@code null}, and a plugin must handle both:</strong>
     * <ol>
     *   <li><strong>Your manifest did not ask.</strong> Its {@code external.kinds} does not contain
     *       {@code "translation"}. Yours to fix, in your own {@code plugin.json}.</li>
     *   <li><strong>The operator configured no provider.</strong> Which is every site until an admin
     *       chooses one, and it can change back while your plugin is running.</li>
     * </ol>
     *
     * <p>They are deliberately <em>indistinguishable at runtime</em>: one {@code null}, no discriminator.
     * The first is a static fact about a file you wrote, so a plugin that wants to know can read its own
     * manifest, and a method answering it would be API surface for a question the author already has the
     * answer to. Staring at an unexpected {@code null}? Check the manifest before the admin panel.
     *
     * <p>{@code null} for the same reason {@link #schema()}, {@link #blobs()} and {@link #tags()} are: a
     * capability that may not exist should be one the caller is made to notice. Unlike those three, half
     * of this gate moves under a running plugin — so do not hold the handle across a scheduled run. Ask
     * again, and treat {@code null} as "this site does not do that" rather than as an error.
     *
     * <p><strong>{@code external.usedBy} does not reach here.</strong> That floor governs who may trigger a
     * call from the plugin's <em>browser</em> UI, and a backend has no visitor and no role: {@code register}
     * runs at startup and {@link #onSchedule(java.time.Duration, Runnable)} on a timer. On this side,
     * declaring the kind is the whole gate.
     *
     * @return the translation surface, or {@code null} when the manifest declares no {@code translation}
     *         kind or no provider is configured
     * @since 0.10.0
     */
    Translation translation();

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
     * The plugin's logger, already named by the host as {@code plugin.<pluginId>}.
     *
     * <p>An ordinary SLF4J {@link Logger}, deliberately: plugin authors already know the API, and
     * parameterised messages ({@code log.info("indexed {} pages", n)}) and throwable overloads come free.
     *
     * <p><strong>Attribution rides in the logger name</strong>, which is why the host hands you a named
     * logger instead of the contract offering a {@code log(level, message)} method. The name travels with
     * every event, so output is still attributed to your plugin when it comes from a thread you started
     * yourself or from an {@link #onSchedule(Duration, Runnable)} task — exactly the places where a
     * thread-local MDC arrives empty.
     *
     * <p>The host owns what happens next: it persists {@code INFO} and above, surfaces {@code WARN} and
     * above in the admin log viewer, and rate-limits this path. Log what an operator needs to diagnose
     * your plugin; a tight loop logging per item will be throttled, not stored.
     *
     * <p>Do not build your own {@code LoggerFactory.getLogger(...)}: a logger you name yourself is not
     * under the {@code plugin.} prefix, so the host cannot attribute it to you and it will not appear in
     * the admin viewer as your plugin's output.
     *
     * @return the plugin's logger; never {@code null}
     */
    Logger logger();

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
