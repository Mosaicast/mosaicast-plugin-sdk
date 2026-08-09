// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.DisplaySnapshot;
import dev.mosaicast.plugin.api.DocEntry;
import dev.mosaicast.plugin.api.OwnedDocEntry;
import dev.mosaicast.plugin.api.PluginBackend;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.Scope;
import dev.mosaicast.plugin.api.ScopeType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Self-tests proving the test doubles behave like the real contract. */
class TestKitTest {

    /** A simple value type stored round-trip through the doc store. */
    record Vote(String user, int number) {
    }

    @Test
    void docStoreRoundTrips() {
        InMemoryDocStore store = new InMemoryDocStore();
        Scope ep = Scope.episode("ep-1");

        store.put(ep, "vote:alice", new Vote("alice", 7));
        Optional<Vote> got = store.get(ep, "vote:alice", Vote.class);

        assertTrue(got.isPresent());
        assertEquals(new Vote("alice", 7), got.get());
    }

    @Test
    void docStoreQueryMatchesPrefixAndScope() {
        InMemoryDocStore store = new InMemoryDocStore();
        Scope ep1 = Scope.episode("ep-1");
        Scope ep2 = Scope.episode("ep-2");

        store.put(ep1, "vote:alice", new Vote("alice", 1));
        store.put(ep1, "vote:bob", new Vote("bob", 2));
        store.put(ep1, "meta:title", "x");
        store.put(ep2, "vote:carol", new Vote("carol", 3)); // different scope, must not leak

        assertEquals(2, store.query(ep1, "vote:").size());
        assertEquals(3, store.query(ep1, "").size());
        assertTrue(store.get(ep2, "vote:alice", Vote.class).isEmpty());
    }

    @Test
    void docStoreQueryCarriesKeys() {
        InMemoryDocStore store = new InMemoryDocStore();
        Scope ep = Scope.episode("ep-1");
        store.put(ep, "vote:alice", new Vote("alice", 1));

        List<DocEntry> entries = store.query(ep, "vote:");

        assertEquals(1, entries.size());
        assertEquals("vote:alice", entries.get(0).key());
        assertEquals("alice", entries.get(0).value().get("user").stringValue());
    }

    @Test
    void docStoreDeleteRemovesAndIsIdempotent() {
        InMemoryDocStore store = new InMemoryDocStore();
        Scope ep = Scope.episode("ep-1");
        store.put(ep, "vote:alice", new Vote("alice", 1));

        assertTrue(store.delete(ep, "vote:alice"));   // removed
        assertFalse(store.delete(ep, "vote:alice"));  // nothing left to remove — not an error
        assertTrue(store.get(ep, "vote:alice", Vote.class).isEmpty());
        assertFalse(store.delete(Scope.episode("never-written"), "nope")); // unknown scope
    }

    @Test
    void docStoreRejectsKeysTheFrontendCouldNotAddress() {
        InMemoryDocStore store = new InMemoryDocStore();
        Scope ep = Scope.episode("ep-1");

        // A '/' would not survive the host's …/data/{scopeType}/{scopeId}/{key} path.
        assertThrows(IllegalArgumentException.class, () -> store.put(ep, "vote/alice", new Vote("a", 1)));
        assertThrows(IllegalArgumentException.class, () -> store.put(ep, "", new Vote("a", 1)));
        store.put(ep, "vote:alice.v2-final_", new Vote("a", 1)); // ':' '.' '-' '_' are all allowed
    }

    @Test
    void userScopeIsRefusedOnEveryBackendMethod() {
        InMemoryDocStore store = new InMemoryDocStore();
        Scope me = Scope.user();

        // A backend thread has no calling user, so there is no "me" to resolve — reads included.
        assertThrows(UnsupportedOperationException.class, () -> store.get(me, "k", Vote.class));
        assertThrows(UnsupportedOperationException.class, () -> store.put(me, "k", new Vote("a", 1)));
        assertThrows(UnsupportedOperationException.class, () -> store.delete(me, "k"));
        assertThrows(UnsupportedOperationException.class, () -> store.query(me, ""));
    }

