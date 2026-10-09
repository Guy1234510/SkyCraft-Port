# Publication audit — 2026-10-09

Scope: the reviewed source snapshot in this repository and its versioned releases.
The private development workspace, its previous Git history and ignored files
are not part of this publication.

- Original mod code is MIT-licensed; full third-party notices and dependency
  licenses are retained. Licensed open-source dependencies are present.
- No Minecraft or Skyrim game binaries/assets, game decompilation dumps, optional
  mod JARs, launchers, saves or account files are included in the source snapshot.
- No maintainer home/build paths, personal Git email/name, API tokens or private
  keys were detected in the reviewed payloads. Commit metadata uses the requested
  GitHub handle and its no-reply address. Public dependency-author attribution is retained.
- Non-code documentation is English. Portuguese prose checks passed.
- Reviewed source payload: 3092 files, 11,644,877 bytes.
  Largest payload: `skse/extern/CommonLibSSE-NG/include/RE/Offsets_VTABLE.h` (1,271,459 bytes). No source file reaches 100 MB.
- Generated builds, caches, debug symbols, old ZIPs, screenshots, private working
  notes, machine utilities and the old Git history are excluded from upload.
- Publication lint found no failures. Six warnings refer only to function-address
  labels in comments of the unchanged, licensed CommonLib headers. These were
  inspected; they are integration annotations, not copied game function bodies.
- Releases pair Forge 1.20.1 build 68 and NeoForge 1.21.1 build 89 with Native112 /
  protocol 17. The latest terrain-edge adjustment awaits gameplay confirmation.
- Matching native library source, license notices and build recipes accompany
  the DLL as a corresponding-source Release asset.

These checks report the files actually reviewed; they do not describe the private
development workspace as a clean distributable source directory.
