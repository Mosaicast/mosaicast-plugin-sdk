// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.Locales;
import dev.mosaicast.plugin.api.PluginBlobs;
import dev.mosaicast.plugin.api.PluginConfig;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.SchemaStore;
import dev.mosaicast.plugin.api.Tags;
import dev.mosaicast.plugin.api.Translation;
import java.time.Duration;
import java.util.Objects;

/**
 * A fully in-memory {@link PluginContext} for testing a plugin backend without core or a database
 * (ARCHITECTURE §13.5).
 *
 * <p>Typical flow: build the fake context, call {@code plugin.register(ctx)}, then assert against the
 * doc store. {@link #onSchedule(Duration, Runnable)} runs the task <strong>synchronously</strong> and
 * immediately, so scheduled work is exercised deterministically within the test.
 *
 * <p>By default {@link #schema()} returns {@code null} (no schema declared, like most plugins); pass a
 * {@link SchemaStore} to the full constructor to test a schema-declaring plugin. Not thread-safe.
 */
public final class FakePluginContext implements PluginContext {

    /** The logger name the host uses for a plugin; this fake stands in for a concrete plugin id. */
    private static final String LOGGER_NAME = "plugin.test";

    private final InMemoryDocStore store;
    private final SchemaStore schema;
    private final PluginBlobs blobs;
    private final PluginConfig config;
    private final FeedAccess feeds;
    private final RecordingLogger logger = new RecordingLogger(LOGGER_NAME);
    private Tags tags;
    private Locales locales = FakeLocales.englishOnly();
    private Translation translation;
    private int scheduledCount;

    /** Creates a context with an empty doc store, empty config, empty feeds, no schema and no blobs. */
    public FakePluginContext() {
        this(new InMemoryDocStore(), new MapPluginConfig(), new FakeFeedAccess(java.util.Map.of()), null);
    }

    /**
     * Creates a context wired to the given doubles, for a plugin that stores no files.
     *
     * <p>Kept as its own constructor rather than folded into the one below: this signature is what every
     * existing plugin test calls, and a fifth positional parameter would break all of them to add something
     * almost none of them want. That is the mistake 0.7.1 was spent undoing on the TypeScript side.
     *
     * @param store  the doc store; never {@code null}
     * @param config the config; never {@code null}
     * @param feeds  the feed access; never {@code null}
     * @param schema the schema store, or {@code null} for a plugin that declares no schema
     */
    public FakePluginContext(InMemoryDocStore store, PluginConfig config, FeedAccess feeds, SchemaStore schema) {
        this(store, config, feeds, schema, null);
    }

