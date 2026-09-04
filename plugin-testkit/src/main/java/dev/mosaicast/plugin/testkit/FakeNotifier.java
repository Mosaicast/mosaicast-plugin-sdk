// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.NotificationException;
import dev.mosaicast.plugin.api.Notifier;
import dev.mosaicast.plugin.api.NotifyMessage;
import dev.mosaicast.plugin.api.OwnedDocEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An in-memory {@link Notifier} for testing what a plugin sends, and to whom (ARCHITECTURE §13.5).
 *
 * <p><strong>Eligibility comes from the doc store, not from a list you seed.</strong> The host will only
 * deliver to users the plugin already holds {@link dev.mosaicast.plugin.api.ScopeType#USER}-scope data
 * for, so this double asks the same {@link InMemoryDocStore} the context is wired to, exactly as the host
 * asks the same partitions {@code queryAcrossUsers} reads. A hand-seeded allow-list would be a second
 * copy of that rule, free to drift from it — and the drift would always fall the same way, with the
 * double permitting what production refuses.
 *
 * <p>So the way to make a user notifiable is to give them a row, which is also the way they become a
 * participant:
 *
 * <pre>{@code
 * var store = new InMemoryDocStore();
 * store.asUser(ana).put(Scope.user(), "mark:s2e04:b3", true);   // Ana played
 *
 * var ctx = new FakePluginContext(store, new MapPluginConfig(), new FakeFeedAccess(Map.of()), null)
 *         .withNotifier(new FakeNotifier(store));
 *
 * List<UUID> told = ctx.notifier().send(List.of(ana, stranger), new NotifyMessage("bingo.resolved"));
 * // told == [ana] — the stranger has no rows, so the host would not have reached them either
 * }</pre>
 *
 * <p><strong>It models the partial send.</strong> An ineligible recipient is left out of the result
 * rather than failing the call, which is the behaviour a permissive double would hide — and hiding it
 * means a plugin working from a stale participant list looks exactly like one working perfectly.
 *
 * <p>The send cap is off by default and armed with {@link #withPerUserPerDay(int)}, so the branch where
 * the host says no gets exercised on purpose rather than never. Not thread-safe.
 *
 * @since 0.14.0
 */
public final class FakeNotifier implements Notifier {

    /** One delivered notification: who got it, and what it said. */
    public record Delivery(UUID userId, NotifyMessage message) {}

    private final InMemoryDocStore store;

    /** Every delivery, in send order — the assertion surface. */
    private final List<Delivery> delivered = new ArrayList<>();

    /** How many this recipient has been sent, against {@link #perUserPerDay}. */
    private final Map<UUID, Integer> sentPerUser = new HashMap<>();

    /** Zero means uncapped; the host always has one, but a test only opts in when that is the point. */
    private int perUserPerDay;

    /**
     * Creates a notifier whose eligibility is read from the given store.
     *
     * <p>Pass the same {@link InMemoryDocStore} the {@link FakePluginContext} is wired to, or the double
     * answers about partitions the plugin under test never wrote.
     *
     * @param store the doc store whose user partitions decide who may be notified; never {@code null}
     */
    public FakeNotifier(InMemoryDocStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Arms the per-recipient send cap, standing in for the operator's ceiling over what the manifest's
     * {@code notifications.perUserPerDay} asked for.
     *
     * <p>Off by default: a plugin that has only ever met an uncapped double never exercises the branch
     * where a send is refused, and that branch is the one a scheduled sender has to get right.
     *
     * @param limit how many notifications one recipient may receive before the send is refused; must be
     *              positive
     * @return this instance, for chaining
     */
    public FakeNotifier withPerUserPerDay(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("perUserPerDay must be positive: " + limit);
        }
        this.perUserPerDay = limit;
        return this;
    }

    /**
     * Every notification this double has delivered, in order.
     *
     * @return the deliveries; never {@code null}
     */
    public List<Delivery> delivered() {
        return List.copyOf(delivered);
    }

    /**
     * What one recipient was told, in order — the common assertion, without filtering
     * {@link #delivered()} by hand.
     *
     * @param userId the recipient to look at
     * @return the messages that user received; never {@code null}, possibly empty
     */
    public List<NotifyMessage> messagesFor(UUID userId) {
        return delivered.stream().filter(d -> d.userId().equals(userId)).map(Delivery::message).toList();
    }

    /**
     * The users this plugin is currently allowed to notify: everyone with at least one document in their
     * partition.
     *
     * <p>Exposed so a test can assert on the rule itself rather than infer it from a send.
     *
     * @return the eligible user ids; never {@code null}
     */
    public Set<UUID> notifiable() {
        Set<UUID> eligible = new LinkedHashSet<>();
        for (OwnedDocEntry entry : store.queryAcrossUsers("")) {
            eligible.add(entry.userId());
        }
        return eligible;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Ineligible recipients are left out of the result rather than failing the call; the cap, when
     * armed, refuses the whole send.
     */
    @Override
    public List<UUID> send(Collection<UUID> userIds, NotifyMessage message) throws NotificationException {
        Objects.requireNonNull(userIds, "userIds");
        Objects.requireNonNull(message, "message");
        for (UUID id : userIds) {
            Objects.requireNonNull(id, "userIds must not contain null");
        }

        Set<UUID> eligible = notifiable();
        // Deduplicated, like the host: naming the same participant twice sends one notification, and must
        // not count twice against their cap either.
        Set<UUID> recipients = new LinkedHashSet<>(userIds);

        if (perUserPerDay > 0) {
            for (UUID id : recipients) {
                // Only an eligible recipient can consume a send, since an ineligible one is never
                // delivered to — checking before filtering would refuse a call the host would have let
                // through.
                if (eligible.contains(id) && sentPerUser.getOrDefault(id, 0) >= perUserPerDay) {
                    throw new NotificationException(
                            NotificationException.Reason.RATE_LIMITED,
                            "over the per-user-per-day cap of " + perUserPerDay + " for user " + id);
                }
            }
        }

        List<UUID> told = new ArrayList<>();
        for (UUID id : recipients) {
            if (!eligible.contains(id)) {
                continue;
            }
            delivered.add(new Delivery(id, message));
            sentPerUser.merge(id, 1, Integer::sum);
            told.add(id);
        }
        return List.copyOf(told);
    }
}
