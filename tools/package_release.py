"""Package the latest compiled ports, native plugin, notices and installation guides."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def latest_jar(module):
    pattern = re.compile(rf"skycraft-{module}-([0-9.]+)-{module}\.([0-9.]+)(?:-([0-9]+))?\.jar$")
    candidates = []
    for path in (ROOT / module / "build/libs").glob("*.jar"):
        match = pattern.fullmatch(path.name)
        if match:
            version = tuple(int(part) for part in (match[1] + "." + match[2] + ("." + match[3] if match[3] else "")).split("."))
            candidates.append((version, path))
    for _, path in sorted(candidates, reverse=True):
        try:
            with zipfile.ZipFile(path) as archive:
                names = archive.namelist()
                if "LICENSE_SkyCraft" in names and archive.testzip() is None:
                    if any(name.endswith((".java", ".cpp", ".h", ".pdb")) for name in names):
                        raise RuntimeError(f"Source/debug files found inside {path.name}")
                    return path
        except (zipfile.BadZipFile, OSError):
            # Another port may still be writing its JAR during parallel builds.
            continue
    raise RuntimeError(f"No complete compiled {module} JAR; build that port first.")


def check_private_paths(data, name):
    # Check ASCII and UTF-16 paths in binaries too, including embedded PDB paths.
    markers = [str(ROOT), str(Path.home())]
    for marker in markers:
        for spelling in (marker, marker.replace("\\", "/")):
            for encoding in ("utf-8", "utf-16-le"):
                if spelling.lower().encode(encoding) in data.lower():
                    raise RuntimeError(f"Personal build path found in {name}; package aborted.")


def split_packages(native, source_commit):
    native_text = (ROOT / "skse/src/main.cpp").read_text(encoding="utf-8")
    match = re.search(r"native(\d+) loading", native_text)
    if not match:
        raise RuntimeError("Native revision not found in the source.")
    native_revision = int(match[1])
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    packages = []
    for module, title in (("forge", "Forge"), ("neoforge", "NeoForge")):
        jar = latest_jar(module)
        version = re.search(r"\.([0-9]+\.[0-9]+\.[0-9]+)-(\d+)\.jar$", jar.name)
        if not version:
            raise RuntimeError(f"Unrecognized compiled version: {jar.name}")
        minecraft, revision = version.groups()
        files = [(jar, f"Minecraft/{module}/{jar.name}"),
                 (native, "Skyrim/Data/SKSE/Plugins/SkyCraft.dll"),
                 (ROOT / "skse/SkyCraft.ini.example", "Skyrim/Data/SKSE/Plugins/SkyCraft.ini.example")]
        files.extend((ROOT / name, name) for name in
                     ("LICENSE", "THIRD-PARTY-NOTICES.md", "DISTRIBUTION.md", "install.md"))
        files.extend((path, f"licenses/{path.name}") for path in sorted((ROOT / "licenses").iterdir())
                     if path.is_file() and path.suffix in (".txt", ".md"))
        files.extend((path, f"docs/installation/{path.name}")
                     for path in sorted((ROOT / "docs/installation").glob("*.md")))
        payloads = {}
        for source, name in files:
            data = source.read_bytes()
            check_private_paths(data, name)
            if len(data) >= 100_000_000:
                raise RuntimeError(f"File reaches the 100 MB publication limit: {name}")
            payloads[name] = data
        readme = f"""SkyCraft Port - {title} {minecraft} build {revision} / Native{native_revision}

Start with install.md. Extract the ZIP; do not extract the Minecraft JAR.
Copy Minecraft/{module}/{jar.name} into your profile's mods folder.
Install Skyrim/Data/SKSE/Plugins/SkyCraft.dll into Skyrim's Data/SKSE/Plugins.
Copy SkyCraft.ini.example as SkyCraft.ini, or merge it with your configuration.
Skyrim, Minecraft, SKSE64, Address Library and external mods are acquired separately.

