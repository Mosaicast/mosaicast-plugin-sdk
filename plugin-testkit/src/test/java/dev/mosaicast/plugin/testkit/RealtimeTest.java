// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.ActionRejectedException;
import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.ChannelRef;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.Scheduled;
import dev.mosaicast.plugin.api.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;
import tools.jackson.databind.JsonNode;

/** Actions, channels and timers (0.20.0), driven through a small turn-taking plugin. */
class RealtimeTest {

    private static final Scope EP = Scope.episode("s1e01");
    private static final ChannelRef GAME = new ChannelRef(EP, "game");

    /** Players join, take turns, and a turn ends after 30 seconds if nobody moves. */
    static final class TurnPlugin {
        final Set<UUID> players = new LinkedHashSet<>();
        final List<String> log = new ArrayList<>();
        UUID turn;
        Scheduled clock;

        void register(PluginContext ctx) {
            ctx.actions().handle("join", call -> {
                players.add(call.caller().userId());
                if (turn == null) {
                    turn = call.caller().userId();
                }
                call.store().put(Scope.user(), "joined", call.receivedAt().toString());
                return Map.of("players", players.size());
            });
            ctx.actions().handle("move", call -> {
                if (!call.caller().userId().equals(turn)) {
                    throw new ActionRejectedException("not-your-turn");
                }
                int cell = call.payload(Move.class).cell();
                ctx.store().put(call.scope(), "game:board", cell);
                ctx.channels().channel(call.scope(), "game").publish("moved", Map.of("cell", cell));
                if (clock != null) {
                    clock.cancel();
                }
                clock = ctx.runAfter(Duration.ofSeconds(30), () -> {
                    log.add("timeout@" + ctx.channels().channel(EP, "game").subscribers().size());
                    ctx.channels().channel(EP, "game").publish("timeout", null);
                });
                return null;
            });
            ctx.actions().handle("start", call -> Map.of("started", true));
            ctx.channels().authorize((caller, ref) -> players.contains(caller.userId()));
            ctx.channels().onJoin((ref, caller) -> log.add("join " + caller.role()));
            ctx.channels().onLeave((ref, caller) -> log.add("leave " + caller.role()));
        }
    }

    record Move(int cell) {}

    private static FakePluginContext ctx(TurnPlugin plugin) {
        FakePluginContext ctx = new FakePluginContext().withActions("join", "move", "start").withChannels();
        ctx.actions().withNameFloor("start", Role.PODCASTER);
        ctx.store().withBackendPrivate("game:*");
        plugin.register(ctx);
        return ctx;
    }

    @Test
    void capabilitiesAreAbsentUntilDeclared() {
        FakePluginContext ctx = new FakePluginContext();
        assertNull(ctx.actions());
        assertNull(ctx.channels());
    }

    @Test
    void anActionAnswersAsJsonAndRejectsWithACode() {
        TurnPlugin plugin = new TurnPlugin();
        FakePluginContext ctx = ctx(plugin);
        Caller alice = Callers.fan();
        Caller bob = Callers.fan();

        JsonNode answer = ctx.actions().call(alice, "join", EP, null);
        assertEquals(1, answer.get("players").asInt());
        ctx.actions().call(bob, "join", EP, null);

        ActionRejectedException e = assertThrows(ActionRejectedException.class,
                () -> ctx.actions().call(bob, "move", EP, new Move(3)));
        assertEquals("not-your-turn", e.code());
        assertNull(ctx.actions().call(alice, "move", EP, new Move(3)));
        assertEquals(3, ctx.store().get(EP, "game:board", Integer.class).orElseThrow());
    }

    @Test
    void theCallStoreWritesTheCallersOwnPartition() {
        FakePluginContext ctx = ctx(new TurnPlugin());
        Caller alice = Callers.fan();

        ctx.actions().call(alice, "join", EP, null);

        assertEquals(FakePluginContext.START.toString(),
                ctx.store().asUser(alice.userId()).get(Scope.user(), "joined", String.class).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> ctx.store().get(Scope.user(), "joined", String.class));
    }

