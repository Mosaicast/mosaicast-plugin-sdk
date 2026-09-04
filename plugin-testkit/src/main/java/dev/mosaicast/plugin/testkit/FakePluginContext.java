// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.testkit;

import dev.mosaicast.plugin.api.DocStore;
import dev.mosaicast.plugin.api.FeedAccess;
import dev.mosaicast.plugin.api.Locales;
import dev.mosaicast.plugin.api.Notifier;
import dev.mosaicast.plugin.api.PluginBlobs;
import dev.mosaicast.plugin.api.PluginConfig;
import dev.mosaicast.plugin.api.PluginContext;
import dev.mosaicast.plugin.api.SchemaStore;
import dev.mosaicast.plugin.api.Tags;
import dev.mosaicast.plugin.api.Translation;
import dev.mosaicast.plugin.api.Users;
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
    private Users users;
    private Notifier notifier;
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
     * Wires a {@link Users} into this context, standing in for a manifest that declares an
     * {@code identity} block.
     *
     * <p>A chaining mutator, for the reason {@link #withTags(Tags)} spells out. The default is
     * {@code null}, which is what a plugin that never declared {@code identity} sees from the host — so a
     * backend written against a directory that is always there fails here before it fails in production.
     *
     * @param users the user directory, or {@code null} to go back to a plugin that declares none
     * @return this instance, for chaining
     * @since 0.13.0
     */
    public FakePluginContext withUsers(Users users) {
        this.users = users;
        return this;
    }

    /**
     * The user directory this context is wired to, or {@code null} when none was supplied — the same
     * {@code null} a plugin that declares no {@code identity} block sees from the host.
     *
     * @return the user directory, or {@code null}
     * @since 0.13.0
     */
    @Override
    public Users users() {
        return users;
    }

    /**
     * Wires a {@link Notifier} into this context, standing in for a manifest that declares a
     * {@code notifications} block.
     *
     * <p>A chaining mutator, for the reason {@link #withTags(Tags)} spells out. The default is
     * {@code null} — what a plugin that never declared {@code notifications} sees from the host.
     *
     * <p>{@link FakeNotifier} decides who may be notified by reading a doc store's user partitions, as
     * the host does, so hand it <strong>this context's own</strong> store or it will answer about
     * partitions the plugin under test never wrote:
     *
     * <pre>{@code
     * var ctx = new FakePluginContext();
     * ctx.withNotifier(new FakeNotifier(ctx.store()));
     * }</pre>
     *
     * @param notifier the notification surface, or {@code null} to go back to a plugin that declares none
     * @return this instance, for chaining
     * @since 0.14.0
     */
    public FakePluginContext withNotifier(Notifier notifier) {
        this.notifier = notifier;
        return this;
    }

    /**
     * The notification surface this context is wired to, or {@code null} when none was supplied — the
     * same {@code null} a plugin that declares no {@code notifications} block sees from the host.
     *
     * @return the notifier, or {@code null}
     * @since 0.14.0
     */
    @Override
    public Notifier notifier() {
        return notifier;
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
     * Wires a {@link Translation} into this context, standing in for a plugin that declared
     * {@code external.kinds: ["translation"]} on a site whose admin selected a provider.
     *
     * <p>A chaining mutator, same as {@link #withTags(Tags)}. The default is {@code null}, which since
     * 0.11.0 stands in for <strong>either</strong> of the two reasons the host produces one: a manifest
     * that never declared the kind, or an operator who configured no provider. This fake does not tell
     * them apart because {@link PluginContext#translation()} does not either — a double that distinguished
     * them would let a test assert something no plugin can observe in production. See
     * {@link FakeTranslation}, and {@link FakeTranslation#unavailable()} for the third state: declared,
     * configured, and gone by the time you call.
     *
     * @param translation the translation surface, or {@code null} to go back to a plugin with no
     *                    declaration, a site with no provider, or both
     * @return this instance, for chaining
     * @since 0.10.0
     */
    public FakePluginContext withTranslation(Translation translation) {
        this.translation = translation;
        return this;
    }

    /**
     * The translation surface this context is wired to, or {@code null} when none was supplied — the same
     * {@code null} a plugin sees when it declared no {@code external.translation} kind, when the site has
     * no provider, or both.
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
