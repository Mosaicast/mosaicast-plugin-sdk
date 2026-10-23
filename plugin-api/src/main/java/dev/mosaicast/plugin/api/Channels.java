// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * Live events from the plugin's backend to subscribed browsers, for plugins whose manifest declares a
 * {@code channels} block (ARCHITECTURE §7.9). Reached through {@link PluginContext#channels()}.
 *
 * <pre>{@code
 * "channels": { "subscribableBy": "fan", "perUserSubscriptions": 4 }
 * }</pre>
 *
 * <p>{@code subscribableBy} defaults to {@code fan}; {@code anonymous} is refused at load.
 * {@code perUserSubscriptions} asks for concurrent subscriptions per user to this plugin, and the operator
 * caps it. How events travel is the host's business: nothing here, nor in the manifest, names a transport.
 *
 * <p>A subscribe passes the host's checks — signed in, {@code subscribableBy}, the scope visible to the
 * caller, the subscription cap — and then the plugin's {@link #authorize(ChannelAuthorizer) authorizer}.
 * Register one in {@code register(ctx)}; <strong>without one every subscribe is refused</strong>.
 *
 * <pre>{@code
 * Channels channels = ctx.channels();
 * channels.authorize((caller, ref) -> ref.name().equals("inbox")
 *         || (ref.name().startsWith("game:") && games.isPlayer(ref, caller.userId())));
 * channels.onLeave((ref, caller) -> games.markAway(ref, caller.userId()));
 *
 * channels.channel(scope, "game:42").publish("moved", view);            // everyone in the game
 * channels.channel(Scope.site(), "inbox").publishTo(bob, "invited", i); // one user, on a shared channel
 * }</pre>
 *
 * <p><strong>Subscriptions end when access does.</strong> Sign-out, account erasure, a role change, a site-access
 * change and the plugin being disabled close them; a refusal or {@link Channel#kick(java.util.UUID)} ends
 * them as refused. Either way {@link #onLeave(ChannelListener)} fires for the user's last one. A subscription
 * is bound to a browser session, so a personal access token cannot open one: nothing would end it on revoke.
 *
 * <p><strong>Concurrency.</strong> The authorizer and the join/leave listeners run concurrently with action
 * handlers and timers; the plugin synchronises its own state. See {@link Actions}.
 *
 * @since 0.20.0
 */
public interface Channels {

    /** The grammar of a channel name: {@value}. */
    String NAME_PATTERN = "^[A-Za-z0-9:_-]{1,128}$";

    /**
     * A handle on one channel.
     *
     * @param scope the scope; never {@code null}, never {@link ScopeType#USER}
     * @param name  the name, matching {@link #NAME_PATTERN}; never {@code null}
     * @return the channel; never {@code null}
     * @throws IllegalArgumentException as {@link ChannelRef}'s constructor
     */
    Channel channel(Scope scope, String name);

    /**
     * Sets the authorizer every subscribe passes after the host's checks, replacing any earlier one.
     *
     * @param authorizer the authorizer; never {@code null}
     */
    void authorize(ChannelAuthorizer authorizer);

    /**
     * Adds a listener for a user's first subscription to a channel — once per user, however many tabs.
     *
     * @param listener the listener; never {@code null}
     */
    void onJoin(ChannelListener listener);

    /**
     * Adds a listener for a user's last subscription to a channel ending, for whatever reason.
     *
     * @param listener the listener; never {@code null}
     */
    void onLeave(ChannelListener listener);
}
