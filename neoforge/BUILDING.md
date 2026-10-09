# Build on Windows

Use a JDK 21 installation and an internet connection for the first dependency download. The module
configures a Java 21 toolchain and uses the Gradle wrapper in the sibling `fabric` directory.

From PowerShell at the universal-modder repository root:

```powershell
Set-Location "examples\skycraft-neoforge\neoforge"
..\fabric\gradlew.bat build --console plain
```

The mod JAR is generated at:

```text
build/libs/skycraft-neoforge-0.1.2-neoforge.1.21.1-86.jar
```

The `-sources.jar` file contains sources and is not the mod to install.

To rebuild from scratch, use `..\fabric\gradlew.bat clean build --console plain`.

This build targets Minecraft 1.21.1 and NeoForge 21.1.252 (minimum 21.1.248). It compiles against
Forgified Fabric API 0.116.15+2.3.5+1.21.1, which is a separate runtime dependency, not embedded in
this JAR. Every build checks configured Mixin contracts against the patched NeoForge classes.
See README.md for startup instructions.

The current clients require protocol17 and the matching native DLL. Update both clients
and the plugin together as described in ../skse/BUILDING.md. Full-resolution texture
streaming, real dropped-item models and moving-support camera synchronization remain.
Plain M reaches Minecraft; Ctrl+M opens the Skyrim map.


Current installed (2026-10-08): Native107 / Neo1.21.1-86 / Forge1.20.1-65. Corrected package-private Rapier terrain access, Minecraft model winding (primed TNT) and startup/respawn cut restoration. Native/paired builds and72/74 Mixin audits pass, including installed Sable API/access checks. Per port:1,350 terrain,187 arrival and187 dig-resend checks;173 native readiness,128/24 GPU,75 flight,1,327 support and3,520 controller checks pass. Real107 gameplay/FPS remains unverified; the user prohibits game launch/control. Preserve confirmed assembler lighting, Elytra F5 and support handoff. See MODLOG.
