// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link NotifyMessage} — the English requirement, the reader's-language lookup, and the copy that
 * keeps a sent message from mutating under the host.
 */
class NotifyMessageTest {

    private static final Map<String, String> BILINGUAL =
            Map.of("en", "Bingo resolved", "de", "Bingo aufgelöst");

    @Test
    void carriesEveryLanguageItWasGiven() {
        NotifyMessage msg = new NotifyMessage(BILINGUAL);

        assertEquals(BILINGUAL, msg.text());
        assertNull(msg.link());
    }

    @Test
    void picksTheReadersLanguageAndFallsBackToEnglish() {
        NotifyMessage msg = new NotifyMessage(BILINGUAL);

        assertEquals("Bingo aufgelöst", msg.textFor("de"));
        assertEquals("Bingo resolved", msg.textFor("en"));
        // The whole point of shipping every locale: a reader in a language the plugin does not have still
        // gets a sentence rather than a blank row.
        assertEquals("Bingo resolved", msg.textFor("nl"));
        assertEquals("Bingo resolved", msg.textFor(null));
        assertEquals("Bingo resolved", msg.textFor("  "));
    }

    @Test
    void normalisesLocaleCodesTheWayTheRestOfTheContractDoes() {
        NotifyMessage msg = new NotifyMessage(Map.of("EN", "Resolved", " De ", "Aufgelöst"));

        assertEquals(Map.of("en", "Resolved", "de", "Aufgelöst"), msg.text());
        assertEquals("Aufgelöst", msg.textFor("DE"));
    }

    @Test
    void theEnglishOnlyFormIsTheHonestShapeForAMonolingualPlugin() {
        NotifyMessage msg = new NotifyMessage("Bingo resolved");

        assertEquals(Map.of("en", "Bingo resolved"), msg.text());
        assertEquals("Bingo resolved", msg.textFor("de"));
    }

    @Test
    void insistsOnEnglish() {
        // §12.7: English is the one language a site cannot switch off, so it is the only fallback a
        // reader is guaranteed to understand. A German-only message renders blank for everyone else.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new NotifyMessage(Map.of("de", "Bingo aufgelöst")));

        assertTrue(thrown.getMessage().contains("en"), thrown.getMessage());
    }

    @Test
    void refusesAnEmptyOrBlankMessage() {
        assertThrows(IllegalArgumentException.class, () -> new NotifyMessage(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new NotifyMessage(""));
        assertThrows(IllegalArgumentException.class, () -> new NotifyMessage(Map.of("en", "   ")));
        assertThrows(IllegalArgumentException.class, () -> new NotifyMessage(Map.of("", "Resolved")));
    }

    @Test
    void copiesTheTextItWasHandedIn() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("en", "Bingo resolved");
        NotifyMessage msg = new NotifyMessage(mutable);

        // A caller reusing its map for the next recipient must not rewrite one already sent.
        mutable.put("en", "Something else");

        assertEquals("Bingo resolved", msg.textFor("en"));
        assertThrows(UnsupportedOperationException.class, () -> msg.text().put("nl", "x"));
    }

    @Test
    void withLinkLeavesTheOriginalAlone() {
        NotifyMessage bare = new NotifyMessage(BILINGUAL);

        NotifyMessage linked = bare.withLink("board/42");

        assertEquals("board/42", linked.link());
        assertEquals(bare.text(), linked.text());
        assertNull(bare.link());
        assertNull(linked.withLink(null).link());
    }
}
