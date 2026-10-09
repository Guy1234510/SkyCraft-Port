# Native112 corresponding source

This archive accompanies the Forge 1.20.1 and NeoForge 1.21.1 compiled releases.
The port source and CommonLib build source match the source_commit in
CORRESPONDING-SOURCE-MANIFEST.json. Required OpenVR headers and hde64 source are
included. native-dependencies/ contains the actual patched native library source
used by the release, original licenses, vcpkg build recipes and build helpers.
Unbuilt dependency tests, examples, documentation and binary fixtures are omitted.

Follow docs/BUILDING.md to build the port with the pinned vcpkg revision.
The sources in native-dependencies/ allow inspection and modification of the
libraries independently of upstream downloads. Their source subdirectories are
the preferred build inputs used by vcpkg; set optional tests/examples OFF when
building these trimmed libraries. fmt already has its vcpkg backport applied;
the original patch is supplied separately for provenance.

Skyrim, Minecraft, optional mod assets, account data, logs, debug symbols and
compiled dependency binaries are not included. Windows SDK/compiler and system
libraries are external. Preserve LICENSE, licenses/, THIRD-PARTY-NOTICES.md,
DISTRIBUTION.md and dependency notices when redistributing. CommonLibSSE-NG uses
GPL-3.0-or-later with the exact exceptions supplied with its source.
