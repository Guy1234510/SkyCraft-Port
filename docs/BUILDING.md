# Build SkyCraft Port

Current releases pair **Forge 1.20.1 build 68** and **NeoForge 1.21.1 build 89**
with **Native112 / protocol 17**. Run commands from the repository root.

Install Git, Python 3.10+, JDK 17 for Forge and JDK 21 for NeoForge. Native
compilation requires Visual Studio with the C++ workload and Windows SDK,
CMake 3.25+ and vcpkg. The first build downloads dependencies from their authors;
Minecraft and Skyrim source or game files are not included in this repository.

## Build both Minecraft ports

With `JAVA_HOME` set to JDK 17:

```powershell
Set-Location forge
.\gradlew.bat assemble check --console plain
Set-Location ..
```

With `JAVA_HOME` set to JDK 21:

```powershell
Set-Location neoforge
..\fabric\gradlew.bat assemble check --console plain
Set-Location ..
```

Both ports run their Mixin target checks. Production JARs are in
`forge/build/libs/` and `neoforge/build/libs/`. Do not install `-sources.jar`
or developer JARs from `build/devlibs/`.

## Build the native plugin

The public source snapshot includes the CommonLibSSE-NG build source at
`skse/extern/CommonLibSSE-NG/`, with its exact upstream commit recorded in
`SOURCE-MANIFEST.json`. Unused Address Library test databases, examples and
dependency binaries are omitted. Required OpenVR headers retain their BSD
license; Skyrim VR support is disabled in this port.

Set up the vcpkg revision used by the release:

```powershell
git clone https://github.com/microsoft/vcpkg .tools/vcpkg
git -C .tools/vcpkg checkout da2be01c400dd3ed102c1198752ef44c76aabe37
.tools/vcpkg/bootstrap-vcpkg.bat -disableMetrics
$toolchainPath = (Resolve-Path .tools/vcpkg/scripts/buildsystems/vcpkg.cmake).Path
cmake -S skse -B skse/build -G "Visual Studio 18 2026" -A x64 "-DCMAKE_TOOLCHAIN_FILE=$toolchainPath" -DVCPKG_TARGET_TRIPLET=x64-windows-static-md -DSKYCRAFT_DEPLOY_DIR= -DCOMMONLIB_ENABLE_IPO=OFF
cmake --build skse/build --config RelWithDebInfo --parallel 4
```

Use the generator matching your installed Visual Studio version. The release was
built with Visual Studio 2026. The native output is
`skse/build/RelWithDebInfo/SkyCraft.dll`. Do not redistribute PDB files.

## Package compiled files

After both Java ports and the native plugin exist, every default CMake build
and each maintained Gradle `build` runs `tools/package_release.py`. It creates a
combined compiled ZIP under `dist/`; `dist/latest-release.json` identifies it.
An absent counterpart JAR or DLL prevents packaging, so finish the sequence above
before using Gradle `build`.

To create separate downloads for both Minecraft versions without recompiling:

```powershell
python tools/package_release.py --split
```

`dist/latest-release-split.json` identifies those packages. They include the
matching JAR, common native DLL, example INI, English guides and license notices.
They exclude game files, private settings, caches, logs, saves and debug symbols.
Public native releases must also provide matching corresponding source,
including native dependencies; see [DISTRIBUTION.md](../DISTRIBUTION.md).

The Forge and NeoForge clients must agree with the native plugin's protocol.
Compilation and static checks do not establish full gameplay compatibility.
