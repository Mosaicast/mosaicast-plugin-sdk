// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.List;

/**
 * The site's shared tag vocabulary, and this plugin's assignments against it (ARCHITECTURE §6.1).
 *
 * <p>Tags began as a feed-derived filter axis for episodes: {@code itunes:keywords} and
 * {@code <category>} become rows a visitor can filter by, and core's related-episode strategy uses shared
 * tags as its topical signal. There was no vocabulary and no plugin surface, so every plugin that wanted
 * tags invented a private one — and two things labelled {@code lore} on the same site had no relationship
 * to each other. This is the surface that makes them one thing.
 *
 * <p>Reachable through {@link PluginContext#tags()}, which is <strong>{@code null}</strong> unless the
 * manifest declares a {@code tags} block — the same shape and the same reasoning as {@link SchemaStore}
 * and {@link PluginBlobs}: what a plugin may touch is decided in the manifest and nowhere else.
 *
 * <pre>{@code
 * "tags": { "readsVocabulary": true, "writesEpisodes": false }
 * }</pre>
 *
 * <h2>Two kinds of assignment, which look alike and are not</h2>
 *
 * <p><strong>Your own subjects</strong> ({@link #tagSubject(String, String)}) are low risk: you name the
 * subject keys, they live in your plugin's namespace, and nobody else's rows are touched.
 * {@code data.writableBy} is the whole authorization story, as with blobs.
 *
 * <p><strong>An episode</strong> ({@link #tagEpisode(String, String)}) is a real capability. Tagging one
 * changes the shell's filter options <em>and</em> what core recommends beside it, which is why it needs
 * {@code tags.writesEpisodes} in the manifest: what a plugin may do stays readable off its manifest.
 * Without the declaration these two methods throw {@link UnsupportedOperationException} — the host refuses
 * rather than silently dropping the write.
 *
 * <h2>Provenance, and what a plugin may never do</h2>
 *
 * <p>Every assignment carries a source: {@code feed}, {@code manual} (a podcaster in admin), or
 * {@code plugin:<id>}. Every write through this interface is recorded as yours. Three consequences the
 * host enforces rather than merely asks for:
 *
 * <ul>
 *   <li><strong>You cannot remove another writer's assignment</strong>, the feed's included.
 *       {@link #untagEpisode(String, String)} removes the row your plugin wrote and nothing else; a tag
 *       another source also put there stays.</li>
 *   <li><strong>You cannot delete a tag from the vocabulary.</strong> It is shared, so removing it is not
 *       one plugin's call. Drop your own assignments; a tag stops existing when nothing carries it, or
 *       when an admin curates it away.</li>
 *   <li><strong>You cannot rename.</strong> A rename is a vocabulary-wide edit and belongs in admin.</li>
 * </ul>
 *
 * <h2>Subject keys</h2>
 *
 * <p>Core cannot know what a wiki page is, so your assignments are keyed by an opaque {@code subjectKey}
 * of your own choosing inside your own namespace — the property {@link SchemaStore} has for tables and
 * {@code ctx.route.navigate} has for URLs. Another plugin's subjects are not so much blocked as
 * unnameable. Use the same key a {@link SearchProvider} hit resolves to, so "this tag" and "this search
 * result" mean the same object rather than two coordinates for one thing.
 *
 * <p>Every read below is filtered to what the current user may see, as everywhere else in this contract:
 * {@link #episodesWith(String)} never returns an episode {@link FeedAccess#episodesIn(Scope)} would have
 * withheld.
 *
 * @since 0.9.0
 */
public interface Tags {

    /**
     * The whole site vocabulary: which tags exist here, with their labels and reach.
     *
     * <p>This is what lets an editor offer the site's real vocabulary instead of a free-text box — the
     * gap that produced a private {@code tags} column in every plugin that wanted them.
     *
     * @return every tag on this site, in the host's order (most used first); never {@code null}, possibly
     *         empty
     */
    List<TagInfo> all();

    /**
     * The episodes carrying a tag.
     *
     * @param tag any spelling of the tag; the host canonicalises it. Never {@code null}
     * @return the public episode slugs carrying it, filtered to what the current user may see; never
     *         {@code null}, possibly empty — an unknown tag is an empty list, not an error
     */
    List<String> episodesWith(String tag);

