# Build the Skyrim SKSE plugin

The current native release is **Native112 / protocol 17**, paired with Forge
1.20.1 build 68 and NeoForge 1.21.1 build 89. The development runtime is Skyrim
Steam 1.7.104 with SKSE64 2.3.1; other runtimes require separate verification.

Follow [the shared build guide](../docs/BUILDING.md) for the required C++ tools,
Java bootstrap builds, pinned native dependencies and packaging sequence.
The DLL is written to `build/RelWithDebInfo/SkyCraft.dll`.

Every successful default native build creates a compiled ZIP. For distributable
files, source paths are mapped to generic project paths and the PDB reference is
relative; PDB files stay outside releases. Preserve licenses and supply the
matching corresponding source when redistributing the linked native plugin,
as described in [DISTRIBUTION.md](../DISTRIBUTION.md).
