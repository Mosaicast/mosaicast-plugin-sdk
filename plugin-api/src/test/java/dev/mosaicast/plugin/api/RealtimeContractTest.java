// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The value types of actions and channels (0.20.0) validate what the host would refuse. */
class RealtimeContractTest {

    @Test
    void aChannelRefTakesAPageScopeAndAGrammaticalName() {
        ChannelRef ref = new ChannelRef(Scope.episode("s1e01"), "game:42_a-B");
        assertEquals("game:42_a-B", ref.name());

        assertThrows(IllegalArgumentException.class, () -> new ChannelRef(Scope.user(), "inbox"));
        assertThrows(IllegalArgumentException.class, () -> new ChannelRef(Scope.site(), ""));
        assertThrows(IllegalArgumentException.class, () -> new ChannelRef(Scope.site(), "a/b"));
        assertThrows(IllegalArgumentException.class, () -> new ChannelRef(Scope.site(), "x".repeat(129)));
        assertThrows(NullPointerException.class, () -> new ChannelRef(null, "inbox"));
    }

    @Test
    void aRejectionCarriesAGrammaticalCode() {
        ActionRejectedException bare = new ActionRejectedException("not-your-turn");
        assertEquals("not-your-turn", bare.code());
        assertNull(bare.detail());
        assertEquals("not-your-turn", bare.getMessage());

        ActionRejectedException detailed = new ActionRejectedException("full", "8 of 8 seats taken");
        assertEquals("8 of 8 seats taken", detailed.detail());
        assertEquals("full: 8 of 8 seats taken", detailed.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new ActionRejectedException("Not_Your_Turn"));
        assertThrows(IllegalArgumentException.class, () -> new ActionRejectedException("-leading"));
        assertThrows(NullPointerException.class, () -> new ActionRejectedException(null, "why"));
    }

    @Test
    void namesFollowOneGrammar() {
        assertEquals(true, "move".matches(Actions.NAME_PATTERN));
        assertEquals(true, "a".repeat(64).matches(Actions.NAME_PATTERN));
        assertEquals(false, "a".repeat(65).matches(Actions.NAME_PATTERN));
        assertEquals(false, "Move".matches(Actions.NAME_PATTERN));
        assertEquals(16384, Actions.MAX_BODY_BYTES);
        assertEquals(8192, Channel.MAX_EVENT_BYTES);
    }

    @Test
    void aCallerNeedsBoth() {
        assertThrows(NullPointerException.class, () -> new Caller(null, Role.FAN));
        assertThrows(NullPointerException.class, () -> new Caller(UUID.randomUUID(), null));
    }
}
