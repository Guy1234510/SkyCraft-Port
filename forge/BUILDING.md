# Build SkyCraft Forge 1.20.1

Install JDK 17 and point `JAVA_HOME` at that installation. From this folder:

```powershell
.\gradlew.bat assemble check --console plain
```

The production JAR is under `build/libs/`; do not install `-sources.jar` or
the developer JAR from `build/devlibs/`. Production remapping and Mixin
verification run automatically.

For the full build and release ZIP, build the native plugin and both Java ports
first as described in [the shared build guide](../docs/BUILDING.md), then run
`.\gradlew.bat build --console plain`. The package uses protocol **17**.
Current release: Forge build **68**, paired with **Native112**. The newest
terrain-edge rendering adjustment still awaits gameplay confirmation.