    /**
     * Creates a context wired to the given doubles, including file storage.
     *
     * @param store  the doc store; never {@code null}
     * @param config the config; never {@code null}
     * @param feeds  the feed access; never {@code null}
     * @param schema the schema store, or {@code null} for a plugin that declares no schema
     * @param blobs  the blob store, or {@code null} for a plugin that declares no {@code blobs} block
     * @since 0.8.0
     */
    public FakePluginContext(InMemoryDocStore store, PluginConfig config, FeedAccess feeds, SchemaStore schema,
                             PluginBlobs blobs) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.feeds = Objects.requireNonNull(feeds, "feeds");
        this.schema = schema;
        this.blobs = blobs;
    }

    /**
     * The in-memory doc store this context is wired to.
     *
     * <p>The return type is narrowed from {@link DocStore} on purpose, as with {@link #logger()}: seeding
     * per-user data needs {@link InMemoryDocStore#asUser(java.util.UUID)}, which the contract type does not
     * have — a real backend cannot write into a user's partition, only a test can.
     *
     * @return the doc store; never {@code null}
     */
    @Override
    public InMemoryDocStore store() {
        return store;
    }

    @Override
    public SchemaStore schema() {
        return schema;
    }

    /**
     * The blob store this context is wired to, or {@code null} when none was supplied — the same
     * {@code null} a plugin that declares no {@code blobs} block sees from the host.
     *
     * @return the blob store, or {@code null}
     */
    @Override
    public PluginBlobs blobs() {
        return blobs;
    }

    /**
     * Wires a {@link Tags} into this context, standing in for a manifest that declares a {@code tags}
     * block.
     *
     * <p><strong>A chaining mutator rather than a sixth constructor parameter.</strong> The constructor
     * list is already at five, and every added positional parameter breaks every existing plugin test to
     * supply something almost none of them want — the mistake 0.7.1 was spent undoing on the TypeScript
     * side, and the reason 0.8.0 added an overload instead. {@link FakeFeedAccess#withDisplay} is the
     * same shape.
     *
     * @param tags the tag surface, or {@code null} to go back to a plugin that declares none
     * @return this instance, for chaining
     * @since 0.9.0
     */
    public FakePluginContext withTags(Tags tags) {
        this.tags = tags;
        return this;
    }

    /**
     * The tag surface this context is wired to, or {@code null} when none was supplied — the same
     * {@code null} a plugin that declares no {@code tags} block sees from the host.
     *
     * @return the tag surface, or {@code null}
     * @since 0.9.0
     */
    @Override
    public Tags tags() {
        return tags;
    }

    /**
     * Wires a {@link Locales} into this context, for a plugin whose behaviour depends on which languages a
     * site has.
     *
     * <p>A chaining mutator, for the reason {@link #withTags(Tags)} spells out. Unlike {@code tags} this is
     * never {@code null}: a site always has at least English, so the default is
     * {@link FakeLocales#englishOnly()} rather than nothing.
     *
     * @param locales the language registry; {@code null} restores the English-only default
     * @return this instance, for chaining
     * @since 0.10.0
     */
    public FakePluginContext withLocales(Locales locales) {
        this.locales = locales == null ? FakeLocales.englishOnly() : locales;
        return this;
    }

    @Override
    public Locales locales() {
        return locales;
    }

    /**
     * Wires a {@link Translation} into this context, standing in for a site whose admin selected a provider.
     *
     * <p>A chaining mutator, same as {@link #withTags(Tags)}. The default is {@code null} — the same
     * {@code null} a plugin sees on a site that configured no provider, which is every site until an
     * operator chooses one. See {@link FakeTranslation}.
     *
     * @param translation the translation surface, or {@code null} to go back to a site with none
     * @return this instance, for chaining
     * @since 0.10.0
     */
    public FakePluginContext withTranslation(Translation translation) {
        this.translation = translation;
        return this;
    }

    /**
     * The translation surface this context is wired to, or {@code null} when none was supplied.
     *
     * @return the translation surface, or {@code null}
     * @since 0.10.0
     */
    @Override
    public Translation translation() {
        return translation;
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public FeedAccess feeds() {
        return feeds;
    }

    /**
     * A {@link RecordingLogger} named {@code plugin.test}, so a test can assert on what the plugin logged
     * rather than watching it scroll past.
     *
     * <p>The return type is narrowed from {@link org.slf4j.Logger} on purpose: {@code ctx.logger().events()}
     * needs no cast.
     *
     * @return the recording logger; never {@code null}
     */
    @Override
    public RecordingLogger logger() {
        return logger;
    }

    /**
     * Runs the task synchronously and immediately (no real scheduling), so tests observe its effects
     * without waiting.
     *
     * @param every ignored beyond a positivity check; the task is run once, now
     * @param task  the task to run; never {@code null}
     */
    @Override
    public void onSchedule(Duration every, Runnable task) {
        Objects.requireNonNull(every, "every");
        Objects.requireNonNull(task, "task");
        if (every.isNegative() || every.isZero()) {
            throw new IllegalArgumentException("schedule interval must be positive: " + every);
        }
        scheduledCount++;
        task.run();
    }

    /**
     * The number of times {@link #onSchedule(Duration, Runnable)} has been called — handy for asserting
     * a plugin registered its scheduled work.
     *
     * @return the count of scheduled tasks
     */
    public int scheduledCount() {
        return scheduledCount;
    }
}
