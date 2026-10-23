// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.Actions;
import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.Channel;
import dev.mosaicast.plugin.api.ChannelAuthorizer;
import dev.mosaicast.plugin.api.ChannelListener;
import dev.mosaicast.plugin.api.ChannelRef;
import dev.mosaicast.plugin.api.Channels;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;

/**
 * An in-memory {@link Channels}: a test {@link #subscribe(Caller, ChannelRef) subscribes} as any
 * {@link Caller} — the plugin's authorizer runs — and reads what each subscription received, or everything a
 * channel {@link #published(ChannelRef) published} (ARCHITECTURE §7.9, §13.5).
 *
 * <p>Obtained from {@link FakePluginContext#withChannels()}, which stands in for the manifest's
 * {@code channels} block.
 *
 * <pre>{@code
 * FakePluginContext ctx = new FakePluginContext().withActions("move").withChannels();
 * plugin.register(ctx);
 *
 * ChannelRef game = new ChannelRef(Scope.episode("s2e04"), "game:1");
 * FakeSubscription alice = ctx.channels().subscribe(Callers.fan(aliceId), game);
 * assertThrows(SubscriptionRefusedException.class, () -> ctx.channels().subscribe(Callers.fan(), game));
 *
 * ctx.actions().call(Callers.fan(aliceId), "move", game.scope(), Map.of("cell", 3));
 * assertEquals("moved", alice.events().getLast().event());
 * }</pre>
 *
 * <p><strong>Synchronous by design</strong>: a publish is delivered before it returns, and join/leave
 * listeners run on the test's thread. A listener that throws is logged to the context's logger and the
 * others still run, as on the host. There is no replay, reset or transport here — those are the shell's.
 *
 * @since 0.20.0
 */
public final class FakeChannels implements Channels {

    private final ObjectMapper mapper;
    private final Supplier<Instant> clock;
    private final Logger logger;
    private final List<ChannelListener> joinListeners = new ArrayList<>();
    private final List<ChannelListener> leaveListeners = new ArrayList<>();
    // Open subscriptions per channel, in subscribe order.
    private final Map<ChannelRef, List<FakeSubscription>> open = new LinkedHashMap<>();
    // Everything published per channel, delivered or not.
    private final Map<ChannelRef, List<ChannelEvent>> published = new LinkedHashMap<>();
    private ChannelAuthorizer authorizer;
    private Role subscribableBy = Role.FAN;

    FakeChannels(ObjectMapper mapper, Supplier<Instant> clock, Logger logger) {
        this.mapper = mapper;
        this.clock = clock;
        this.logger = logger;
    }

    /**
     * Sets {@code channels.subscribableBy}. The default is {@link Role#FAN}, as the host's is.
     *
     * @param role the lowest role that may subscribe; never {@code null}
     * @return this instance, for chaining
     */
    public FakeChannels withSubscribableBy(Role role) {
        this.subscribableBy = Objects.requireNonNull(role, "role");
        return this;
    }

    @Override
    public Channel channel(Scope scope, String name) {
        return new FakeChannel(new ChannelRef(scope, name));
    }