This port is 100% vibe-coded. There will be no regular updates.
The latest terrain-edge visual adjustment awaits gameplay confirmation.
Preserve LICENSE, THIRD-PARTY-NOTICES.md, DISTRIBUTION.md and licenses/.
Matching corresponding source accompanies this package on its GitHub release page.
"""
        payloads["README.txt"] = readme.encode("utf-8")
        manifest = {"created_utc": datetime.now(timezone.utc).isoformat(),
                    "minecraft": minecraft, "loader": title, "build": jar.name,
                    "native_revision": native_revision, "protocol": 17,
                    "source_commit": source_commit, "source_included": False,
                    "gameplay_validation": "latest terrain-edge adjustment pending",
                    "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()}}
        payloads["MANIFEST.json"] = json.dumps(manifest, indent=2).encode("utf-8")
        name = f"SkyCraft-{title}-{minecraft}-{revision}-Native{native_revision}.zip"
        target = output / name
        staging = output / (name + ".part")
        try:
            with zipfile.ZipFile(staging, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
                for entry, data in payloads.items():
                    archive.writestr(entry, data)
            with zipfile.ZipFile(staging) as archive:
                if archive.testzip() is not None or set(archive.namelist()) != set(payloads):
                    raise RuntimeError("Release ZIP verification failed.")
            os.replace(staging, target)
        finally:
            if staging.exists():
                staging.unlink()
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        target.with_suffix(".zip.sha256").write_text(digest + "  " + name + "\n", encoding="ascii")
        packages.append({"zip": name, "sha256": digest, "jar": jar.name,
                         "loader": title, "minecraft": minecraft,
                         "tag": f"{module}-{minecraft}-{revision}-native{native_revision}"})
        print(f"Release package: dist/{name} ({len(payloads)} files; {target.stat().st_size} bytes)")
    (output / "latest-release-split.json").write_text(json.dumps(packages, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native", type=Path)
    parser.add_argument("--split", action="store_true", help="Create separate Forge and NeoForge packages.")
    parser.add_argument("--source-commit", help="Reviewed repository commit corresponding to these binaries.")
    args = parser.parse_args()
    native = args.native or ROOT / "skse/build/RelWithDebInfo/SkyCraft.dll"
    if not native.is_file():
        raise RuntimeError("Build the native Skyrim plugin before packaging.")
    if args.split:
        split_packages(native, args.source_commit)
        return
    jars = {module: latest_jar(module) for module in ("neoforge", "forge")}
    files = [(path, f"Minecraft/{module}/{path.name}") for module, path in jars.items()]
    files.append((native, "Skyrim/Data/SKSE/Plugins/SkyCraft.dll"))
    files.append((ROOT / "skse/SkyCraft.ini.example", "Skyrim/Data/SKSE/Plugins/SkyCraft.ini.example"))
    files.extend((ROOT / name, name) for name in ("LICENSE", "THIRD-PARTY-NOTICES.md", "DISTRIBUTION.md", "install.md"))
    files.extend((path, f"licenses/{path.name}") for path in sorted((ROOT / "licenses").iterdir())
                 if path.is_file() and path.suffix in (".txt", ".md"))
    files.extend((path, f"docs/installation/{path.name}")
                 for path in sorted((ROOT / "docs/installation").glob("*.md")))
    expected = {"licenses/CommonLibSSE-NG-GPL-3.0.txt", "licenses/CommonLibSSE-NG-EXCEPTIONS.md",
                "docs/installation/INSTALL-EN.md"}
    if not expected.issubset({name for _, name in files}):
        raise RuntimeError("Required notices or installation guides are missing.")
    payloads = {}
    for source, name in files:
        data = source.read_bytes()
        check_private_paths(data, name)
        payloads[name] = data
    manifest = {
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "builds": {module: path.name for module, path in jars.items()},
        "source_included": False,
        "gameplay_validation": "pending",
        "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()},
    }
    readme = """SkyCraft — compiled release

Start with docs/installation/INSTALL-EN.md.

Choose ONE Minecraft version. Copy only the JAR matching your Minecraft loader.
Minecraft/neoforge: Minecraft 1.21.1 / NeoForge.
Minecraft/forge: Minecraft 1.20.1 / Forge.
Skyrim/Data: SkyCraft's own plugin, to be installed into Skyrim's Data folder.

Skyrim, Minecraft, SKSE, Address Library and optional mods are acquired separately.
Compilation and Mixin audits do not confirm the visual/gameplay fixes in game.
Keep LICENSE, THIRD-PARTY-NOTICES.md, DISTRIBUTION.md and licenses/ when sharing.
This ZIP excludes project source, PDBs, logs, saves, personal settings and game assets.
MANIFEST.json records the exact compiled JARs and SHA-256 hashes.
"""
    payloads["README.txt"] = readme.encode("utf-8")
    payloads["MANIFEST.json"] = json.dumps(manifest, indent=2, ensure_ascii=False).encode("utf-8")
    labels = [re.split(r"[.-]",path.stem)[-1] for path in jars.values()]
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    name = f"SkyCraft-Neo{labels[0]}-Forge{labels[1]}-{stamp}-{uuid.uuid4().hex[:6]}.zip"
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    staging = output / (name + ".part")
    target = output / name
    try:
        with zipfile.ZipFile(staging, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for entry, data in payloads.items():
                archive.writestr(entry, data)
        with zipfile.ZipFile(staging) as archive:
            if archive.testzip() is not None or set(archive.namelist()) != set(payloads):
                raise RuntimeError("Release ZIP verification failed.")
        os.replace(staging, target)
    finally:
        if staging.exists():
            staging.unlink()
    zip_digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.zip.sha256').write_text(zip_digest + '  ' + name + '\n', encoding='ascii')
    pointer = output / f".latest-{uuid.uuid4().hex}.json"
    pointer.write_text(json.dumps({"zip": name, "sha256": zip_digest, "builds": manifest["builds"]}, indent=2), encoding="utf-8")
    os.replace(pointer, output / "latest-release.json")
    print(f"Release ZIP: dist/{name} ({len(payloads)} files; no source or private build paths)")


if __name__ == "__main__":
    main()
