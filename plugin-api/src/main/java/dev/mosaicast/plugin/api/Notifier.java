// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Puts a message in a user's inbox (ARCHITECTURE §17).
 *
 * <p>A plugin that finishes a long-running thing a user took part in — a bingo resolving, the case this
 * was written for — could previously only hope they came back and looked. This is the surface that lets
 * it say so. It is where nearly all real use lives, because the thing worth announcing usually finishes
 * on a timer rather than in somebody's browser: expect to call this from
 * {@link PluginContext#onSchedule(java.time.Duration, Runnable)}.
 *
 * <p>Reachable through {@link PluginContext#notifier()}, which is <strong>{@code null}</strong> unless the
 * manifest declares a {@code notifications} block — the same shape and the same reasoning as
 * {@link PluginBlobs}, {@link Tags} and {@link Users}:
 *
 * <pre>{@code
 * "notifications": { "sends": true, "perUserPerDay": 5 }
 * }</pre>
 *
 * <p>{@code perUserPerDay} is what the plugin <em>asks</em> for; what it gets is the operator's cap over
 * that, exactly as {@code blobs} quotas work. Of every block in the manifest this is the one an operator
 * most needs to see before installing, which is the whole argument for declaring it.
 *
 * <h2>The one surface that writes into somebody else's site</h2>
 *
 * <p>Everything else a plugin touches is its own scope or the current visitor's. This is not: it puts
 * text in front of a person who did not ask for it, which unbounded is a spam cannon aimed at the user
 * list. So the host draws two lines the plugin cannot move.
 *
 * <p><strong>A plugin may only notify users it already holds {@link ScopeType#USER}-scope data for.</strong>
 * Enforced against the same partitions {@link DocStore#queryAcrossUsers(String)} reads, so it needs no new
 * concept: bingo may write to its participants because participants have rows, and no plugin can reach a
 * user who never touched it. The rule outlives the case it was written for — a comments plugin notifies a
 * thread's participants, who are exactly the users it stores rows for.
 *
 * <p><strong>The rate limits are the host's</strong>, per plugin per recipient per window plus a ceiling
 * across all recipients. A limit a plugin enforces is a limit a plugin can drop, so there is nothing here
 * to configure and no counter to read.
 *
 * <h2>What it does not do</h2>
 *
 * <p>There is no read side: a plugin cannot list, count or mark an inbox, and cannot tell whether anyone
 * opened what it sent. Read state is the user's, and the one place it carries weight is an admin warning
 * (§17) — a plugin learning who read what would be an analytics surface nobody asked for.
 *
 * <p>Nothing here reaches email. The addresses on a {@code LinkedIdentity} were collected to establish
 * identity (§8.2); sending to them is a different purpose with consent, bounce handling and an
 * unsubscribe path behind it, and this is in-app only.
 *
 * @since 0.14.0
 */
public interface Notifier {

    /**
     * Sends one notification to each of the given users, and answers who actually got it.
     *
     * <p><strong>Ineligible recipients are left out, not rejected.</strong> An id this plugin holds no
     * {@link ScopeType#USER}-scope data for, or one belonging to an account erased or pseudonymised since
     * the plugin stored it (§12.8), is simply absent from the returned list — one stale participant does
     * not cost the other forty-nine their notification, which is what an all-or-nothing call would do
     * given how ordinary an erased account is.
     *
     * <p><strong>So the return value is the point.</strong> This is a write, and a write whose partial
     * failure is invisible degrades in silence: a plugin working from a stale participant list would
     * notify nobody and look exactly like one working perfectly. Compare what came back against what you
     * asked for when the difference means something — a subscriber list worth pruning, a metric worth
     * logging.
     *
     * <p>Duplicate ids are notified once. An empty input sends nothing and returns an empty list rather
     * than failing.
     *
     * @param userIds the users to notify; never {@code null}, and must not contain {@code null}
     * @param message what to tell them — a translation key and parameters, never a rendered sentence
     * @return the users actually notified, in no guaranteed order and with ineligible ids left out;
     *         never {@code null}
     * @throws NotificationException if the call as a whole was refused — the send cap is exhausted
     *         ({@link NotificationException.Reason#RATE_LIMITED}, worth retrying on a later tick), or the
     *         message or its link is one the host will not draw
     */
    List<UUID> send(Collection<UUID> userIds, NotifyMessage message) throws NotificationException;
}
