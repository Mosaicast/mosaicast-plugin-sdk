// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * A pending one-shot task from {@link PluginContext#runAfter(java.time.Duration, Runnable)}.
 *
 * @since 0.20.0
 */
public interface Scheduled {

    /**
     * Cancels the task if it has not started.
     *
     * <p>Idempotent: a second cancel, or a cancel after the task ran, changes nothing and answers
     * {@code false}. A task that has already started is not interrupted.
     *
     * @return {@code true} if this call stopped the task from running; {@code false} if it already ran,
     *         is running, or was cancelled before
     */
    boolean cancel();
}
