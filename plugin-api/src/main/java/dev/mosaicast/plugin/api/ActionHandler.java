// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * The plugin code behind one declared action (ARCHITECTURE §7.9). Registered with
 * {@link Actions#handle(String, ActionHandler)}.
 *
 * <p>The host has already checked everything it can before this runs — the caller is signed in and at or
 * above the action's {@code callableBy}, the scope exists and is visible to them, the body is within
 * {@link Actions#MAX_BODY_BYTES}, and the rate limits allow it. What is left is the plugin's own rule: is
 * this move legal, is it this player's turn.
 *
 * @since 0.20.0
 */
@FunctionalInterface
public interface ActionHandler {

    /**
     * Handles one call.
     *
     * <p>Runs on the plugin's executor, possibly at the same time as other calls, timers and channel
     * callbacks — see {@link Actions} on concurrency.
     *
     * @param call the call, with its host-resolved caller and scope; never {@code null}
     * @return the answer, serialized to JSON as the response body; {@code null} answers 204 with no body
     * @throws ActionRejectedException to refuse the call with a code the plugin's UI can explain (422)
     */
    Object handle(ActionCall call);
}
