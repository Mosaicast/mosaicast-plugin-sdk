// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.plugin.api;

/**
 * The relational store handed to plugins that declare a schema in their manifest (ARCHITECTURE §7.6).
 *
 * <p>Most plugins use only the generic {@link DocStore} and never see a {@code SchemaStore}
 * ({@link PluginContext#schema()} returns {@code null} for them). Relational plugins (the wiki is the
 * v1 reference) instead declare entities + indexed/full-text fields in the manifest; the
 * <strong>platform</strong> provisions dedicated, namespaced tables ({@code plugin_<id>_*}) via its
 * managed migration runner and cleans them up on purge. <strong>The plugin never writes DDL.</strong>
 *
 * <p>This handle exposes the plugin's reserved table namespace so declared entities can be addressed;
 * the concrete, typed accessors are provisioned from the schema declaration by the host.
 */
public interface SchemaStore {

    /**
     * The table-name namespace the platform reserved for this plugin's schema, e.g. {@code plugin_wiki_}.
     *
     * <p>All of the plugin's provisioned tables share this prefix. The plugin owns only this namespace
     * and never touches tables outside it.
     *
     * @return the namespace prefix; never {@code null}
     */
    String namespace();
}