    @Test
    void userScopeIsASingletonNoPluginCanRedirect() {
        assertEquals(Scope.SELF_ID, Scope.user().id());
        // Whatever id is handed in, the scope addresses the caller's own partition and nobody else's.
        assertEquals(Scope.user(), new Scope(ScopeType.USER, "some-other-user"));
    }

    @Test
    void asUserSeedsAPartitionAndQueryAcrossUsersAggregatesThem() {
        InMemoryDocStore store = new InMemoryDocStore();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        // What each frontend would have written through …/data/user/me/{key}.
        store.asUser(alice).put(Scope.user(), "mark:s2e04:b3", new Vote("alice", 3));
        store.asUser(bob).put(Scope.user(), "mark:s2e04:b7", new Vote("bob", 7));
        store.asUser(bob).put(Scope.user(), "pref:theme", "dark");

        List<OwnedDocEntry> marks = store.queryAcrossUsers("mark:");

        assertEquals(2, marks.size());
        assertEquals(Set.of(alice, bob), marks.stream().map(OwnedDocEntry::userId).collect(Collectors.toSet()));
        assertEquals(3, store.queryAcrossUsers("").size());

        // A partition is private to its owner: one user's view never sees another's document.
        assertTrue(store.asUser(alice).get(Scope.user(), "mark:s2e04:b7", Vote.class).isEmpty());
        assertEquals(new Vote("alice", 3),
                store.asUser(alice).get(Scope.user(), "mark:s2e04:b3", Vote.class).orElseThrow());
        assertEquals(1, store.docsOf(alice).size());
    }

    @Test
    void aUserViewStillRefusesNothingElseAndSharesEntityData() {
        InMemoryDocStore store = new InMemoryDocStore();
        Scope ep = Scope.episode("s2e04");
        store.put(ep, "meta:title", "x");

        // Entity scopes behave identically on a view, over the same data.
        assertEquals(Optional.of("x"), store.asUser(UUID.randomUUID()).get(ep, "meta:title", String.class));
        assertFalse(store.asUser(UUID.randomUUID()).delete(Scope.user(), "nothing:here"));
    }

    @Test
    void backendOwnedKeysAreWritableByTheBackendAndNotByClients() {
        InMemoryDocStore store = new InMemoryDocStore().withBackendOwned("stats", "agg:*");
        InMemoryDocStore client = store.asUser(UUID.randomUUID());
        Scope site = Scope.site();

        store.put(site, "stats", new Vote("computed", 1));       // the backend keeps writing
        store.put(site, "agg:favourites", new Vote("computed", 2));

        // The forged PUT/DELETE from the audit — 403 on the host, refused here.
        assertThrows(IllegalStateException.class, () -> client.put(site, "stats", new Vote("forged", 9999)));
        assertThrows(IllegalStateException.class, () -> client.delete(site, "stats"));
        assertThrows(IllegalStateException.class, () -> client.put(site, "agg:favourites", new Vote("f", 1)));

        // Reads are untouched, and the computed value is the one that survives.
        assertEquals(new Vote("computed", 1), client.get(site, "stats", Vote.class).orElseThrow());

        // A sibling key nobody declared stays writable.
        client.put(site, "note", new Vote("client", 3));
        assertEquals(new Vote("client", 3), store.get(site, "note", Vote.class).orElseThrow());
    }

    @Test
    void backendOwnedNeverBindsAUserPartition() {
        InMemoryDocStore store = new InMemoryDocStore().withBackendOwned("*");
        InMemoryDocStore client = store.asUser(UUID.randomUUID());

        // A bare '*' locks every shared key…
        assertThrows(IllegalStateException.class, () -> client.put(Scope.site(), "anything", "x"));
        // …and none of a user's own, which the backend could not write anyway.
        client.put(Scope.user(), "mark:s2e04:b3", Boolean.TRUE);
        assertEquals(Optional.of(Boolean.TRUE), client.get(Scope.user(), "mark:s2e04:b3", Boolean.class));
    }

