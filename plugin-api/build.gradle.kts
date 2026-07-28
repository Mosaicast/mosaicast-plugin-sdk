// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// The plugin contract itself. Pure interfaces + records, no implementation.
//
// Dependencies are deliberately minimal and exposed as `api` because they appear
// in the public method signatures consumers compile against:
//   - PF4J: the `ExtensionPoint` marker that PluginBackend extends. NO pf4j-spring,
//     no Spring — plugins stay framework-free (ARCHITECTURE §7.1 mitigation).
//   - Jackson databind: `JsonNode` is the value component of DocEntry, which
//     DocStore.query(...) returns. Jackson 3 (`tools.jackson`) since 0.4.0 — the
//     host runs Spring Boot 4, plugins load parent-first under PF4J, so the
//     contract must name the databind the host actually loads. See CHANGELOG 0.4.0.
//
// Versions track what Spring Boot 4.1 manages (jackson-bom 3.1.4) so a plugin built
// standalone against this SDK meets the same classes at runtime.

dependencies {
    api("org.pf4j:pf4j:3.15.0")
    api("tools.jackson.core:jackson-databind:3.1.4")
}
