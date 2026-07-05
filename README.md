# mosaicast-plugin-sdk

> Versioned plugin contract SDK (Java plugin-api + @mosaicast/plugin-sdk TS) plus a test kit.

Part of **[Mosaicast](https://github.com/mosaicast)** — an extensible website platform for podcasts. Status: **v1 in development**.

## What is this?
See `docs/ARCHITECTURE.md` for the big picture and `docs/BRIEF.md` for this repo's scope.

## Build & test
```bash
./gradlew build publishToMavenLocal   # Java
npm run build                              # TypeScript
./gradlew test  &&  npm test
```

## Using it
- Java: `./gradlew publishToMavenLocal` or composite build `includeBuild("../mosaicast-plugin-sdk")`.
- TS: `npm link @mosaicast/plugin-sdk`. Sources/Javadoc JARs + `.d.ts`/TSDoc provide IDE docs.
- **The source of truth for signatures** is this built SDK + its docs.

## Contributing
Contributions welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md). In short: `git commit -s` (DCO, required), SPDX header in new files, add tests.

## License
**Apache License 2.0** — see [`LICENSE`](LICENSE). Header per source file:
```
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors
```

## Name & trademark
"Mosaicast" and the logo denote the official project. Please rename forks.