    @Test
    void backendOwnedRejectsPatternsTheHostWouldRejectAtLoad() {
        InMemoryDocStore store = new InMemoryDocStore();

        assertThrows(IllegalArgumentException.class, () -> store.withBackendOwned("agg:*:total")); // '*' inside
        assertThrows(IllegalArgumentException.class, () -> store.withBackendOwned("")); // empty
        assertThrows(IllegalArgumentException.class, () -> store.withBackendOwned("stats/*")); // '/' not a key char
        store.withBackendOwned("*", "stats", "agg:"); // bare '*', exact, and a prefix that happens to end in ':'
    }

    @Test
    void feedAccessResolvesNoEpisodesForAUserScope() {
        FakeFeedAccess feeds = new FakeFeedAccess(Map.of(Scope.feed("f1"), List.of("ep-1")));
        assertEquals(List.of(), feeds.episodesIn(Scope.user()));
    }

    @Test
    void missingKeyIsEmpty() {
        InMemoryDocStore store = new InMemoryDocStore();
        assertFalse(store.get(Scope.site(), "nope", Vote.class).isPresent());
    }

    @Test
    void siteScopeIsASingleton() {
        assertEquals(Scope.SITE_ID, Scope.site().id());
        // The host normalizes any site scope to the singleton, so these address the same document.
        assertEquals(Scope.site(), new Scope(ScopeType.SITE, "whatever"));
    }

    @Test
    void registerThenAssertStore() {
        // A tiny plugin that seeds a value on register.
        PluginBackend plugin = ctx -> ctx.store().put(Scope.site(), "hello", "world");
        FakePluginContext ctx = new FakePluginContext();

        plugin.register(ctx);

        assertEquals(Optional.of("world"), ctx.store().get(Scope.site(), "hello", String.class));
    }

    @Test
    void onScheduleRunsSynchronously() {
        FakePluginContext ctx = new FakePluginContext();
        PluginBackend plugin = c ->
                c.onSchedule(Duration.ofMinutes(15), () ->
                        c.store().put(Scope.site(), "ran", Boolean.TRUE));

        plugin.register(ctx);

        assertEquals(1, ctx.scheduledCount());
        assertEquals(Optional.of(Boolean.TRUE), ctx.store().get(Scope.site(), "ran", Boolean.class));
    }

    @Test
    void onScheduleRejectsNonPositiveInterval() {
        FakePluginContext ctx = new FakePluginContext();
        assertThrows(IllegalArgumentException.class,
                () -> ctx.onSchedule(Duration.ZERO, () -> { }));
    }

    @Test
    void feedAccessResolvesScopeAndDisplay() {
        Scope feed = Scope.feed("f1");
        DisplaySnapshot snap = new DisplaySnapshot("Ep 1", "notes", "http://a/1.mp3",
                Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMinutes(42),
                "http://a/ep1.jpg", "http://a/feed.jpg", "Ada Lovelace", "First episode");
        FakeFeedAccess feeds = new FakeFeedAccess(Map.of(feed, List.of("ep-1", "ep-2")))
                .withDisplay("ep-1", snap);

        assertEquals(List.of("ep-1", "ep-2"), feeds.episodesIn(feed));
        assertEquals(snap, feeds.display("ep-1"));
        assertThrows(IllegalArgumentException.class, () -> feeds.display("ep-unknown"));

        // artwork() prefers the episode cover, falls back to the feed cover, then null.
        assertEquals("http://a/ep1.jpg", snap.artwork());
        assertEquals("http://a/feed.jpg", new DisplaySnapshot("t", "d", null, null, null,
                null, "http://a/feed.jpg", null, null).artwork());
        assertEquals(null, new DisplaySnapshot("t", "d", null, null, null,
                null, null, null, null).artwork());
    }

    @Test
    void configReadsTypedValuesWithFallback() {
        MapPluginConfig config = new MapPluginConfig().with("fuzzyThreshold", 0.85);
        assertEquals(0.85, config.get("fuzzyThreshold", Double.class).orElseThrow());
        assertEquals(1.0, config.get("missing", Double.class, 1.0));
    }

    @Test
    void schemaAbsentByDefault() {
        assertEquals(null, new FakePluginContext().schema());
    }

    // Compile-time proof the fake is usable anywhere a PluginContext is expected.
    @SuppressWarnings("unused")
    private static void acceptsContract(PluginContext ctx) {
    }
}
