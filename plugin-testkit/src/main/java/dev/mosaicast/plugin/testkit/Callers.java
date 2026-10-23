// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.Caller;
import dev.mosaicast.plugin.api.Role;
import java.util.UUID;

/**
 * Ready-made {@link Caller}s for calling a {@link FakeActions fake action} or subscribing to a
 * {@link FakeChannels fake channel}.
 *
 * <p>The no-argument forms mint a fresh user each time, so two {@code Callers.fan()} are two different
 * people; pass a UUID to be the same person twice.
 *
 * @since 0.20.0
 */
public final class Callers {

    private Callers() {}

    /**
     * A new signed-in listener.
     *
     * @return a {@link Role#FAN} caller with a random id
     */
    public static Caller fan() {
        return fan(UUID.randomUUID());
    }

    /**
     * A signed-in listener with a given id.
     *
     * @param userId the id; never {@code null}
     * @return a {@link Role#FAN} caller
     */
    public static Caller fan(UUID userId) {
        return new Caller(userId, Role.FAN);
    }

    /**
     * A new podcaster.
     *
     * @return a {@link Role#PODCASTER} caller with a random id
     */
    public static Caller podcaster() {
        return podcaster(UUID.randomUUID());
    }

    /**
     * A podcaster with a given id.
     *
     * @param userId the id; never {@code null}
     * @return a {@link Role#PODCASTER} caller
     */
    public static Caller podcaster(UUID userId) {
        return new Caller(userId, Role.PODCASTER);
    }

    /**
     * A new admin.
     *
     * @return a {@link Role#ADMIN} caller with a random id
     */
    public static Caller admin() {
        return admin(UUID.randomUUID());
    }

    /**
     * An admin with a given id.
     *
     * @param userId the id; never {@code null}
     * @return a {@link Role#ADMIN} caller
     */
    public static Caller admin(UUID userId) {
        return new Caller(userId, Role.ADMIN);
    }

    /** A role's rank on the manifest scale, where {@code anonymous} would be 0. */
    static int rank(Role role) {
        return switch (role) {
            case FAN -> 1;
            case PODCASTER -> 2;
            case ADMIN -> 3;
        };
    }
}