    @Override
    public void authorize(ChannelAuthorizer authorizer) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
    }

    @Override
    public void onJoin(ChannelListener listener) {
        joinListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void onLeave(ChannelListener listener) {
        leaveListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Subscribes as {@code caller} would from a browser: the floor, then the plugin's authorizer, then
     * {@code onJoin} if this is the caller's first subscription to the channel.
     *
     * <p>The host's other checks — signed in, scope visible, the subscription cap — have nothing to refuse here:
     * a {@link Caller} is signed in by construction, and the fake knows no scope it could hide.
     *
     * @param caller  who subscribes; never {@code null}
     * @param channel the channel; never {@code null}
     * @return the open subscription, collecting what it receives
     * @throws SubscriptionRefusedException where the browser would see {@code refused}
     */
    public FakeSubscription subscribe(Caller caller, ChannelRef channel) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(channel, "channel");
        if (Callers.rank(caller.role()) < Callers.rank(subscribableBy)) {
            throw new SubscriptionRefusedException(SubscriptionRefusedException.Reason.BELOW_FLOOR,
                    "a " + caller.role() + " cannot subscribe: subscribableBy is " + subscribableBy);
        }
        if (authorizer == null) {
            throw new SubscriptionRefusedException(SubscriptionRefusedException.Reason.NO_AUTHORIZER,
                    "the plugin registered no authorizer, so the host refuses every subscribe");
        }
        if (!authorizer.allows(caller, channel)) {
            throw new SubscriptionRefusedException(SubscriptionRefusedException.Reason.AUTHORIZER,
                    "the plugin's authorizer refused " + caller.userId() + " on " + channel);
        }
        boolean first = !subscribers(channel).contains(caller.userId());
        FakeSubscription subscription = new FakeSubscription(this, caller, channel);
        open.computeIfAbsent(channel, c -> new ArrayList<>()).add(subscription);
        if (first) {
            fire(joinListeners, channel, caller, "join");
        }
        return subscription;
    }

    /**
     * Everything published on a channel so far, to everyone or to one user, whether or not anyone was
     * subscribed — the host drops an event nobody receives, but a test may still want to see it was sent.
     *
     * @param channel the channel; never {@code null}
     * @return the events, oldest first; never {@code null}
     */
    public List<ChannelEvent> published(ChannelRef channel) {
        Objects.requireNonNull(channel, "channel");
        return List.copyOf(published.getOrDefault(channel, List.of()));
    }

    /**
     * Ends every subscription a user holds, on every channel, as the host does on sign-out, account erasure, a role
     * change or a site-access change. Each ends as closed, not refused, and {@code onLeave} fires per channel.
     *
     * @param userId the user; never {@code null}
     */
    public void endAccess(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        for (ChannelRef ref : List.copyOf(open.keySet())) {
            for (FakeSubscription s : List.copyOf(open.getOrDefault(ref, List.of()))) {
                if (s.caller().userId().equals(userId)) {
                    s.close();
                }
            }
        }
    }

    /** Distinct users subscribed to a channel right now. */
    Set<UUID> subscribers(ChannelRef channel) {
        Set<UUID> out = new LinkedHashSet<>();
        for (FakeSubscription s : open.getOrDefault(channel, List.of())) {
            out.add(s.caller().userId());
        }
        return out;
    }

    /** Removes a subscription and fires {@code onLeave} if it was the user's last on the channel. */
    void end(FakeSubscription subscription) {
        List<FakeSubscription> subs = open.get(subscription.channel());
        if (subs == null || !subs.remove(subscription)) {
            return;
        }
        if (subs.isEmpty()) {
            open.remove(subscription.channel());
        }
        if (!subscribers(subscription.channel()).contains(subscription.caller().userId())) {
            fire(leaveListeners, subscription.channel(), subscription.caller(), "leave");
        }
    }

    private void fire(List<ChannelListener> listeners, ChannelRef channel, Caller caller, String what) {
        for (ChannelListener listener : List.copyOf(listeners)) {
            try {
                listener.on(channel, caller);
            } catch (RuntimeException e) {
                logger.error("channel {} listener failed for {} on {}", what, caller.userId(), channel, e);
            }
        }
    }

    /** One channel handle; every handle on the same ref shares the same subscribers. */
    private final class FakeChannel implements Channel {

        private final ChannelRef ref;

        FakeChannel(ChannelRef ref) {
            this.ref = ref;
        }

        @Override
        public ChannelRef ref() {
            return ref;
        }

        @Override
        public void publish(String event, Object data) {
            deliver(null, event, data);
        }

        @Override
        public void publishTo(UUID userId, String event, Object data) {
            deliver(Objects.requireNonNull(userId, "userId"), event, data);
        }

        @Override
        public Set<UUID> subscribers() {
            return Set.copyOf(FakeChannels.this.subscribers(ref));
        }

        @Override
        public void kick(UUID userId) {
            Objects.requireNonNull(userId, "userId");
            for (FakeSubscription s : List.copyOf(open.getOrDefault(ref, List.of()))) {
                if (s.caller().userId().equals(userId)) {
                    s.refuse();
                }
            }
        }

        private void deliver(UUID recipient, String event, Object data) {
            Objects.requireNonNull(event, "event");
            if (!event.matches(Actions.NAME_PATTERN)) {
                throw new IllegalArgumentException("event name '" + event + "' does not match " + Actions.NAME_PATTERN);
            }
            JsonNode json = data == null ? NullNode.getInstance() : mapper.valueToTree(data);
            int size = mapper.writeValueAsBytes(json).length;
            if (size > MAX_EVENT_BYTES) {
                throw new IllegalArgumentException("event '" + event + "' is " + size + " bytes as JSON, over "
                        + MAX_EVENT_BYTES);
            }
            ChannelEvent e = new ChannelEvent(event, json, clock.get(), recipient);
            published.computeIfAbsent(ref, r -> new ArrayList<>()).add(e);
            for (FakeSubscription s : List.copyOf(open.getOrDefault(ref, List.of()))) {
                if (recipient == null || s.caller().userId().equals(recipient)) {
                    s.receive(e);
                }
            }
        }
    }
}
