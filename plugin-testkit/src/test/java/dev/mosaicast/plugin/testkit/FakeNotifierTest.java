// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.NotificationException;
import dev.mosaicast.plugin.api.NotifyMessage;
import dev.mosaicast.plugin.api.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link FakeNotifier} — above all the partial send, which is what a permissive double would hide,
 * and the eligibility rule, which this double reads from the store rather than keeping its own copy of.
 */
class FakeNotifierTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    /** A store where Ana has played and the stranger has not — the shape every test here needs. */
    private static InMemoryDocStore storeWithAna() {
        InMemoryDocStore store = new InMemoryDocStore();
        store.asUser(ANA).put(Scope.user(), "mark:s2e04:b3", true);
        return store;
    }

    @Test
    void notifiesAParticipant() throws Exception {
        InMemoryDocStore store = storeWithAna();
        FakeNotifier notifier = new FakeNotifier(store);
        NotifyMessage msg = new NotifyMessage("bingo.resolved", Map.of("episode", "S02E04"));

        List<UUID> told = notifier.send(List.of(ANA), msg);

        assertEquals(List.of(ANA), told);
        assertEquals(List.of(msg), notifier.messagesFor(ANA));
        assertEquals(List.of(new FakeNotifier.Delivery(ANA, msg)), notifier.delivered());
    }

    @Test
    void leavesOutSomebodyThePluginHoldsNoDataFor() throws Exception {
        FakeNotifier notifier = new FakeNotifier(storeWithAna());

        List<UUID> told = notifier.send(List.of(ANA, STRANGER), new NotifyMessage("bingo.resolved"));

        // The partial send: one stale participant does not cost the other their notification, and the
        // return value is the only way a plugin can tell.
        assertEquals(List.of(ANA), told);
        assertEquals(List.of(), notifier.messagesFor(STRANGER));
    }

    @Test
    void readsEligibilityFromTheStoreRatherThanASeededList() throws Exception {
        InMemoryDocStore store = storeWithAna();
        FakeNotifier notifier = new FakeNotifier(store);
        assertEquals(Set.of(ANA), notifier.notifiable());

        // The stranger plays. Nothing is told to the notifier — it asks the store, as the host does.
        store.asUser(STRANGER).put(Scope.user(), "mark:s2e04:b4", true);

        assertEquals(Set.of(ANA, STRANGER), notifier.notifiable());
        assertEquals(List.of(ANA, STRANGER), notifier.send(List.of(ANA, STRANGER), new NotifyMessage("x")));
    }

    @Test
    void notifiesEachRecipientOnce() throws Exception {
        FakeNotifier notifier = new FakeNotifier(storeWithAna());

        List<UUID> told = notifier.send(List.of(ANA, ANA, ANA), new NotifyMessage("bingo.resolved"));

        assertEquals(List.of(ANA), told);
        assertEquals(1, notifier.messagesFor(ANA).size());
    }

    @Test
    void refusesTheSendOverThePerUserCap() throws Exception {
        FakeNotifier notifier = new FakeNotifier(storeWithAna()).withPerUserPerDay(2);
        notifier.send(List.of(ANA), new NotifyMessage("one"));
        notifier.send(List.of(ANA), new NotifyMessage("two"));

        NotificationException thrown = assertThrows(NotificationException.class,
                () -> notifier.send(List.of(ANA), new NotifyMessage("three")));

        assertEquals(NotificationException.Reason.RATE_LIMITED, thrown.reason());
        // Retryable, which is what tells a scheduled sender to hold the batch rather than drop it.
        assertTrue(thrown.retryable());
        assertEquals(2, notifier.messagesFor(ANA).size());
    }

    @Test
    void anIneligibleRecipientDoesNotConsumeTheCap() throws Exception {
        FakeNotifier notifier = new FakeNotifier(storeWithAna()).withPerUserPerDay(1);

        // The stranger is never delivered to, so they cannot use up a send the host would not have made.
        notifier.send(List.of(STRANGER), new NotifyMessage("one"));
        notifier.send(List.of(STRANGER), new NotifyMessage("two"));

        assertEquals(List.of(ANA), notifier.send(List.of(ANA), new NotifyMessage("three")));
    }

    @Test
    void isUncappedUntilATestArmsTheCap() throws Exception {
        FakeNotifier notifier = new FakeNotifier(storeWithAna());

        for (int i = 0; i < 50; i++) {
            notifier.send(List.of(ANA), new NotifyMessage("spam"));
        }

        assertEquals(50, notifier.messagesFor(ANA).size());
        assertThrows(IllegalArgumentException.class, () -> notifier.withPerUserPerDay(0));
    }

    @Test
    void anEmptySendDeliversNothingRatherThanFailing() throws Exception {
        FakeNotifier notifier = new FakeNotifier(storeWithAna());

        assertEquals(List.of(), notifier.send(List.of(), new NotifyMessage("bingo.resolved")));
        assertEquals(List.of(), notifier.delivered());
    }

    @Test
    void rejectsNullArguments() {
        FakeNotifier notifier = new FakeNotifier(storeWithAna());
        List<UUID> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> notifier.send(null, new NotifyMessage("x")));
        assertThrows(NullPointerException.class, () -> notifier.send(List.of(ANA), null));
        assertThrows(NullPointerException.class, () -> notifier.send(withNull, new NotifyMessage("x")));
    }

    @Test
    void contextHasNoNotifierUntilOneIsWired() throws Exception {
        FakePluginContext ctx = new FakePluginContext();

        // The undeclared-manifest case, which is most plugins.
        assertNull(ctx.notifier());

        ctx.store().asUser(ANA).put(Scope.user(), "mark:s2e04:b3", true);
        ctx.withNotifier(new FakeNotifier(ctx.store()));

        assertEquals(List.of(ANA), ctx.notifier().send(List.of(ANA, STRANGER), new NotifyMessage("x")));
        assertNull(ctx.withNotifier(null).notifier());
    }
}
