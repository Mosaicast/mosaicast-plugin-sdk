// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaicast.plugin.api.Role;
import dev.mosaicast.plugin.api.SearchHit;
import dev.mosaicast.plugin.api.SearchProvider;
import dev.mosaicast.plugin.api.Tags;
import dev.mosaicast.plugin.api.UserDataHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests the two extension-point harnesses and {@link FakePluginContext#withTags}. */
class HarnessTest {

    /** A provider that hides drafts from anyone below podcaster — the case worth testing. */
    private static final class WikiSearch implements SearchProvider {
        @Override
        public List<SearchHit> search(String query, Role role, int limit) {
            List<SearchHit> hits = new ArrayList<>();
            hits.add(new SearchHit("glossary/kraken", "The Kraken", "a big squid", 1.0));
            if (role == Role.PODCASTER || role == Role.ADMIN) {
                hits.add(new SearchHit("draft/lighthouse", "Draft: The Lighthouse", "unfinished", 0.5));
            }
            return hits;
        }
    }

    /** The bug the harness is for: a provider that ignores the role entirely. */
    private static final class LeakySearch implements SearchProvider {
        @Override
        public List<SearchHit> search(String query, Role role, int limit) {
            return List.of(new SearchHit("draft/lighthouse", "Draft: The Lighthouse", "unfinished", 1.0));
        }
    }

    @Test
    void searchHarnessCallsEveryRoleIncludingAnonymous() {
        var results = new SearchProviderHarness(new WikiSearch()).search("kraken");

        assertEquals(List.of("The Kraken"), results.titles(null));                 // anonymous
        assertEquals(List.of("The Kraken"), results.titles(Role.FAN));
        assertEquals(List.of("The Kraken", "Draft: The Lighthouse"), results.titles(Role.PODCASTER));
        assertEquals(List.of("The Kraken", "Draft: The Lighthouse"), results.titles(Role.ADMIN));
    }

    @Test
    void searchHarnessNamesTheLeakTheHostWouldNotCatch() {
        var safe = new SearchProviderHarness(new WikiSearch()).search("lighthouse");
        var leaky = new SearchProviderHarness(new LeakySearch()).search("lighthouse");

        assertFalse(safe.leakedToAnonymous("draft/lighthouse"));
        // Access is the plugin's job here: the host has no model of a draft, so nothing else catches this.
        assertTrue(leaky.leakedToAnonymous("draft/lighthouse"));
    }

    @Test
    void userDataHarnessCatchesANonIdempotentErasure() {
        UserDataHandler brittle = new UserDataHandler() {
            private boolean erased;

            @Override
            public void eraseUser(String userId) {
                if (erased) {
                    throw new IllegalStateException("no rows for " + userId);
                }
                erased = true;
            }
        };

        AssertionError failure =
                assertThrows(AssertionError.class, () -> new UserDataHandlerHarness(brittle).eraseTwice("u-1"));

        // The failure mode: it only appears during a retry, when the alternative is a half-done deletion.
        assertTrue(failure.getMessage().contains("not idempotent"));
    }

    @Test
    void userDataHarnessPassesAnIdempotentHandlerAndForwardsTheExport() {
        List<String> erased = new ArrayList<>();
        UserDataHandler handler = new UserDataHandler() {
            @Override
            public void eraseUser(String userId) {
                erased.add(userId);   // erasing nothing succeeds, which is what idempotent means here
            }

            @Override
            public Optional<Map<String, Object>> exportUser(String userId) {
                return Optional.of(Map.of("pages", List.of("kraken")));
            }
        };
        var harness = new UserDataHandlerHarness(handler);

        assertTrue(harness.export("u-1").isPresent());
        harness.eraseTwice("u-1");

        assertEquals(List.of("u-1", "u-1"), erased);
    }

    @Test
    void fakeContextHasNoTagsUntilOneIsWiredIn() {
        FakePluginContext ctx = new FakePluginContext();

        // Null is what a plugin without a `tags` block sees from the host.
        assertEquals(null, ctx.tags());

        Tags tags = new FakeTags();
        assertEquals(tags, ctx.withTags(tags).tags());
    }

    @Test
    void withTagsLeavesTheExistingConstructorsUntouched() {
        // The compatibility property: every plugin test written against 0.8.0 still compiles and still
        // means the same thing. A sixth positional parameter would have broken all of them.
        FakePluginContext four =
                new FakePluginContext(new InMemoryDocStore(), new MapPluginConfig(), new FakeFeedAccess(Map.of()), null);
        FakePluginContext five = new FakePluginContext(
                new InMemoryDocStore(), new MapPluginConfig(), new FakeFeedAccess(Map.of()), null, null);

        assertEquals(null, four.tags());
        assertEquals(null, five.tags());
    }
}
