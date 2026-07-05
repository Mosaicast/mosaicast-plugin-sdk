// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

// Test doubles for the plugin contract (ARCHITECTURE §13.5).
//
// A SEPARATE artifact: consumers pull it with `testImplementation` only, so the
// fakes never reach a plugin's production runtime. It re-exposes plugin-api as
// `api` because its public types implement the contract interfaces.

dependencies {
    api(project(":plugin-api"))
}
