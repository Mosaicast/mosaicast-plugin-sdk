// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.TagInfo;
import dev.mosaicast.plugin.api.Tags;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * An in-memory {@link Tags} for testing a plugin against a known vocabulary (ARCHITECTURE §13.5).
 *
 * <p><strong>It refuses what the host refuses.</strong> Episode writes throw without
 * {@link #withEpisodeWrites()}, and {@link #untagEpisode(String, String)} removes only this plugin's own
 * assignment — a tag the feed also put there survives, exactly as the host's {@code source} column makes
 * it survive. A double that accepted everything would let a plugin meet its first refusal in production.
 *
 * <p>Canonicalisation is applied the way the host applies it (trim, collapse internal whitespace,
 * casefold), so a test that writes {@code "Maritime "} and reads {@code "maritime"} passes here for the
 * same reason it passes against core.
 *
 * <p>Seed the feed's side with {@link #withFeedTag(String, String)} — that is what makes the
 * "can I delete somebody else's tag" case testable at all. Not thread-safe.
 *
 * @since 0.9.0
 */
public final class FakeTags implements Tags {

    /** The source recorded for every write through this double, mirroring the host's {@code plugin:<id>}. */
    private static final String PLUGIN_SOURCE = "plugin:test";

    /** The source the reconciler uses for what it read out of the feed. */
    private static final String FEED_SOURCE = "feed";

    /** One tag on one episode, and who put it there — the host's {@code episode_tag} row. */
    private record EpisodeTag(String episodeSlug, String tag, String source) {}

    /** Display labels, kept from first use, keyed by canonical tag. */
    private final Map<String, String> labels = new LinkedHashMap<>();

    private final Set<EpisodeTag> episodeTags = new HashSet<>();

    /** This plugin's subjects → the canonical tags on them. */
    private final Map<String, Set<String>> subjectTags = new LinkedHashMap<>();

    private boolean episodeWritesAllowed;

    /**
     * Allows {@link #tagEpisode(String, String)} and {@link #untagEpisode(String, String)}, standing in
     * for {@code "tags": { "writesEpisodes": true }} in the manifest.
     *
     * <p>Off by default on purpose: a plugin that has only ever met an enabled double never exercises the
     * branch where the host says no.
     *
     * @return this instance, for chaining
     */
    public FakeTags withEpisodeWrites() {
        this.episodeWritesAllowed = true;
        return this;
    }

    /**
     * Seeds a tag the <em>feed</em> put on an episode — a row this plugin may read but must not remove.
     *
     * @param episodeSlug the episode's public slug; never {@code null}
     * @param tag         any spelling; canonicalised as the host would canonicalise it
     * @return this instance, for chaining
     */
    public FakeTags withFeedTag(String episodeSlug, String tag) {
        String canonical = canonical(tag);
        remember(canonical, tag);
        episodeTags.add(new EpisodeTag(Objects.requireNonNull(episodeSlug, "episodeSlug"), canonical, FEED_SOURCE));
        return this;
    }

    /**
     * The canonical key for a tag, applying the host's normalisation rule.
     *
     * <p>Exposed so a test can assert on the same key the double stores, rather than re-implementing
     * (and slightly mis-implementing) the rule.
     *
     * @param tag any spelling; never {@code null}
     * @return the canonical key
     */
    public static String canonical(String tag) {
        Objects.requireNonNull(tag, "tag");
        String collapsed = tag.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (collapsed.isEmpty()) {
            throw new IllegalArgumentException("tag must not be blank");
        }
        return collapsed;
    }

    private void remember(String canonical, String spelling) {
        labels.putIfAbsent(canonical, spelling.trim().replaceAll("\\s+", " "));
    }

    @Override
    public List<TagInfo> all() {
        List<TagInfo> vocabulary = new ArrayList<>();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            TagInfo info = info(entry.getKey());
            // A tag stops existing when nothing carries it — the host does not keep empty vocabulary rows.
            if (info.episodes() > 0 || info.subjects() > 0) {
                vocabulary.add(info);
            }
        }
        vocabulary.sort(Comparator.comparingInt((TagInfo t) -> -(t.episodes() + t.subjects()))
                .thenComparing(TagInfo::tag));
        return List.copyOf(vocabulary);
    }

    private TagInfo info(String canonical) {
        long episodes = episodeTags.stream()
                .filter(row -> row.tag().equals(canonical))
                .map(EpisodeTag::episodeSlug)
                .distinct()
                .count();
        long subjects = subjectTags.entrySet().stream()
                .filter(entry -> entry.getValue().contains(canonical))
                .count();
        return new TagInfo(canonical, labels.getOrDefault(canonical, canonical), (int) episodes, (int) subjects);
    }

    @Override
    public List<String> episodesWith(String tag) {
        String canonical = canonical(tag);
        return episodeTags.stream()
                .filter(row -> row.tag().equals(canonical))
                .map(EpisodeTag::episodeSlug)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public List<String> tagsOn(String episodeSlug) {
        Objects.requireNonNull(episodeSlug, "episodeSlug");
        return episodeTags.stream()
                .filter(row -> row.episodeSlug().equals(episodeSlug))
                .map(EpisodeTag::tag)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public List<TagInfo> similarTo(String tag, int limit) {
        String canonical = canonical(tag);
        Map<String, Integer> shared = new HashMap<>();
        for (String slug : episodesWith(canonical)) {
            for (String other : tagsOn(slug)) {
                if (!other.equals(canonical)) {
                    shared.merge(other, 1, Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Set<String>> subject : subjectTags.entrySet()) {
            if (subject.getValue().contains(canonical)) {
                for (String other : subject.getValue()) {
                    if (!other.equals(canonical)) {
                        shared.merge(other, 1, Integer::sum);
                    }
                }
            }
        }
        return shared.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(Math.max(0, limit))
                .map(entry -> info(entry.getKey()))
                .toList();
    }

    @Override
    public List<String> subjectsWith(String tag) {
        String canonical = canonical(tag);
        return subjectTags.entrySet().stream()
                .filter(entry -> entry.getValue().contains(canonical))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    @Override
    public List<String> tagsOnSubject(String subjectKey) {
        Objects.requireNonNull(subjectKey, "subjectKey");
        return List.copyOf(subjectTags.getOrDefault(subjectKey, Set.of()));
    }

    @Override
    public void tagSubject(String subjectKey, String tag) {
        Objects.requireNonNull(subjectKey, "subjectKey");
        if (subjectKey.isBlank()) {
            throw new IllegalArgumentException("subjectKey must not be blank");
        }
        String canonical = canonical(tag);
        remember(canonical, tag);
        subjectTags.computeIfAbsent(subjectKey, key -> new TreeSet<>()).add(canonical);
    }

    @Override
    public void untagSubject(String subjectKey, String tag) {
        Objects.requireNonNull(subjectKey, "subjectKey");
        Set<String> tags = subjectTags.get(subjectKey);
        if (tags != null) {
            tags.remove(canonical(tag));
            if (tags.isEmpty()) {
                subjectTags.remove(subjectKey);
            }
        }
    }

    @Override
    public void tagEpisode(String episodeSlug, String tag) {
        requireEpisodeWrites();
        Objects.requireNonNull(episodeSlug, "episodeSlug");
        String canonical = canonical(tag);
        remember(canonical, tag);
        episodeTags.add(new EpisodeTag(episodeSlug, canonical, PLUGIN_SOURCE));
    }

    @Override
    public void untagEpisode(String episodeSlug, String tag) {
        requireEpisodeWrites();
        Objects.requireNonNull(episodeSlug, "episodeSlug");
        // Only this plugin's own row: a tag the feed also put here stays, and the episode keeps carrying it.
        episodeTags.remove(new EpisodeTag(episodeSlug, canonical(tag), PLUGIN_SOURCE));
    }

    private void requireEpisodeWrites() {
        if (!episodeWritesAllowed) {
            throw new UnsupportedOperationException(
                    "this plugin's manifest does not declare tags.writesEpisodes; "
                            + "call FakeTags.withEpisodeWrites() to test the granted case");
        }
    }
}
