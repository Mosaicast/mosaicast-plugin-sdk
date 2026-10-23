// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * One event a {@link FakeChannels fake channel} published, as JSON — what a browser's {@code onEvent} would
 * receive, plus who it was for.
 *
 * @param event     the event name
 * @param data      the payload after the JSON round trip the host makes; a JSON {@code null} node for none
 * @param at        when it was published, on the context's {@link FakePluginContext#now() clock}
 * @param recipient the one user it was for ({@code publishTo}), or {@code null} for a {@code publish} to all
 * @since 0.20.0
 */
public record ChannelEvent(String event, JsonNode data, Instant at, UUID recipient) {}
