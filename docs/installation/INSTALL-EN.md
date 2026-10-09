# SkyCraft 1.20.1 and 1.21.1 — your first Windows installation

Current compiled builds: **1.20.1-68 / Forge** and **1.21.1-89 / NeoForge**, paired with **Native112 / protocol 17**.

SkyCraft puts Minecraft's player, items and blocks inside Skyrim's world. Both games must be installed and running on the same computer. Use a launcher of your choice with legitimate Minecraft authentication, and install the Skyrim plugin manually. No coding or compiling is required.

**Original author: chasmlol.** These Forge/NeoForge versions are community adaptations of the [original SkyCraft](https://github.com/chasmlol/SkyCraft). The project is experimental. Builds and audits passed, but the latest fixes still need gameplay confirmation.

## 1. Choose a version

An **instance** is a separate Minecraft installation with its own mods, settings and worlds. You may keep both versions, but run only one at a time.

| Component | SkyCraft 1.20.1 | SkyCraft 1.21.1 |
|---|---|---|
| Minecraft | Java Edition 1.20.1 | Java Edition 1.21.1 |
| Mod loader | Forge 47.4.10 | NeoForge 21.1.252 |
| Instance Java | Java 17, 64-bit | Java 21, 64-bit |
| SkyCraft mod | The JAR in the ZIP's Minecraft/forge folder | The JAR in the ZIP's Minecraft/neoforge folder |
| Additional library | No Fabric API required | Forgified Fabric API `0.116.15+2.3.5+1.21.1` |
| Skyrim plugin | `SkyCraft.dll`, protocol 17 | The same protocol 17 DLL |

Choose according to the other mods you intend to use. **Forge and NeoForge are different loaders.** A mod for the wrong loader or Minecraft version may prevent startup. Do not move a 1.21.1 world into 1.20.1.

## 2. Get the games and files ready

You need:

- 64-bit Windows and a computer able to keep both games running.
- An account that owns **Minecraft: Java Edition** and a legitimate **Skyrim Special Edition for PC** installation. Minecraft Bedrock does not work with this guide.
- A Minecraft launcher, SKSE64, Address Library and the matching SkyCraft files in the table.
- [7-Zip](https://www.7-zip.org/) to open `.7z` archives if needed.

**Check Skyrim first:** in Steam, open Library → Skyrim Special Edition → gear button → Manage → Browse local files. Right-click `SkyrimSE.exe`, select Properties → Details and write down the version.

The current Steam installation used by this project runs **Skyrim 1.7.104 with SKSE64 2.3.1**. For a different runtime, confirm that both the SkyCraft plugin and SKSE support it. Check the [official SKSE page](https://skse.silverlock.org/) for matching downloads.

Run normal Skyrim once. For your first SkyCraft session, have a save made after the Helgen introduction and back it up. Saves are normally in `Documents\My Games\Skyrim Special Edition\Saves`; Documents may be inside OneDrive on your PC. Copy that folder to a backup location.

**Getting SkyCraft:** open [SkyCraft Port Releases](https://github.com/Guy1234510/SkyCraft-Port/releases), select Forge 1.20.1 or NeoForge 1.21.1, expand **Assets** and download that version's compiled ZIP and `.zip.sha256` file. Extract it to a temporary folder. Start with [install.md](../../install.md), which links directly to both releases. The automatic Source code downloads and corresponding-source asset are for developers. Each compiled package includes the matching JAR, DLL, example configuration, English guides and license notices.

## 3. Set up your launcher and sign in

Use your preferred Minecraft launcher with legitimate Microsoft account authentication.
Sign in with an account that owns Minecraft: Java Edition. No launcher is bundled
or required by this guide.

## 4. Create your Minecraft profile

Create a separate game profile/directory for Minecraft 1.20.1 with Forge 47.4.10
or Minecraft 1.21.1 with NeoForge 21.1.252. If your launcher manages mod loaders,
select the matching loader there. Otherwise use the official Forge/NeoForge
installer and select its installed profile in your launcher.

Configure Java 17 for Minecraft 1.20.1, or Java 21 for Minecraft 1.21.1.
Launch the profile once, confirm the main menu appears, then close Minecraft.
Keep the game directories/worlds separate when maintaining both versions.

## 5. Put the mod in the correct folder

Open the game directory for your selected profile using your launcher. Find or create the `mods` folder inside it.

### If you chose Minecraft 1.20.1

Copy the SkyCraft Forge JAR from the table into `mods`:

```text
SkyCraft-1201\
└── mods\
    └── skycraft-forge-0.1.2-forge.1.20.1-68.jar
```

This port uses Forge directly. MixinExtras is already bundled. Fabric API, Forgified Fabric API and Connector are not requirements of this port.

### If you chose Minecraft 1.21.1

Copy **both** the SkyCraft NeoForge JAR and Forgified Fabric API JAR into `mods`:

```text
SkyCraft-1211\
└── mods\
    ├── skycraft-neoforge-0.1.2-neoforge.1.21.1-89.jar
    └── [Forgified Fabric API 0.116.15+2.3.5+1.21.1 .jar file]
```

Get the library from [Forgified Fabric API versions](https://modrinth.com/mod/forgified-fabric-api/versions), filtering for **1.21.1 and NeoForge**, then choose the version in the table. It is the API port for this loader; do not select the original Fabric API. [Project page](https://modrinth.com/mod/forgified-fabric-api).

**Do not extract JAR files.** Copy the entire file. A file ending in `-sources.jar` contains source code, not the playable mod. Keep only one active SkyCraft JAR in `mods`.

## 6. Install SKSE64 and Address Library in Skyrim

Keep both games closed while copying files.

1. Visit the [official SKSE page](https://skse.silverlock.org/) and select the download matching SkyrimSE.exe. The installation used by this project has **SKSE64 2.3.1 for Steam runtime 1.7.104**.
2. Open the downloaded archive with 7-Zip and enter the folder containing `skse64_loader.exe`.
3. Copy `skse64_loader.exe`, the `.dll` files in that folder and its `Data` folder into the Skyrim folder containing `SkyrimSE.exe`. Merge `Data` when Windows asks.
4. Visit [Address Library for SKSE Plugins](https://www.nexusmods.com/skyrimspecialedition/mods/32444). In **Files**, get the all-in-one package for your **exact Skyrim runtime**, checking that it includes the matching address database (versionlib-1-7-104-0.bin for 1.7.104).
5. Open the archive and copy its `SKSE` folder into Skyrim's `Data` folder. Database `.bin` files must end up in `Data\SKSE\Plugins`, as specified by the [Address Library author](https://www.nexusmods.com/skyrimspecialedition/mods/32444).

“Anniversary Edition” in this download refers to the executable family. Choose by your `SkyrimSE.exe` version, not just the store edition name.

Before adding SkyCraft, run `skse64_loader.exe`, confirm Skyrim starts, then close it. The `getskseversion` command in Skyrim's console can show the installed SKSE version.

## 7. Install SkyCraft's Skyrim component

Copy the **protocol 17 DLL** into:

```text
[Skyrim folder]\Data\SKSE\Plugins\SkyCraft.dll
```

Copy the package's `SkyCraft.ini.example` into that folder as **`SkyCraft.ini`**, or create the file with Notepad and paste:

```ini
[Minecraft]
bStartWithSkyrim = 0
sLauncher =
sArguments =

[Debug]
bDiagnostics = 0
```

Use **Save As**, select **All files**, and enter `SkyCraft.ini`. Make sure the name is not `SkyCraft.ini.txt`. In File Explorer, enable **View → Show → File name extensions** to check it.

This configuration means “I will start Minecraft myself in your chosen launcher.” It avoids starting the project's original Fabric bundle.

Keep the notices supplied with the mod, for example:

```text
[Skyrim folder]\Data\SKSE\Plugins\
├── SkyCraft.dll
├── SkyCraft.ini
└── SkyCraft\
    ├── LICENSE.txt
    └── THIRD-PARTY-NOTICES.md
```

If you already use Vortex or Mod Organizer 2, install the `SKSE` folder as a mod and enable/deploy it through that manager. Edit the managed mod's source files; manually copied files can be overwritten on the next deployment.

## 8. Start both games

1. Launch **only your chosen instance** in your chosen launcher and wait for Minecraft's main menu.
2. Start Skyrim with **`skse64_loader.exe`**. Steam's normal Play button does not replace this step.
3. Load your test save made after Helgen.
4. Wait for the connection. SkyCraft attempts to create or load its Minecraft world named `SkyCraft` automatically. Do not manually create an ordinary world for this first test.
5. Once connected, Minecraft's window may hide. Its HUD and blocks appear inside Skyrim; that is expected.

Try walking, opening the inventory with **E**, placing a block and dropping an item. Then try additional mods. Physics Staff, Cobblemon and other content require their own mods and dependencies; they are not automatically included with SkyCraft.

To switch between 1.20.1 and 1.21.1, close both games and start the other instance. Keep their worlds separate. When updating SkyCraft, check that the DLL and JAR still use the same protocol.

## 9. Useful first-session controls

| Key | Action |
|---|---|
| WASD / Space / Shift | Minecraft movement / jump / crouch |
| E | Minecraft inventory |
| T | Minecraft chat |
| F5 | Switch Minecraft camera mode |
| M | Forwarded to Minecraft; available to Minecraft mods |
| G | Interact with Skyrim doors, characters and objects |
| O | Minecraft menu |
| Esc | Skyrim menu; closes a Minecraft screen if one is open |
| Ctrl + M | Skyrim map |
| J / H | Skyrim journal / wait |

**Plain M is forwarded to Minecraft.** G, H, O, J, Esc and F9 have special integration functions; account for these when configuring other mods' shortcuts.

## 10. If something goes wrong

| Symptom | What to check |
|---|---|
| Minecraft will not start | Instance Java version, loader and dependencies. |
| Missing Fabric API modules in 1.21.1 | Install Forgified Fabric API for NeoForge 1.21.1. |
| SKSE reports an incompatible version | Check `SkyrimSE.exe` and select matching SKSE. |
| Missing address database | Check Address Library and your runtime's `.bin` in `Data\SKSE\Plugins`. |
| No Minecraft HUD | Start Skyrim through SKSE; check DLL, INI and that a save is loaded. |
| Protocol mismatch | Update DLL and JAR together; this table uses protocol 17. |
| DLL error such as `VCRUNTIME140` | Install/repair Microsoft's [Visual C++ Redistributable x64](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist). |
| Duplicate mod error | Keep one active SkyCraft JAR in `mods`. |

For help, provide the exact error and both logs:

- Minecraft: instance folder → `logs\latest.log`.
- Skyrim: `Documents\My Games\Skyrim Special Edition\SKSE\SkyCraft.log`.

Do not delete your worlds or saves to troubleshoot these errors. Back up before changing files.

## 11. Attribution and usage license

The project's [LICENSE](../../LICENSE) identifies **Copyright (c) 2026 chasmlol** and the **MIT** license. You may use, copy, modify and redistribute software covered by it while retaining the copyright and license notices in copies. The software comes without warranty. Read the [MIT text](https://opensource.org/license/mit) and full license file, not only this summary.

When sharing an adaptation, retain credit to the original author, identify it as an adaptation, and preserve the [third-party notices](../../THIRD-PARTY-NOTICES.md). SkyCraft's license does not replace licenses for SKSE, Address Library, launchers, libraries or other mods.

Minecraft and Skyrim remain subject to their own licenses. Share mod files you are entitled to distribute; do not share game copies, authentication files or an entire modified Minecraft client. The [Minecraft EULA](https://www.minecraft.net/en-us/eula) distinguishes distributing mods from distributing the modified game.

SkyCraft is a fan project, unaffiliated with and not endorsed by Mojang, Microsoft, Bethesda or ZeniMax. This tutorial does not require paying the author to use the MIT license or impose additional SkyCraft restrictions.


## 12. Update or uninstall

Before updating, close both games and back up saves, worlds and `SkyCraft.ini`.
Replace the chosen SkyCraft JAR and the matching DLL together. Keep one active
SkyCraft JAR in each profile. Preserve existing configuration when merging new
options, and read the release notes for protocol and dependency changes.

To uninstall, close both games, disable/remove the SkyCraft JAR from the chosen
profile and remove/disable `SkyCraft.dll` through your Skyrim mod manager or
plugin folder. Keep your backed-up Minecraft world and Skyrim saves. External
loaders and dependencies may be used by other mods; manage those separately.

This port is **100% vibe-coded** and has **no regular update schedule**.
The latest terrain-edge adjustment awaits gameplay confirmation.
