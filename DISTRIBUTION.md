# Distribution

Original SkyCraft and this port's original code use the MIT license. Preserve
**Copyright (c) 2026 chasmlol** and the complete root `LICENSE`. Identify this
project as an independent modified port of [SkyCraft](https://github.com/chasmlol/SkyCraft).
Third-party code keeps its own licenses and authorship.

Include `THIRD-PARTY-NOTICES.md` and the complete `licenses/` directory with
distributed mod binaries. Keep notices already embedded in dependency JARs.
The maintained builds embed notices in the Forge and NeoForge JARs as well.

## Native DLL and corresponding source

The CommonLibSSE-NG build source uses **GPL-3.0-or-later**, with the exact Modding
and Linking Exceptions in `EXCEPTIONS.md`. Preserve `COPYING.txt`, those exceptions
and original attribution. The historical MIT file does not replace this GPL license.
The MIT license on SkyCraft's own source does not make the linked DLL MIT-only.

Each Native112 release provides `SkyCraft-Corresponding-Source-Native112.zip` in
the same release as the DLL. It contains the matching port source, CommonLib
build source, required OpenVR headers, hde64 source and the native library sources
used by the release, including vcpkg patches and build recipes. Its manifest
identifies the source commit, dependency revisions and checksums. Dependencies
fetched by build tools remain under their own license notices.

When redistributing the DLL, provide equivalent access to matching corresponding
source and applicable build scripts under the required terms. License notices
alone do not satisfy the corresponding-source requirement. Source archives omit
unused game address databases, build output and dependency runtime binaries.
Windows compiler/SDK and system libraries are acquired separately.

## Packaging boundaries

Ship original mod source, documentation and permitted open-source dependencies.
Exclude Minecraft/Skyrim files, extracted game assets, game decompilations, ROMs,
optional mod JARs/assets, saves, account files, tokens, logs and private settings.
Minecraft's [EULA](https://www.minecraft.net/en-us/eula) and
[usage guidelines](https://www.minecraft.net/en-us/usage-guidelines) apply
separately from the project's source license.

The repository has a new source-only history using a GitHub no-reply commit
identity. Compiled JARs/DLLs and corresponding-source ZIPs are Release assets;
build caches, older packages, debugging symbols and private working notes remain
outside the published source tree. Do not commit dependency downloads or game
installations to Git.

The port is **100% vibe-coded**, experimental and has **no regular update
schedule**. Original SkyCraft and dependency authorship is retained. Compilation
and static checks do not establish that every gameplay or visual defect is fixed.