    /**
     * The tags on one episode, whatever put them there.
     *
     * @param episodeSlug the episode's public slug — the same id {@link FeedAccess#episodesIn(Scope)}
     *                    returns. Never {@code null}
     * @return the canonical keys on that episode; never {@code null}, possibly empty
     */
    List<String> tagsOn(String episodeSlug);

    /**
     * Tags that tend to appear alongside this one, best first.
     *
     * <p>Co-occurrence over the site's assignments — the same topical signal core's related-episode
     * strategy reads. It answers "what else on this site is about this", which is the question a private
     * per-plugin tag column can never answer.
     *
     * <p>The ranking is the host's and is <strong>not</strong> part of the contract: treat the order as
     * advice, not as a number to display or compare across calls.
     *
     * @param tag   any spelling of the tag; never {@code null}
     * @param limit the most entries to return; the host clamps an unreasonable value rather than failing
     * @return co-occurring tags, best first, excluding {@code tag} itself; never {@code null}, possibly
     *         empty
     */
    List<TagInfo> similarTo(String tag, int limit);

    /**
     * This plugin's own subjects carrying a tag.
     *
     * <p>Scoped to your plugin by construction — there is no way to ask about another's.
     *
     * @param tag any spelling of the tag; never {@code null}
     * @return your subject keys carrying it; never {@code null}, possibly empty
     */
    List<String> subjectsWith(String tag);

    /**
     * The tags on one of this plugin's subjects.
     *
     * @param subjectKey the subject key you assigned; never {@code null}
     * @return the canonical keys on it; never {@code null}, possibly empty — an unknown subject is an
     *         empty list, since a subject exists only because something tagged it
     */
    List<String> tagsOnSubject(String subjectKey);

    /**
     * Tags one of this plugin's own subjects, adding the tag to the vocabulary if it is new.
     *
     * <p>Idempotent: tagging what is already tagged changes nothing. The subject key is yours to invent
     * and needs no prior registration.
     *
     * @param subjectKey the subject in your namespace; never {@code null} or blank
     * @param tag        any spelling; the host canonicalises it and keeps your spelling as the display
     *                   label if the tag is new. Never {@code null} or blank
     */
    void tagSubject(String subjectKey, String tag);

    /**
     * Removes a tag from one of this plugin's subjects.
     *
     * <p>Idempotent. It removes an assignment, never the tag itself — see the class notes on what a
     * plugin may not do.
     *
     * @param subjectKey the subject in your namespace; never {@code null}
     * @param tag        any spelling of the tag; never {@code null}
     */
    void untagSubject(String subjectKey, String tag);

    /**
     * Tags an episode — <strong>a capability, not a convenience</strong>.
     *
     * <p>This changes what the shell offers as a filter and what core recommends next to the episode, so
     * it needs {@code "tags": { "writesEpisodes": true }} in the manifest. The assignment is recorded with
     * your plugin as its source, so an operator can see who put it there and revoke your writes wholesale.
     *
     * <p>Idempotent, and additive: an episode already carrying the tag from the feed keeps the feed's row
     * and gains yours.
     *
     * @param episodeSlug the episode's public slug; never {@code null}
     * @param tag         any spelling; never {@code null} or blank
     * @throws UnsupportedOperationException if the manifest does not declare {@code tags.writesEpisodes}
     */
    void tagEpisode(String episodeSlug, String tag);

    /**
     * Removes <strong>this plugin's</strong> tag assignment from an episode.
     *
     * <p>Only yours. If the feed or a podcaster also put this tag on the episode, it stays there and the
     * episode keeps carrying it — the source column makes that enforceable rather than merely
     * discouraged. Idempotent.
     *
     * @param episodeSlug the episode's public slug; never {@code null}
     * @param tag         any spelling of the tag; never {@code null}
     * @throws UnsupportedOperationException if the manifest does not declare {@code tags.writesEpisodes}
     */
    void untagEpisode(String episodeSlug, String tag);
}