    @Test
    void theHostsRefusalsComeBeforeAnyHandler() {
        FakePluginContext ctx = ctx(new TurnPlugin());

        assertThrows(IllegalStateException.class, () -> ctx.actions().call(Callers.fan(), "nope", EP, null));
        assertThrows(IllegalStateException.class, () -> ctx.actions().call(Callers.fan(), "start", EP, null));
        assertEquals(true, ctx.actions().call(Callers.podcaster(), "start", EP, null).get("started").asBoolean());
        assertThrows(IllegalStateException.class,
                () -> ctx.actions().call(Callers.fan(), "join", EP, "x".repeat(16 * 1024)));
        assertThrows(IllegalArgumentException.class, () -> ctx.actions().call(Callers.fan(), "join", Scope.user(), null));
        assertThrows(IllegalArgumentException.class, () -> ctx.actions().handle("undeclared", call -> null));
    }

    @Test
    void aDeclaredNameWithoutAHandlerIsTheAuthorsBug() {
        FakePluginContext ctx = new FakePluginContext().withActions("orphan");
        assertThrows(IllegalStateException.class, () -> ctx.actions().call(Callers.fan(), "orphan", EP, null));
    }

    @Test
    void aNameFloorOnlyRaises() {
        FakePluginContext ctx = new FakePluginContext().withActions("a");
        ctx.actions().withCallableBy(Role.PODCASTER);
        assertThrows(IllegalArgumentException.class, () -> ctx.actions().withNameFloor("a", Role.FAN));
        assertThrows(IllegalArgumentException.class, () -> new FakePluginContext().withActions("Bad"));
    }

    @Test
    void theAuthorizerDecidesWhoListensAndEventsReachThem() {
        TurnPlugin plugin = new TurnPlugin();
        FakePluginContext ctx = ctx(plugin);
        Caller alice = Callers.fan();
        ctx.actions().call(alice, "join", EP, null);

        SubscriptionRefusedException refused = assertThrows(SubscriptionRefusedException.class,
                () -> ctx.channels().subscribe(Callers.fan(), GAME));
        assertEquals(SubscriptionRefusedException.Reason.AUTHORIZER, refused.reason());

        FakeSubscription tab1 = ctx.channels().subscribe(alice, GAME);
        FakeSubscription tab2 = ctx.channels().subscribe(alice, GAME);
        assertEquals(List.of("join FAN"), plugin.log);
        assertEquals(Set.of(alice.userId()), ctx.channels().channel(EP, "game").subscribers());

        ctx.actions().call(alice, "move", EP, new Move(5));
        assertEquals(5, tab1.events().getFirst().data().get("cell").asInt());
        assertEquals(1, tab2.events().size());

        tab1.close();
        assertEquals(List.of("join FAN"), plugin.log);
        tab2.close();
        assertEquals(List.of("join FAN", "leave FAN"), plugin.log);
    }

    @Test
    void publishToReachesOneUserAndKickRefuses() {
        TurnPlugin plugin = new TurnPlugin();
        FakePluginContext ctx = ctx(plugin);
        Caller alice = Callers.fan();
        Caller bob = Callers.fan();
        ctx.actions().call(alice, "join", EP, null);
        ctx.actions().call(bob, "join", EP, null);
        FakeSubscription a = ctx.channels().subscribe(alice, GAME);
        FakeSubscription b = ctx.channels().subscribe(bob, GAME);

        ctx.channels().channel(EP, "game").publishTo(bob.userId(), "your-turn", null);
        assertTrue(a.events().isEmpty());
        assertEquals(bob.userId(), b.events().getFirst().recipient());
        assertEquals(1, ctx.channels().published(GAME).size());

        ctx.channels().channel(EP, "game").kick(bob.userId());
        assertTrue(b.wasRefused());
        assertFalse(b.isOpen());

        ctx.channels().endAccess(alice.userId());
        assertFalse(a.isOpen());
        assertFalse(a.wasRefused());
        assertEquals(List.of("join FAN", "join FAN", "leave FAN", "leave FAN"), plugin.log);
    }

