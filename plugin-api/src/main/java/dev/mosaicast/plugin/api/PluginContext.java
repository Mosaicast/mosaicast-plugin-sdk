// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
     *          → one JSON doc; 204 (no body) if the key is not set
     * GET    /api/plugins/{id}/data/{scopeType}?ids=a,b&keys=x,y
     *          → { a: { x: … }, b: {} } — misses absent; ≤ 100 ids and ≤ 100 keys (since 0.16.0)
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
     * this HTTP surface: a backend has no calling user, so it writes none, and reads them only through
     * {@link #allUsers()} — which the manifest has to declare.
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
     * exception: an exact key or a {@code *}-terminated prefix ({@link DocStore#KEY_SELECTOR_PATTERN})
     * whose documents this store still writes freely while a client {@code PUT}/{@code DELETE} is refused
     * with a 403 the host words differently from the role-floor one, so an author can tell which rule
     * turned them down. Reads are untouched. See {@link DocStore} for what it does not do — it neither
     * removes a value forged before the declaration nor applies to {@code USER} partitions.
     *
     * <p>{@code keyFloors} (since 0.19.0) raises the read or write floor for the keys it names — private
     * bookkeeping beside public numbers, an admin-only setting — with the same selector grammar; a listing
     * omits a key the reader may not see, and a single read or write of it is a 403 of its own type. Like
     * {@code backendOwned} it binds HTTP clients only: this store reads and writes every key. See
     * {@link DocStore} for the rules.
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
     * leaderboard built from {@link #allUsers()} holds {@link OwnedDocEntry} — UUIDs
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
     * Puts a message in a user's inbox, for plugins that declare a {@code notifications} block in their
     * manifest (ARCHITECTURE §17).
     *
     * <p>{@code null} without the declaration, exactly as with {@link #schema()}, {@link #blobs()},
     * {@link #tags()} and {@link #users()}. Declare it when your plugin finishes something a user took
     * part in and has no way to tell them — a bingo resolving, the case §17 was written for.
     *
     * <p>This is the <strong>one surface that writes into another user's experience</strong>; everything
     * else a plugin touches is its own scope or the current visitor's. So it is bounded twice over: the
     * host will only deliver to users this plugin already holds {@link ScopeType#USER}-scope data for, and
     * the rate limits are the host's rather than the plugin's. See {@link Notifier}.
     *
     * <p>Expect to call it from {@link #onSchedule(Duration, Runnable)} — the thing worth announcing
     * usually finishes on a timer, not in somebody's browser.
     *
     * <p><strong>Named {@code notifier()}, not {@code notify()}.</strong> ARCHITECTURE §7.4 writes the
     * latter, and it cannot exist: {@code Object.notify()} is {@code final}, so no Java interface can
     * declare that name. The type name is what the rest of this context uses anyway
     * ({@link #logger()}, {@link #translation()}, {@link #config()}), and it avoids reading like a getter
     * for a list of notifications — there is no read side (§17.1). The <em>TypeScript</em> half is
     * {@code ctx.notify}, as specified; the languages differ here because one of them has to.
     *
     * @return the notifier, or {@code null} when the manifest declares no {@code notifications} block
     *         (most plugins)
     * @since 0.14.0
     */
    Notifier notifier();

    /**
     * Every user's {@link ScopeType#USER} partition of this plugin, for plugins whose manifest declares
     * {@code "data": { "readsAllUsers": true }} (ARCHITECTURE §7.4).
     *
     * <p>{@code null} without the declaration, exactly as with {@link #blobs()}, {@link #tags()},
     * {@link #users()} and {@link #notifier()}. It is the one read that crosses an ownership boundary — every
     * account's documents, owner UUID included — so it is what an operator should be able to read off a
     * manifest before installing. Declare it when your backend aggregates over its users: a leaderboard, a
     * rollup, a moderation view. See {@link CrossUserStore}.
     *
     * <p>Replaces {@code DocStore.queryAcrossUsers(prefix)}, removed in 0.16.0: the call is now
     * {@code ctx.allUsers().query(prefix)}.
     *
     * @return the cross-user reader, or {@code null} when the manifest does not declare
     *         {@code data.readsAllUsers} (most plugins)
     * @since 0.16.0
     */
    CrossUserStore allUsers();

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
     * Registers a periodic background task at a <strong>fixed</strong> period.
     *
     * <p>The host wraps execution in <a href="https://github.com/lukas-krecan/ShedLock">ShedLock</a> so
     * the task runs at most once across all instances (ARCHITECTURE §5.4/§7.4).
     *
     * <p>The period is captured here and never re-read. If your tick rate comes from
     * {@link #config() config} — anything an operator can edit in the admin form — use
     * {@link #onSchedule(Supplier, Runnable)} instead: this overload keeps running at the value config
     * had during {@code register()}, and the saved setting silently does nothing until the host restarts.
     *
     * @param every how often the task should run; must be positive
     * @param task  the work to run on each tick; exceptions it throws are isolated by the host and must
     *              not take the site down
     * @throws NullPointerException if {@code every} or {@code task} is {@code null}
     */
    default void onSchedule(Duration every, Runnable task) {
        Objects.requireNonNull(every, "every");
        onSchedule(() -> every, task);
    }

    /**
     * Registers a periodic background task whose period the host <strong>re-reads before every tick</strong>
     * (ARCHITECTURE §7.4).
     *
     * <p>This exists because {@link #onSchedule(Duration, Runnable)} takes its period once, during
     * {@code register()}, and the host holds it for the life of the process. A plugin whose tick rate is
     * configurable — which the manifest actively invites — therefore accepted a new value, stored it,
     * reported success, and went on running at the old cadence. Nothing in the admin form said so.
     *
     * <p>So hand over the <em>reading</em> of the period rather than a value:
     *
     * <pre>{@code
     * ctx.onSchedule(
     *     () -> Duration.ofSeconds(ctx.config().getInt("ingestIntervalSeconds", 60)),
     *     this::ingest);
     * }</pre>
     *
     * <p><strong>What the host guarantees.</strong> It calls the supplier once at registration and again
     * before each fire; when the answer differs from the period currently in force it reschedules, so the
     * next tick lands on the new cadence. A change therefore takes effect within one old period — not at
     * the next restart. The host may clamp very short periods to a floor it owns; treat the supplied value
     * as a request, the same way the manifest's other numbers are.
     *
     * <p><strong>The supplier must be cheap and total.</strong> It runs on a scheduler thread before each
     * tick, so read config or a field — do not query, block, or do work with side effects in it. A supplier
     * that returns {@code null} or a non-positive {@link Duration}, or that throws, leaves the task running
     * at the last period that was valid; the host logs it and the task is never silently dropped. Only the
     * value at registration is strict: it must be positive, or the host rejects the registration.
     *
     * @param every supplies the current period, consulted before each tick; must return a positive
     *              {@link Duration}
     * @param task  the work to run on each tick; exceptions it throws are isolated by the host and must
     *              not take the site down
     * @throws NullPointerException     if {@code every} or {@code task} is {@code null}
     * @throws IllegalArgumentException if the period supplied at registration is not positive
     * @since 0.15.0
     */
    void onSchedule(Supplier<Duration> every, Runnable task);

    /**
     * Registers a listener the host calls when a planned episode is released — when it binds to its feed item
     * and moves from {@code PLANNED} to {@code PUBLISHED} (ARCHITECTURE §4.3, §5.3).
     *
     * <p>The listener receives the episode's public slug: the id {@link Scope#episode(String)} takes and
     * {@link FeedAccess#episodesIn(Scope)} returns. Use it to resolve what was prepared while the episode was
     * planned — a bingo opening for marks, a wiki page leaving draft.
     *
     * <p><strong>Best effort, and only a shortcut.</strong> The host calls it once per release, after the
     * transaction that bound the episode has committed, on a host thread; an exception the listener throws is
     * caught and logged against this plugin, and the host goes on to the next listener. The event is not durable and
     * not replayed: a plugin that was not running at that moment — stopped, restarting, being upgraded —
     * never hears about that release. So do not make it the only path. Reconcile on a schedule as well:
     *
     * <pre>{@code
     * ctx.onEpisodeReleased(this::open);
     * ctx.onSchedule(Duration.ofMinutes(15), () ->
     *     ctx.feeds().episodesIn(Scope.site()).stream()
     *         .filter(slug -> ctx.feeds().display(slug).phase() == EpisodePhase.RELEASED)
     *         .filter(this::stillClosed)
     *         .forEach(this::open));
     * }</pre>
     *
     * <p>The listener should therefore be idempotent: the event and the reconciliation may both handle one
     * release.
     *
     * <p><strong>Not for every new episode.</strong> An episode that arrives from the feed already released,
     * with no planned episode before it, never fires this. Neither does a planned episode becoming
     * {@link EpisodePhase#UPCOMING}: that is the clock passing its announcement instant, and nothing is written.
     *
     * <p>A {@code default} method that does nothing, so an existing {@code PluginContext} implementation — a
     * test double of your own — keeps compiling. The host overrides it. There is no frontend event: the shell
     * hands a component a new {@code ctx} when the phase changes.
     *
     * <p>A release is also a phase change, so {@link #onEpisodePhaseChanged(BiConsumer)} listeners hear it too,
     * after this one's.
     *
     * @param listener called with the released episode's slug; never {@code null}
     * @throws NullPointerException if {@code listener} is {@code null}
     * @since 0.18.0
     */
    default void onEpisodeReleased(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
    }

    /**
     * Registers a listener the host calls when a <strong>write</strong> changes an episode's
     * {@link EpisodePhase} (ARCHITECTURE §4.3).
     *
     * <p>{@link #onEpisodeReleased(Consumer)} covers one direction. This covers the one that leaks: an episode
     * becoming <em>less</em> visible. A podcaster moves an announced episode's {@code announceAt} into the
     * future and it is {@link EpisodePhase#PLANNED} again — hidden from everyone below podcaster at once, while
     * whatever your backend <em>published</em> on its schedule (a site-scope index, a count, a teaser) keeps
     * naming it to anonymous readers until the next tick. Anything you compute per request (a sitemap,
     * OpenGraph, {@link PageRouteProvider#hasRoute(String)}, search) is already right, because it asks
     * {@link FeedAccess#display(String)} each time; this hook is for what you stored.
     *
     * <pre>{@code
     * ctx.onEpisodePhaseChanged((slug, phase) -> {
     *     if (phase == null || phase == EpisodePhase.PLANNED || phase == EpisodePhase.WITHDRAWN) {
     *         republishIndex();          // drop it from what anonymous readers see, now
     *     }
     * });
     * }</pre>
     *
     * <p><strong>When it fires.</strong> The host compares the phase derived just before a write with the one
     * just after, at the same instant, and calls this only when they differ: announcing, an
     * {@code announceAt} edit in either direction, a release, a withdrawal, a withdrawn episode coming back,
     * and an episode ceasing to exist. The listener receives the slug and the new phase — or {@code null}
     * when the episode <strong>no longer exists</strong>: a cancelled plan, the duplicate a manual match or a
     * confirmed suggestion removed when a plan took over its feed item, or every episode of a deleted feed. Its
     * episode-scoped documents are gone with it, but anything your other scopes say about that slug is yours to
     * drop.
     *
     * <p><strong>The clock never fires it.</strong> A planned episode becoming {@link EpisodePhase#UPCOMING}
     * because its announcement instant passed involves no write, so there is no event. That direction only
     * makes an episode <em>more</em> visible, and being late there is harmless; reconcile it on your schedule.
     *
     * <p><strong>Delivery is the release hook's:</strong> once per change, after the transaction commits; an
     * exception the listener throws is caught and logged against this plugin. Best effort, not durable, not
     * replayed — keep reconciling by phase on a schedule, and keep the listener idempotent. On a release the
     * host calls {@link #onEpisodeReleased(Consumer)} listeners first, then this one with
     * {@link EpisodePhase#RELEASED}.
     *
     * <p><strong>Expect concurrent calls.</strong> Each event is its own task on its own thread, so a write
     * touching many episodes — deleting a feed is the large case — reaches your listener as many calls at once,
     * in no guaranteed order, and every one of them already sees every episode gone. A listener that
     * republishes something expensive should coalesce: let one pass run and the rest mark it dirty, rather than
     * recomputing per slug. Guard that with a {@link java.util.concurrent.locks.ReentrantLock}, not
     * {@code synchronized} — the host's threads are virtual, and on Java 21 a virtual thread waiting on a
     * monitor pins its carrier.
     *
     * <p>The phase handed over is the one right after the write. Read
     * {@link FeedAccess#display(String)} again if you act later: the clock may have moved it since.
     *
     * <p>A {@code default} method that does nothing, so an existing {@code PluginContext} implementation keeps
     * compiling. The host overrides it.
     *
     * @param listener called with the episode's slug and its new phase, which is {@code null} for an episode
     *                 that no longer exists (cancelled, removed as a matched duplicate, or in a deleted feed);
     *                 never {@code null} itself
     * @throws NullPointerException if {@code listener} is {@code null}
     * @since 0.19.0
     */
    default void onEpisodePhaseChanged(BiConsumer<String, EpisodePhase> listener) {
        Objects.requireNonNull(listener, "listener");
    }
}
