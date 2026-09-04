// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests {@link NotifyMessage} — the validation, and the copy that keeps a sent message from mutating. */
class NotifyMessageTest {

    @Test
    void theShortFormsFillInTheRest() {
        NotifyMessage bare = new NotifyMessage("bingo.resolved");
        assertEquals(Map.of(), bare.params());
        assertNull(bare.link());

        NotifyMessage withParams = new NotifyMessage("bingo.resolved", Map.of("episode", "S02E04"));
        assertEquals(Map.of("episode", "S02E04"), withParams.params());
        assertNull(withParams.link());
    }

    @Test
    void nullParamsBecomeAnEmptyMap() {
        // So a caller never has to null-check what it reads back, and the host never has to either.
        assertEquals(Map.of(), new NotifyMessage("k", null, null).params());
    }

    @Test
    void copiesTheParamsItWasHandedIn() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("episode", "S02E04");
        NotifyMessage msg = new NotifyMessage("bingo.resolved", mutable);

        // A caller reusing its map for the next recipient must not rewrite one already sent.
        mutable.put("episode", "S02E05");

        assertEquals("S02E04", msg.params().get("episode"));
        assertThrows(UnsupportedOperationException.class, () -> msg.params().put("x", "y"));
    }

    @Test
    void refusesABlankKey() {
        // Blank is not a message, and the host would refuse it — better here, where the stack trace names
        // the plugin's own line.
        assertThrows(IllegalArgumentException.class, () -> new NotifyMessage(""));
        assertThrows(IllegalArgumentException.class, () -> new NotifyMessage("   "));
        assertThrows(NullPointerException.class, () -> new NotifyMessage(null));
    }

    @Test
    void withLinkLeavesTheOriginalAlone() {
        NotifyMessage bare = new NotifyMessage("bingo.resolved", Map.of("episode", "S02E04"));

        NotifyMessage linked = bare.withLink("board/42");

        assertEquals("board/42", linked.link());
        assertEquals(bare.params(), linked.params());
        assertNull(bare.link());
        assertNull(linked.withLink(null).link());
    }
}
