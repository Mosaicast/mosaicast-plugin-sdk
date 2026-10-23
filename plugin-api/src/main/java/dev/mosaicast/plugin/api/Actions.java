// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * Request-time calls into the plugin's backend, for plugins whose manifest declares an {@code actions}
 * block (ARCHITECTURE §7.9). Reached through {@link PluginContext#actions()}.
 *
 * <p>An action is a <em>function</em> the host calls, not a route the plugin authors: the host owns the
 * URL, authentication, CSRF, the body cap, the rate limits and the error shape, and checks access before
 * any plugin code runs. The manifest names the closed set a client may call:
 *
 * <pre>{@code
 * "actions": { "callableBy": "fan",
 *              "names": { "join": {}, "move": {}, "start": { "callableBy": "podcaster" } },
 *              "perUserPerMinute": 120, "perPluginPerMinute": 2000 }
 * }</pre>
 *
 * <p>A name's own {@code callableBy} can only raise the block's; {@code anonymous} is refused at load. The
 * two numbers are requests the operator caps. Register each name in {@code register(ctx)}:
 *
 * <pre>{@code
 * ctx.actions().handle("move", call -> {
 *     Move move = call.payload(Move.class);
 *     synchronized (games) {
 *         Game game = games.load(call.scope());
 *         if (!game.turn().equals(call.caller().userId())) {
 *             throw new ActionRejectedException("not-your-turn");
 *         }
 *         game.apply(move);
 *         games.save(call.scope(), game);           // the store is the truth; memory is a cache
 *         ctx.channels().channel(call.scope(), "game").publish("moved", game.publicView());
 *         return game.publicView();
 *     }
 * });
 * }</pre>
 *
 * <p><strong>What a caller gets back</strong>, in the order the host checks:
 * <ul>
 *   <li>404 {@code not-found} — the plugin declares no {@code actions}; 404 {@code unknown-action} — the
 *       name is not declared;</li>
 *   <li>401 {@code unauthorized} — not signed in; 403 {@code forbidden} — below the name's
 *       {@code callableBy};</li>
 *   <li>413 {@code payload-too-large} — the body exceeds {@link #MAX_BODY_BYTES} (checked before the scope,
 *       which travels inside the body);</li>
 *   <li>404 {@code not-found} — the scope does not exist or is not visible to the caller;</li>
 *   <li>429 {@code too-many-requests} with {@code Retry-After} and {@code retryAfterMs} — over a budget;</li>
 *   <li>503 {@code action-unavailable} — declared but no handler registered: the author's wiring bug;</li>
 *   <li>then the handler: its value as JSON, {@code null} as 204, {@link ActionRejectedException} as 422
 *       {@code action-rejected} with {@code code}, a payload {@link ActionCall#payload(Class)} could not read
 *       as 400 {@code bad-request}, anything else thrown as 500 (logged to the plugin's log);</li>
 *   <li>503 {@code action-busy} with {@code retryAfterMs} — the plugin's queue is full; 504
 *       {@code action-timeout} — the handler overran its deadline (it keeps running; the caller is
 *       answered).</li>
 * </ul>
 * Every type is {@code https://mosaicast.dev/problems/<slug>}.
 *
 * <p>Both size caps are measured on the UTF-8 JSON: the whole {@code { scope, payload }} body here, the
 * serialized {@code data} for a {@link Channel channel event}.
 *
 * <p><strong>Concurrency.</strong> Handlers run on a bounded executor of the plugin's own, with a deadline —
 * never on the request thread. They run concurrently with each other, with
 * {@link PluginContext#runAfter(java.time.Duration, Runnable) timers} and with {@link Channels} callbacks;
 * <strong>the plugin synchronises its own state</strong>. The host promises no ordering between two calls
 * beyond {@link ActionCall#receivedAt()}.
 *
 * <p><strong>Memory is a cache, the store is the truth.</strong> A handler may keep state across calls, but
 * anything that must survive a restart goes in the store — a shared scope under {@code data.backendPrivate}
 * is the intended home. The contract promises nothing about which instance runs a call.
 *
 * @since 0.20.0
 */
public interface Actions {

    /**
     * The grammar of an action name, also used for {@link Channel channel event} names and
     * {@link ActionRejectedException rejection codes}: {@value}.
     */
    String NAME_PATTERN = "^[a-z0-9][a-z0-9-]{0,63}$";

    /** The largest request body the host accepts for an action, in bytes: {@value}. */
    int MAX_BODY_BYTES = 16 * 1024;

    /**
     * Registers the handler for a declared action name, replacing any earlier one for that name.
     *
     * @param name    a name the manifest's {@code actions.names} declares; never {@code null}
     * @param handler the handler; never {@code null}
     * @throws IllegalArgumentException if the manifest does not declare {@code name} — a typo found at
     *                                  {@code register()}, not at the first call
     * @throws NullPointerException     if either argument is {@code null}
     */
    void handle(String name, ActionHandler handler);
}
