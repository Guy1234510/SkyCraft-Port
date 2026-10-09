# SkyCraft — Minecraft 1.20.1 / Forge

Current compiled build: **1.20.1-68**, paired with Native112. This fixes the inherited-raycast crash in build65.

Use Minecraft 1.20.1, Forge 47.4.10 and Java 17. Install the production JAR
from `Minecraft/forge/` in the release ZIP into your instance's `mods` folder.
Keep one active SkyCraft JAR. MixinExtras is bundled; Fabric API and Connector
are not required by this port.

Install the matching Skyrim native plugin as well: protocol **17**.
Obtain SKSE64 and Address Library for your exact Skyrim runtime separately.

- [Installation in English](../docs/installation/INSTALL-EN.md)
- [Build from source](BUILDING.md)

Use a separate world from the 1.21.1 port. Compilation and Mixin verification
do not establish complete modpack or gameplay compatibility.

Original SkyCraft author: chasmlol; source license MIT. See the root
distribution guide for the native plugin's dependency obligations.
