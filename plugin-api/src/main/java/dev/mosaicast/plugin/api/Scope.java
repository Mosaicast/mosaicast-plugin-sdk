// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;

/**
 * Addresses a level of the site plus the concrete entity at that level (ARCHITECTURE §7.4).
 *
 * <p>A scope is a {@link ScopeType} and the id of the entity at that level. It is the addressing unit
 * for both {@link DocStore} entries and {@link FeedAccess} lookups.
 *
 * <p>Two of the five types are <strong>singletons</strong> whose id this record pins for you, exactly as
 * the host does: {@link ScopeType#SITE} to {@link #SITE_ID} (there is one site) and {@link ScopeType#USER}
 * to {@link #SELF_ID} (a caller has one partition — their own). For {@code USER} that is a security
 * property, not a convenience: no plugin code, on either side of the wire, can construct a scope naming
 * somebody else.
 *
 * @param type the level; never {@code null}
 * @param id   the id of the entity at that level (e.g. the {@code EpisodeRef} ID for
 *             {@link ScopeType#EPISODE}); never {@code null}. For {@link ScopeType#SITE} it is always
 *             {@link #SITE_ID} and for {@link ScopeType#USER} always {@link #SELF_ID} — see {@link #site()}
 *             and {@link #user()}.
 */
public record Scope(ScopeType type, String id) {

    /**
     * The id of the one and only {@link ScopeType#SITE} scope: {@value}.
     *
     * <p>There is a single site, so its scope is a singleton. The host normalizes every {@code SITE}
     * scope to this id, and it is what appears in the frontend's data path — {@code …/data/site/main/{key}}
     * addresses exactly the document a backend writes with {@code store().put(Scope.site(), key, value)}.
     */
    public static final String SITE_ID = "main";

    /**
     * The id of every {@link ScopeType#USER} scope: {@value} — the sentinel standing for "the calling
     * user".
     *
     * <p>The host resolves it server-side from the session; it is never a user id a plugin supplies. On
     * the frontend the path is literally {@code …/data/user/me/{key}}, and the host answers <strong>400
     * to any other {@code USER} id</strong> rather than substituting silently — code that reads as though
     * it addresses a specific user must not quietly address the caller instead. An anonymous call to a
     * {@code USER} path is a <strong>401</strong>: no session, no partition.
     *
     * @since 0.5.0
     */
    public static final String SELF_ID = "me";

    /**
     * Canonical constructor: validates that neither component is {@code null} and normalizes the
     * singleton scopes to their fixed ids, exactly as the host does — {@code new Scope(SITE, anything)}
     * is {@code "main"} and {@code new Scope(USER, anything)} is {@code "me"}, so every site scope is
     * {@code equals} to every other site scope and every user scope to every other user scope.
     *
     * <p>Normalizing {@code USER} here is deliberate: it means there is no expression in this API that
     * names another person's partition, so the IDOR cannot be written in the first place.
     */
    public Scope {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type == ScopeType.SITE) {
            id = SITE_ID;
        } else if (type == ScopeType.USER) {
            id = SELF_ID;
        }
    }

    /**
     * The {@link ScopeType#SITE} scope — the singleton, with id {@link #SITE_ID}.
     *
     * @return the site scope
     */
    public static Scope site() {
        return new Scope(ScopeType.SITE, SITE_ID);
    }

    /**
     * The {@link ScopeType#USER} scope — the calling user's own private partition, with id
     * {@link #SELF_ID}.
     *
     * <p><strong>Per-user data belongs here, not in the key.</strong> A key is client-supplied, so the
     * older convention {@code mark:<userId>:cell} under an episode scope was an access-control decision
     * the host could not enforce: any caller past the plugin's read floor could address someone else's
     * key directly. In this scope the partition a caller reaches is the only one they can name.
     *
     * <p>The partition is flat — one per user, not one per user and entity — so the entity goes in the
     * key: {@code mark:<episodeSlug>:cell}. Scope ids are slugs, not UUIDs.
     *
     * <p>There is deliberately <strong>no {@code user(String)} overload</strong>: a plugin has no
     * business naming a user, and a compile error is a better answer than an argument that would be
     * silently ignored.
     *
     * <p>This scope is unusable from a backend thread — there is no calling user to resolve — so every
     * {@link DocStore} method throws {@link UnsupportedOperationException} for it. Aggregate with
     * {@link DocStore#queryAcrossUsers(String)}.
     *
     * @return the calling user's scope
     * @since 0.5.0
     */
    public static Scope user() {
        return new Scope(ScopeType.USER, SELF_ID);
    }

    /** Convenience factory for a {@link ScopeType#FEED} scope. */
    public static Scope feed(String id) {
        return new Scope(ScopeType.FEED, id);
    }

    /** Convenience factory for a {@link ScopeType#SEASON} scope. */
    public static Scope season(String id) {
        return new Scope(ScopeType.SEASON, id);
    }

    /** Convenience factory for a {@link ScopeType#EPISODE} scope. */
    public static Scope episode(String id) {
        return new Scope(ScopeType.EPISODE, id);
    }
}
