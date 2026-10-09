# Third-party notices

SkyCraft Port's original code is MIT-licensed. Preserve the original SkyCraft
notice, **Copyright (c) 2026 chasmlol**, and [LICENSE](LICENSE). Dependencies keep
their own licenses; the MIT license does not relicense third-party material.

## Source included in this repository

| Component | License | Source / notice |
|---|---|---|
| Original SkyCraft 0.1.2 | MIT | [chasmlol/SkyCraft](https://github.com/chasmlol/SkyCraft), root `LICENSE` |
| CommonLibSSE-NG 10.0.0, commit `d61bca4de789428aa7d98a770b1323ddf1bb855c` | GPL-3.0-or-later with Modding and Linking Exceptions | [CommonLibVR](https://github.com/alandtse/CommonLibVR), `skse/extern/CommonLibSSE-NG/COPYING.txt` and `EXCEPTIONS.md` |
| OpenVR headers, commit `60eb187801956ad277f1cae6680e3a410ee0873b` | BSD-3-Clause | [ValveSoftware/openvr](https://github.com/ValveSoftware/openvr), `licenses/OpenVR-BSD-3-Clause.txt` |
| Gradle Wrapper scripts and JARs | Apache-2.0 | [gradle/gradle](https://github.com/gradle/gradle), `licenses/Gradle-APACHE-2.0.txt`; scripts retain their copyright headers |

CommonLib's historical MIT notice is retained for attribution. Its current
top-level license is GPL, with the exact exceptions preserved in `licenses/`.
The vendored snapshot excludes unrelated examples, Address Library test databases,
CI/agent configuration and binary dependencies. OpenVR headers are needed by
CommonLib even when this port disables Skyrim VR; no OpenVR runtime binary is bundled.

## Native plugin dependencies

| Component / release build version | License | Original source | Full notice |
|---|---|---|---|
| DirectXMath 2026-06-12 | MIT | [Microsoft/DirectXMath](https://github.com/microsoft/DirectXMath) | `licenses/directxmath.txt` |
| DirectXTK 2026-05-07 | MIT | [Microsoft/DirectXTK](https://github.com/microsoft/DirectXTK) | `licenses/directxtk.txt` |
| fmt 12.2.0 | MIT | [fmtlib/fmt](https://github.com/fmtlib/fmt) | `licenses/fmt.txt` |
| nlohmann-json 3.12.0 | MIT | [nlohmann/json](https://github.com/nlohmann/json) | `licenses/nlohmann-json.txt` |
| rapidcsv 9.07 | MIT | [d99kris/rapidcsv](https://github.com/d99kris/rapidcsv) | `licenses/rapidcsv.txt` |
| SimpleIni 4.27 | MIT | [brofield/simpleini](https://github.com/brofield/simpleini) | `licenses/SimpleIni.txt` |
| spdlog 1.17.0 | MIT | [gabime/spdlog](https://github.com/gabime/spdlog) | `licenses/spdlog.txt` |
| toml11 4.4.0 | MIT | [ToruNiina/toml11](https://github.com/ToruNiina/toml11) | `licenses/toml11.txt` |
| xbyak 7.28 | BSD-3-Clause | [herumi/xbyak](https://github.com/herumi/xbyak) | `licenses/xbyak.txt` |
| hde64, from MinHook v1.3.4 | BSD-2-Clause | [TsudaKageyu/minhook](https://github.com/TsudaKageyu/minhook) | `licenses/CommonLibSSE-NG-LICENSE-hde64.txt` |

Matching native dependency sources and build recipes are supplied in the
corresponding-source asset accompanying each DLL release. Windows SDK, compiler
and system runtime components are external tools/system libraries, not bundled.

## Java ports

| Component | License | Distribution / source |
|---|---|---|
| MixinExtras 0.5.3 | MIT | Nested in the Forge JAR; [LlamaLad7/MixinExtras](https://github.com/LlamaLad7/MixinExtras); full notice in `licenses/mixinextras-forge-0.5.3-1.txt` and its own JAR |
| JNA and JNA Platform 5.13.0 / 5.14.0 | LGPL-2.1-or-later OR Apache-2.0 | External dependency; this project chooses Apache-2.0; [java-native-access/jna](https://github.com/java-native-access/jna); matching notices in `licenses/jna-platform-*.txt` |
| JSpecify 1.0.0 | Apache-2.0 | External dependency; [jspecify/jspecify](https://github.com/jspecify/jspecify); `licenses/jspecify-1.0.0.txt` |
| Forgified Fabric API `0.116.15+2.3.5+1.21.1` | Apache-2.0 | External NeoForge dependency; [Sinytra/ForgifiedFabricAPI](https://github.com/Sinytra/ForgifiedFabricAPI) |

## External loaders and build tools

These components are fetched separately and are not redistributed in the port's
source tree or compiled release ZIPs, except for the Gradle Wrapper noted above.

| Component | License | License source |
|---|---|---|
| Forge / NeoForge | LGPL-2.1 | [Forge](https://github.com/MinecraftForge/MinecraftForge/blob/1.20.x/LICENSE.txt), [NeoForge](https://github.com/neoforged/NeoForge/blob/1.21.1/LICENSE.txt) |
| ModDevGradle 2.0.107 | LGPL-2.1 | [ModDevGradle](https://github.com/neoforged/ModDevGradle/blob/main/LICENSE) |
| Fabric Loader / Fabric API | Apache-2.0 | [Fabric Loader](https://github.com/FabricMC/fabric-loader/blob/master/LICENSE), [Fabric API](https://github.com/FabricMC/fabric/blob/1.21.1/LICENSE) |
| Fabric Loom | MIT | [Fabric Loom](https://github.com/FabricMC/fabric-loom/blob/dev/1.11/LICENSE) |
| SpongePowered Mixin | MIT | [Mixin](https://github.com/SpongePowered/Mixin/blob/master/LICENSE.txt) |
| ASM | BSD-3-Clause | [ASM](https://gitlab.ow2.org/asm/asm/-/blob/master/LICENSE.txt) |
| vcpkg | MIT | [vcpkg](https://github.com/microsoft/vcpkg/blob/master/LICENSE.txt) |

The retained Fabric module is legacy source; it supplies shared resources and a
wrapper to the maintained ports. No Fabric launcher bundle is distributed here.

## Optional integrations and game files

Aeronautics, Sable, Create, Cobblemon, Twilight Forest and Alex's Caves are optional
external mods. Compatibility code calls their APIs or uses reflection; their JARs,
native libraries, textures, models, sounds and other assets are not bundled.
Create Aeronautics 1.3.2's installed notice licenses code under MIT and assets
under All Rights Reserved. Obtain it from its [project page](https://modrinth.com/mod/create-aeronautics);
this notice does not permit redistributing its assets.

Minecraft and Skyrim are proprietary games obtained separately. Their files,
extracted assets, saves and account data are excluded. SKSE64, Address Library,
Java and launchers are also obtained separately under their respective terms.
This project is unaffiliated with Mojang, Microsoft, Bethesda or ZeniMax.
