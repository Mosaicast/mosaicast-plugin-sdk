// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// Test doubles for the plugin contract (ARCHITECTURE §13.5).
//
// A SEPARATE artifact: consumers pull it with `testImplementation` only, so the
// fakes never reach a plugin's production runtime. It re-exposes plugin-api as
// `api` because its public types implement the contract interfaces.
//
// Jackson is declared explicitly rather than inherited: `ObjectMapper` appears in
// InMemoryDocStore's public constructor, so it belongs to this module's own API.

dependencies {
    api(project(":plugin-api"))
    api("tools.jackson.core:jackson-databind:3.1.4")
}
