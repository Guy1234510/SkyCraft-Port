# Install SkyCraft Port

This port is **100% vibe-coded**. There will be **no regular project updates**.

## Download your version

| Minecraft | Loader / Java | Release package |
|---|---|---|
| 1.20.1 | Forge 47.4.10 / Java 17 x64 | [Forge 68 with Native112](https://github.com/Guy1234510/SkyCraft-Port/releases/tag/forge-1.20.1-68-native112) |
| 1.21.1 | NeoForge 21.1.252 / Java 21 x64 | [NeoForge 89 with Native112](https://github.com/Guy1234510/SkyCraft-Port/releases/tag/neoforge-1.21.1-89-native112) |

Open your release page and expand **Assets**. Download the ZIP whose name begins
with `SkyCraft-Forge-1.20.1-68` or `SkyCraft-NeoForge-1.21.1-89`, together with
its `.zip.sha256` checksum. Extract the ZIP into a temporary folder.
GitHub's automatic **Source code** downloads are for developers. Compiled
JAR/DLL files and installation ZIPs are under **Assets**.

Both packages use **Native112 / protocol 17** and include `SkyCraft.dll`.
Skyrim, Minecraft, launchers, SKSE64, Address Library and optional mods must be
obtained separately. The native build targets Windows x64; the development
runtime is Skyrim Steam 1.7.104 with SKSE64 2.3.1. Other runtimes are unverified.

## Install

1. Close both games and back up your Skyrim saves and Minecraft worlds.
2. Set up a legitimate Minecraft Java profile with the loader and Java version
   in the table. Keep 1.20.1 and 1.21.1 profiles and worlds separate.
3. Copy the JAR from the extracted ZIP's `Minecraft/forge/` or
   `Minecraft/neoforge/` folder into that profile's `mods` folder. Keep **one
   active SkyCraft JAR** and disable previous versions. Do not extract JARs.
4. For **NeoForge only**, install [Forgified Fabric API](https://modrinth.com/mod/forgified-fabric-api/versions)
   version `0.116.15+2.3.5+1.21.1` for **NeoForge / Minecraft 1.21.1** separately.
   Forge does not require Fabric API or Connector; MixinExtras is bundled.
5. Install [SKSE64](https://skse.silverlock.org/) and
   [Address Library](https://www.nexusmods.com/skyrimspecialedition/mods/32444)
   matching your exact `SkyrimSE.exe` runtime.
6. Copy `Skyrim/Data/SKSE/Plugins/SkyCraft.dll` from the ZIP into Skyrim's
   `Data/SKSE/Plugins/` folder. Copy `SkyCraft.ini.example` to the same folder
   as **`SkyCraft.ini`**, or merge it into an existing configuration. Its
   `bStartWithSkyrim = 0` setting lets you start Minecraft in your own launcher.
7. Start the Minecraft profile, then launch Skyrim using `skse64_loader.exe`
   and load a backed-up test save made after Helgen. SkyCraft creates or loads
   its Minecraft world automatically.

See the **[detailed Windows guide](docs/installation/INSTALL-EN.md)** for mod
managers, configuration, controls, troubleshooting and uninstalling. Release
compilation and Mixin checks passed; the latest terrain-edge visual adjustment
still awaits gameplay confirmation.

## Verify the download

Run this in PowerShell, substituting your downloaded ZIP filename:

```powershell
Get-FileHash -Algorithm SHA256 .\SkyCraft-Forge-1.20.1-68-Native112.zip
```

Compare the hash with the accompanying `.zip.sha256` file. `MANIFEST.json` inside
the ZIP records the hashes of its JAR, DLL, configuration, guides and notices.

## Attribution and redistribution

Original SkyCraft: **chasmlol**, [MIT](LICENSE). Port code keeps that license.
Preserve `LICENSE`, `THIRD-PARTY-NOTICES.md`, `DISTRIBUTION.md` and `licenses/`.
The native plugin also incorporates CommonLibSSE-NG under GPL-3.0-or-later with
exceptions. Matching corresponding source is provided in the repository; see
[DISTRIBUTION.md](DISTRIBUTION.md) before redistributing the DLL.
