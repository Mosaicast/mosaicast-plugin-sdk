// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// The plugin contract itself. Pure interfaces + records, no implementation.
//
// Dependencies are deliberately minimal and exposed as `api` because they appear
// in the public method signatures consumers compile against:
//   - PF4J: the `ExtensionPoint` marker that PluginBackend extends. NO pf4j-spring,
//     no Spring — plugins stay framework-free (ARCHITECTURE §7.1 mitigation).
//   - Jackson databind: `JsonNode` is the return type of DocStore.query(...).

dependencies {
    api("org.pf4j:pf4j:3.12.0")
    api("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}
