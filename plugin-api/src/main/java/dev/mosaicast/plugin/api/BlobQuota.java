// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * How much room this plugin has for files, as the host currently sees it (ARCHITECTURE §11).
 *
 * <p>The numbers are the <strong>effective</strong> ones, not what the manifest asked for: an operator caps
 * both, and a plugin that declared more than the install allows gets the install's answer. That is why this
 * is a runtime call rather than something read back from your own manifest — the only honest source is the
 * host.
 *
 * <p>Useful for telling a podcaster why an upload will fail <em>before</em> they pick a 200 MB file, and for
 * a scheduled task that prunes its own oldest files when it is close to the ceiling. Nothing enforces
 * anything here: {@link PluginBlobs#put} refuses over-quota writes regardless of whether you looked.
 *
 * @param usedBytes    what this plugin's files currently occupy
 * @param quotaBytes   the effective ceiling for the total
 * @param maxFileBytes the effective ceiling for a single file
 */
public record BlobQuota(long usedBytes, long quotaBytes, long maxFileBytes) {

    /**
     * The room left before the total ceiling.
     *
     * @return the remaining bytes, never negative
     */
    public long remainingBytes() {
        return Math.max(0L, quotaBytes - usedBytes);
    }
}
