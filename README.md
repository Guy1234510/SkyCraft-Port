# SkyCraft Port — Forge 1.20.1 and NeoForge 1.21.1

Play Minecraft inside Skyrim using a Minecraft mod and a Skyrim SKSE plugin.
This is an independent modified port of SkyCraft by chasmlol. Both games must be
obtained separately through legitimate sources. The project is experimental;
back up your saves and use a test profile.

| Minecraft | Loader | Java | Current build | Download |
|---|---|---|---|---|
| 1.20.1 | Forge 47.4.10 | 17 | Forge 68 / Native112 | [Forge release](https://github.com/Guy1234510/SkyCraft-Port/releases/tag/forge-1.20.1-68-native112) |
| 1.21.1 | NeoForge 21.1.252 | 21 | NeoForge 89 / Native112 | [NeoForge release](https://github.com/Guy1234510/SkyCraft-Port/releases/tag/neoforge-1.21.1-89-native112) |

The maintained clients and the Skyrim plugin currently share protocol **17**.
Install the matching Minecraft JAR and native DLL together. Compilation and
Mixin audits are separate from gameplay validation; reported visual and
movement problems still require testing in game.

## Development and maintenance

**This port is 100% vibe-coded. There will be no regular project updates,
guaranteed support or release schedule.** The port changes were developed with
AI coding agents, including Codex. Original SkyCraft and third-party libraries
retain their own authorship and licenses. Treat this as an experimental project.

## Install

- Start with **[install.md](install.md)** for downloads, requirements and installation.
- [Detailed Windows installation guide](docs/installation/INSTALL-EN.md)
- [Build from source](docs/BUILDING.md)
- [All releases](https://github.com/Guy1234510/SkyCraft-Port/releases)

Release ZIPs contain the chosen Minecraft JAR, the matching native Skyrim plugin,
license notices and installation guides. Download the Forge or NeoForge package
for your profile. Matching native source and dependency build sources are provided in the
repository, including the `native-dependencies/` directory.

Compiled files are distributed as Release assets. This source repository excludes
build output, dependency caches, private notes, logs, saves, account files and
machine settings. External loaders and optional mods are acquired separately.

The original Fabric module is retained as legacy source and supplies shared
resources and a Gradle wrapper. The installation guides above cover the two
maintained ports; the ZIP does not include a launcher or either game.

## License and attribution

The original [SkyCraft by chasmlol](https://github.com/chasmlol/SkyCraft) is
[MIT-licensed](LICENSE). This port's original code uses the same MIT license and
preserves `Copyright (c) 2026 chasmlol`. Vendored dependencies keep their own
licenses; CommonLibSSE-NG uses GPL-3.0-or-later with its documented exceptions.
The linked native DLL is distributed with matching source and dependency sources.
See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md),
[DISTRIBUTION.md](DISTRIBUTION.md) and [licenses/](licenses/).

Not affiliated with Mojang, Microsoft, Bethesda or ZeniMax.
