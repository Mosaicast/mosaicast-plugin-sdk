// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Names one channel of this plugin: a scope plus a name the plugin gives meaning to — {@code game:42},
 * {@code inbox} (ARCHITECTURE §7.9).
 *
 * <p>Channels belong to the plugin, so two plugins' {@code inbox} on the same scope are different
 * channels. The scope is checked by the host, like an action's, before the
 * {@link ChannelAuthorizer authorizer} sees a subscribe.
 *
 * @param scope the scope; never {@code null}, never {@link ScopeType#USER}
 * @param name  the name, matching {@link Channels#NAME_PATTERN}; never {@code null}
 * @since 0.20.0
 */
public record ChannelRef(Scope scope, String name) {

    private static final Pattern NAME = Pattern.compile(Channels.NAME_PATTERN);

    /**
     * Validates both components.
     *
     * @throws NullPointerException     if either is {@code null}
     * @throws IllegalArgumentException if the scope is {@link ScopeType#USER} — a channel is something
     *                                  several people can share, and a user partition is not — or the name
     *                                  does not match {@link Channels#NAME_PATTERN}
     */
    public ChannelRef {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(name, "name");
        if (scope.type() == ScopeType.USER) {
            throw new IllegalArgumentException(
                    "a channel cannot live in the USER scope — use publishTo on a shared channel to reach one user");
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "channel name '" + name + "' does not match " + Channels.NAME_PATTERN);
        }
    }
}
