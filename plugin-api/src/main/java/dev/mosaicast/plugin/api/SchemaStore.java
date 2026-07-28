// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The relational store handed to plugins that declare a schema in their manifest (ARCHITECTURE §7.6).
 *
 * <p>Most plugins use only the generic {@link DocStore} and never see a {@code SchemaStore}
 * ({@link PluginContext#schema()} returns {@code null} for them). Reach for this one when you actually
 * need relational features — full-text search, revisions, backlinks — and can live with declaring your
 * shape up front. The wiki is the v1 reference; the mechanism is open to any plugin.
 *
 * <p>You declare entities and their fields in the manifest; the <strong>platform</strong> provisions
 * dedicated, namespaced tables ({@code plugin_<id>_*}) through its managed migration runner and drops
 * them when an admin purges the plugin. <strong>The plugin never writes DDL</strong>, and never names a
 * table:
 *
 * <pre>{@code
 * "storage": { "schema": { "page": {
 *     "slug": "string:indexed:unique", "title": "string",
 *     "markdown": "text:fulltext", "updatedAt": "timestamp:indexed" } } }
 * }</pre>
 *
 * <p>Every method here addresses a <strong>declared entity by name</strong>, and declared fields by name.
 * That is the whole scoping story: there is no SQL string to sanitize and no table identifier to get
 * wrong, so a plugin cannot reach another plugin's tables or core's — not because it is checked, but
 * because it cannot be expressed. The host resolves the entity to its provisioned table, validates every
 * field name against your declaration, and binds every value as a JDBC parameter.
 *
 * <p><strong>Rows map to your own types.</strong> Pass a Java record whose component names match the
 * declared field names — the same convention {@link DocStore#get(Scope, String, Class)} and
 * {@link PluginConfig#get(String, Class)} already use. Each entity also carries a platform-assigned
 * {@code id} of type {@code long}; include an {@code id} component to read it.
 *
 * <pre>{@code
 * record Page(long id, String slug, String title, String markdown, Instant updatedAt) {}
 *
 * SchemaStore schema = ctx.schema();
 *
 * long id = schema.insert("page", Map.of(
 *         "slug", "getting-started", "title", "Getting started",
 *         "markdown", "# Hello", "updatedAt", Instant.now()));
 *
 * Optional<Page> byId = schema.find("page", id, Page.class);
 * List<Page> bySlug   = schema.select("page",
 *         Criteria.where("slug", Criteria.Op.EQ, "getting-started"), Page.class);
 * List<Page> hits     = schema.search("page", "markdown", "lighthouse",
 *         Criteria.all().limit(20), Page.class);
 * }</pre>
 *
 * <p><strong>Every method rejects an entity or field your manifest does not declare</strong> with
 * {@link IllegalArgumentException}. That is a programming error, not a runtime condition — it means the
 * manifest and the code disagree, and it fails the same way against the test kit as against the host.
 *
 * <p>Errors reaching the database (constraint violations, connection loss) surface as unchecked
 * exceptions. A failure inside {@link PluginBackend#register(PluginContext)} or an
 * {@link PluginContext#onSchedule(java.time.Duration, Runnable)} task is isolated by the host and
 * disables the plugin rather than the site (§7.8).
 */
public interface SchemaStore {

    /**
     * The table-name namespace the platform reserved for this plugin's schema, e.g. {@code plugin_wiki_}.
     *
     * <p>All of the plugin's provisioned tables share this prefix. Exposed for diagnostics and logging —
     * you do not need it in order to query, since every method here addresses entities by their declared
     * name.
     *
     * @return the namespace prefix; never {@code null}
     */
    String namespace();

    /**
     * The entity names this plugin declared in its manifest.
     *
     * <p>These are the only values the {@code entity} parameter of every other method accepts.
     *
     * @return the declared entity names; never {@code null}, and empty only for a manifest that declares
     *         no schema — in which case {@link PluginContext#schema()} would have been {@code null}
     */
    Set<String> entities();

    /**
     * Reads one row by its platform-assigned id.
     *
     * @param entity the declared entity name; never {@code null}
     * @param id     the row's {@code id}, as returned by {@link #insert(String, Map)}
     * @param type   the type to map the row into; never {@code null}
     * @param <T>    the row type
     * @return the row, or {@link Optional#empty()} if no row has that id
     * @throws IllegalArgumentException if {@code entity} is not declared
     */
    <T> Optional<T> find(String entity, long id, Class<T> type);

    /**
     * Reads the rows matching a criteria.
     *
     * @param entity   the declared entity name; never {@code null}
     * @param criteria which rows, in what order, how many — {@link Criteria#all()} for everything; never
     *                 {@code null}
     * @param type     the type to map each row into; never {@code null}
     * @param <T>      the row type
     * @return the matching rows, ordered as the criteria asks (unspecified order if it does not ask);
     *         never {@code null}, empty when nothing matches
     * @throws IllegalArgumentException if {@code entity} is not declared, or the criteria names a field
     *                                  that is not
     */
    <T> List<T> select(String entity, Criteria criteria, Class<T> type);

    /**
     * Full-text search over one field, narrowed by a criteria.
     *
     * <p>{@code field} must be declared {@code :fulltext} in the manifest — this runs against the index
     * the platform provisioned for it, which is the reason to declare a schema at all. Results come back
     * best match first; {@code criteria} filters and caps them, and any
     * {@link Criteria#orderBy(String, Criteria.Direction)} it carries replaces that relevance order.
     *
     * <p>For a plain substring or prefix match on an ordinary field, use {@link Criteria.Op#LIKE} with
     * {@link #select(String, Criteria, Class)} instead.
     *
     * @param entity   the declared entity name; never {@code null}
     * @param field    the field to search; never {@code null}, and declared {@code :fulltext}
     * @param text     the search text, interpreted by the platform's text search; never {@code null}. An
     *                 empty string matches nothing rather than everything
     * @param criteria additional filtering, ordering and limits; {@link Criteria#all()} for none; never
     *                 {@code null}
     * @param type     the type to map each row into; never {@code null}
     * @param <T>      the row type
     * @return the matching rows, best match first unless the criteria orders them; never {@code null}
     * @throws IllegalArgumentException if {@code entity} is not declared, {@code field} is not declared
     *                                  or is not a full-text field, or the criteria names an undeclared
     *                                  field
     */
    <T> List<T> search(String entity, String field, String text, Criteria criteria, Class<T> type);

    /**
     * Counts the rows matching a criteria, without materializing them.
     *
     * <p>Ordering, limit and offset on the criteria are ignored.
     *
     * @param entity   the declared entity name; never {@code null}
     * @param criteria which rows to count — {@link Criteria#all()} for the whole entity; never
     *                 {@code null}
     * @return the number of matching rows
     * @throws IllegalArgumentException if {@code entity} is not declared, or the criteria names a field
     *                                  that is not
     */
    long count(String entity, Criteria criteria);

    /**
     * Inserts one row.
     *
     * @param entity the declared entity name; never {@code null}
     * @param values the field values, keyed by declared field name; never {@code null}. Declared fields
     *               left out are stored as {@code NULL}; the {@code id} is assigned by the platform and
     *               must not be supplied
     * @return the platform-assigned {@code id} of the new row
     * @throws IllegalArgumentException if {@code entity} is not declared, {@code values} names a field
     *                                  that is not, or it supplies {@code id}
     */
    long insert(String entity, Map<String, Object> values);

    /**
     * Updates one row by id, writing only the fields present in {@code values}.
     *
     * @param entity the declared entity name; never {@code null}
     * @param id     the row's {@code id}
     * @param values the fields to write, keyed by declared field name; never {@code null} or empty.
     *               Fields left out keep their current value
     * @return the number of rows written: {@code 1}, or {@code 0} if no row has that id
     * @throws IllegalArgumentException if {@code entity} is not declared, {@code values} is empty or
     *                                  names a field that is not, or it supplies {@code id}
     */
    int update(String entity, long id, Map<String, Object> values);

    /**
     * Deletes the rows matching a criteria.
     *
     * <p>Ordering, limit and offset on the criteria are ignored — a delete is not a paged read. Passing
     * {@link Criteria#all()} empties the entity; that is allowed, and deliberate.
     *
     * @param entity   the declared entity name; never {@code null}
     * @param criteria which rows to delete; never {@code null}
     * @return the number of rows deleted
     * @throws IllegalArgumentException if {@code entity} is not declared, or the criteria names a field
     *                                  that is not
     */
    int delete(String entity, Criteria criteria);
}
