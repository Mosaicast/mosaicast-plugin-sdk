// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.UUID;

/**
 * Who a user id belongs to, in the only terms a plugin is given (ARCHITECTURE §8.8).
 *
 * <p>What a plugin holds is a {@link UUID}: {@link OwnedDocEntry#userId()} hands one over, and
 * {@link DocStore#queryAcrossUsers(String)} hands over a list of them. This is what turns one into
 * something renderable — a name and a picture, and deliberately nothing else.
 *
 * <p><strong>Never email, provider or external id.</strong> Those stay server-side; the stable login key
 * is {@code (provider, external_id)} (§8.2) and publishing it is exactly what social login was meant to
 * avoid. This record is the whole of what a plugin may learn about somebody else.
 *
 * <p><strong>Store the {@link #id()}, resolve at render. Never persist {@link #displayName()}.</strong>
 * A display name copied into a plugin's own store outlives the rename meant to shed it and the erasure
 * meant to end it — core provisioned those tables without ever learning which column holds a person, so
 * §12.8 cannot reach it. The host cannot enforce this; the rule lives in this doc comment and in
 * {@link Users#resolve(java.util.Collection)}.
 *
 * @param id          the user's stable UUID — the only part safe to persist; never {@code null}
 * @param displayName what a reader sees; the user may change it, so treat it as presentation, never as
 *                    identity and never as a key; never {@code null}
 * @param avatarUrl   a host-relative path, always {@code /api/users/{id}/avatar} (§8.7); always
 *                    populated — every user has an avatar, generated from the UUID when there is no
 *                    provider picture, so there is no null case and no fallback to implement; never
 *                    {@code null}
 * @param role        the user's role (§8.5); never {@code null}
 * @since 0.13.0
 */
public record UserRef(UUID id, String displayName, String avatarUrl, Role role) {}