    @Test
    void noAuthorizerRefusesEveryoneAndTheFloorComesFirst() {
        FakePluginContext ctx = new FakePluginContext().withChannels();
        assertEquals(SubscriptionRefusedException.Reason.NO_AUTHORIZER,
                assertThrows(SubscriptionRefusedException.class,
                        () -> ctx.channels().subscribe(Callers.admin(), GAME)).reason());

        ctx.channels().withSubscribableBy(Role.PODCASTER).authorize((c, r) -> true);
        assertEquals(SubscriptionRefusedException.Reason.BELOW_FLOOR,
                assertThrows(SubscriptionRefusedException.class,
                        () -> ctx.channels().subscribe(Callers.fan(), GAME)).reason());
    }

    @Test
    void aBrokenEventIsTheBackendsBug() {
        FakePluginContext ctx = new FakePluginContext().withChannels();
        var channel = ctx.channels().channel(EP, "game");

        assertThrows(IllegalArgumentException.class, () -> channel.publish("Bad Name", null));
        assertThrows(IllegalArgumentException.class, () -> channel.publish("big", "x".repeat(8 * 1024)));
        channel.publish("nobody-listens", Map.of());
        assertEquals(1, ctx.channels().published(GAME).size());
    }

    @Test
    void timersWaitForTheFakeClock() {
        TurnPlugin plugin = new TurnPlugin();
        FakePluginContext ctx = ctx(plugin);
        Caller alice = Callers.fan();
        ctx.actions().call(alice, "join", EP, null);
        FakeSubscription sub = ctx.channels().subscribe(alice, GAME);

        ctx.actions().call(alice, "move", EP, new Move(1));
        assertEquals(1, ctx.pendingTimers());
        ctx.advanceTimers(Duration.ofSeconds(20));
        ctx.actions().call(alice, "move", EP, new Move(2));   // re-arms: the first clock is cancelled
        assertEquals(1, ctx.pendingTimers());

        ctx.advanceTimers(Duration.ofSeconds(29));
        assertFalse(plugin.log.contains("timeout@1"));
        ctx.advanceTimers(Duration.ofSeconds(1));
        assertTrue(plugin.log.contains("timeout@1"));
        assertEquals("timeout", sub.events().getLast().event());
        assertEquals(FakePluginContext.START.plusSeconds(50), sub.events().getLast().at());
        assertEquals(0, ctx.pendingTimers());
    }

    @Test
    void timersRunInDueOrderIncludingOnesScheduledOnTheWay() {
        FakePluginContext ctx = new FakePluginContext();
        List<String> ran = new ArrayList<>();
        ctx.runAfter(Duration.ofSeconds(2), () -> ran.add("b@" + ctx.now()));
        ctx.runAfter(Duration.ZERO, () -> {
            ran.add("a");
            ctx.runAfter(Duration.ofSeconds(1), () -> ran.add("a2"));
        });
        Scheduled never = ctx.runAfter(Duration.ofSeconds(1), () -> ran.add("cancelled"));
        ctx.runAfter(Duration.ofSeconds(1), () -> {
            throw new IllegalStateException("boom");
        });

        assertTrue(never.cancel());
        assertFalse(never.cancel());
        assertTrue(ran.isEmpty());
        ctx.advanceTimers(Duration.ofSeconds(5));

        Instant b = FakePluginContext.START.plusSeconds(2);
        assertEquals(List.of("a", "a2", "b@" + b), ran);
        assertEquals(FakePluginContext.START.plusSeconds(5), ctx.now());
        assertEquals(1, ctx.logger().events(Level.ERROR).size());
        assertThrows(IllegalArgumentException.class, () -> ctx.runAfter(Duration.ofSeconds(-1), () -> {}));
        assertThrows(IllegalArgumentException.class, () -> ctx.runAfter(null, () -> {}));
    }
}
