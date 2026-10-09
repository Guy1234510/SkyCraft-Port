"""Prepare a reviewed source snapshot with no old Git history or local settings."""

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TOP_FILES = {'.gitignore', '.gitattributes', 'LICENSE', 'README.md', 'install.md', 'THIRD-PARTY-NOTICES.md', 'DISTRIBUTION.md'}
TOP_DIRS = {'fabric', 'forge', 'neoforge', 'protocol', 'skse', 'tools', 'licenses', 'docs'}
PUBLIC_TOOLS = {'prepare_github.py', 'package_release.py',
                'test_export_queue.py', 'test_fire_contact.cpp', 'test_fire_contact.cmd',
                'test_water_pass.cpp', 'test_water_pass.cmd', 'test_flight_frame.cpp', 'test_flight_frame.cmd',
                'test_world_effects.cpp', 'test_world_effects.cmd',
                'TerrainRegression.java', 'WaterCacheRegression.java', 'check_world_shader.py'}
PUBLIC_DOCS = {'BUILDING.md', 'PUBLISH-GITHUB.md', 'installation/INSTALL-EN.md'}
COMMON = ROOT / 'skse/extern/CommonLibSSE-NG'
COMMON_URL = 'https://github.com/alandtse/CommonLibVR'
COMMON_DIRS = {'include', 'src', 'cmake', 'licenses'}
COMMON_ROOT_FILES = {'CMakeLists.txt', 'CMakePresets.json', 'CommonLibSSE.natvis',
                     'COPYING.txt', 'EXCEPTIONS.md', 'LICENSE', 'README.md',
                     'vcpkg.json', '.clang-format'}
PRIVATE_NAMES = [part[len('- COISAS '):] for part in ROOT.parts if part.startswith('- COISAS ')]
CODE_EXTENSIONS = {'.py', '.ps1', '.cpp', '.h', '.hpp', '.c', '.java', '.gradle',
                   '.kts', '.cmake', '.bat', '.cmd', '.sh', '.inl', '.vsh', '.fsh', '.glsl'}
PORTUGUESE_PROSE = re.compile(r'\b(?:você|vocês|não|também|instalação|português|usuário|'
                             r'corrigido|corrigida|próximo|próxima|evidência|baixar|'
                             r'descompactar|substitua|terreno|blocos|neve|atualizações)\b', re.I)


def git_files(folder):
    top = subprocess.run(['git', '-C', str(folder), 'rev-parse', '--show-toplevel'],
                         capture_output=True, text=True)
    if top.returncode or Path(top.stdout.strip()).resolve() != folder.resolve():
        return sorted(path.relative_to(folder).as_posix() for path in folder.rglob('*')
                      if path.is_file() and not any(part in {'.git', 'build', '.gradle', '__pycache__'}
                                                  for part in path.relative_to(folder).parts))
    result = subprocess.run(['git', '-C', str(folder), 'ls-files', '-c', '-o', '--exclude-standard', '-z'],
                            check=True, capture_output=True)
    return sorted({name.decode('utf-8') for name in result.stdout.split(b'\0') if name})


def audit(name, data):
    if len(data) >= 100_000_000:
        raise RuntimeError(f'File reaches the 100 MB publication limit: {name}')
    text = data.decode('utf-8', errors='ignore') + '\n' + data.decode('utf-16-le', errors='ignore')
    for marker in [str(ROOT), str(Path.home()), *PRIVATE_NAMES]:
        for spelling in {marker, marker.replace('\\', '/')}:
            if spelling and re.search(r'(?<!\w)' + re.escape(spelling) + r'(?!\w)', text, re.IGNORECASE):
                raise RuntimeError(f'Personal name or path found in {name}; export stopped.')
    patterns = [r'[A-Za-z]:[\\/]+Users[\\/]+[^\\/\r\n<>]+',
                r'(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16})',
                r'sk-[A-Za-z0-9_-]{32,}',
                r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----']
    if any(re.search(pattern, text) for pattern in patterns):
        raise RuntimeError(f'User path, credential or private key found in {name}; export stopped.')
    if Path(name).suffix.lower() not in CODE_EXTENSIONS and PORTUGUESE_PROSE.search(text):
        raise RuntimeError(f'Portuguese non-code prose found in {name}; export stopped.')


def revision(folder, fallback=None):
    top = subprocess.run(['git', '-C', str(folder), 'rev-parse', '--show-toplevel'],
                         capture_output=True, text=True)
    if top.returncode == 0 and Path(top.stdout.strip()).resolve() == folder.resolve():
        dirty = subprocess.run(['git', '-C', str(folder), 'status', '--porcelain', '--untracked-files=no'],
                               check=True, capture_output=True, text=True).stdout
        if dirty.strip():
            raise RuntimeError(f'Dependency has local modifications: {folder.name}')
        return subprocess.run(['git', '-C', str(folder), 'rev-parse', 'HEAD'], check=True,
                              capture_output=True, text=True).stdout.strip()
    return fallback


def collect_sources():
    files = {}
    for name in git_files(ROOT):
        relative = Path(name)
        if relative.parts[0] not in TOP_DIRS and name not in TOP_FILES:
            continue
        if relative.parts[0] == 'tools' and (len(relative.parts) != 2 or relative.name not in PUBLIC_TOOLS):
            continue
        if relative.parts[0] == 'docs' and relative.relative_to('docs').as_posix() not in PUBLIC_DOCS:
            continue
        if any(part in {'.github', '.git', '.agents', '.claude', '.codex', '.cursor'} for part in relative.parts):
            continue
        if relative.name in {'AGENTS.md', 'MODLOG.md', 'CONTINUE_HERE.md', 'gradle.properties.local'}:
            continue
        if relative.as_posix().startswith('skse/extern/CommonLibSSE-NG/'):
            continue
        source = ROOT / relative
        if source.is_file():
            files[relative.as_posix()] = source
    for name in git_files(COMMON):
        parts = Path(name).parts
        if parts[0] not in COMMON_DIRS and name not in COMMON_ROOT_FILES:
            continue
        source = COMMON / name
        if source.is_file():
            files[f'skse/extern/CommonLibSSE-NG/{name}'] = source
    openvr = COMMON / 'extern/openvr'
    for name in ('LICENSE', 'headers/openvr.h', 'headers/openvr_driver.h', 'headers/openvr_capi.h'):
        source = openvr / name
        if not source.is_file():
            raise RuntimeError(f'Required OpenVR source missing: {name}')
        files[f'skse/extern/CommonLibSSE-NG/extern/openvr/{name}'] = source
    minhook = ROOT / 'skse/extern/minhook'
    if not minhook.is_dir():
        minhook = ROOT / 'skse/build/_deps/hde64-src'
    for name in ('LICENSE.txt', 'src/hde/hde64.c', 'src/hde/hde64.h', 'src/hde/pstdint.h', 'src/hde/table64.h'):
        source = minhook / name
        if not source.is_file():
            raise RuntimeError(f'Required hde64 source missing: {name}')
        files[f'skse/extern/minhook/{name}'] = source
    return files


def main():
    files = collect_sources()
    existing = ROOT / 'SOURCE-MANIFEST.json'
    previous = json.loads(existing.read_text(encoding='utf-8')) if existing.is_file() else {}
    commit = revision(COMMON, previous.get('dependency', {}).get('commit'))
    if not commit:
        raise RuntimeError('Exact CommonLib revision is not known; export stopped.')
    identities = [subprocess.run(['git', '-C', str(ROOT), 'config', '--get', key],
                                  capture_output=True, text=True).stdout.strip()
                  for key in ('user.name', 'user.email')]
    payloads = {}
    for name, source in files.items():
        data = source.read_bytes()
        if not name.endswith('.jar'):
            data = data.replace(b'\r\n', b'\n')
        if Path(name).suffix.lower() in {'.dll', '.exe', '.pdb', '.obj', '.class', '.zip', '.bin', '.lib', '.pch'}:
            raise RuntimeError(f'Build/game binary found in source snapshot: {name}')
        if name.endswith('.jar') and not name.endswith('/gradle-wrapper.jar'):
            raise RuntimeError(f'Unexpected JAR in source snapshot: {name}')
        audit(name, data)
        readable = data.decode('utf-8', errors='ignore')
        for value in identities:
            if value and not value.endswith('@users.noreply.github.com') and re.search(
                    r'(?<!\w)' + re.escape(value) + r'(?!\w)', readable, re.I):
                raise RuntimeError(f'Local Git identity found in {name}; export stopped.')
        payloads[name] = data
    payloads['.gitattributes'] = b'* text=auto eol=lf\n*.jar binary\n'
    required = {'LICENSE', 'THIRD-PARTY-NOTICES.md', 'DISTRIBUTION.md',
                'licenses/CommonLibSSE-NG-GPL-3.0.txt', 'licenses/CommonLibSSE-NG-EXCEPTIONS.md',
                'skse/extern/CommonLibSSE-NG/COPYING.txt', 'skse/extern/CommonLibSSE-NG/EXCEPTIONS.md',
                'licenses/Gradle-APACHE-2.0.txt', 'licenses/OpenVR-BSD-3-Clause.txt',
                'skse/extern/CommonLibSSE-NG/extern/openvr/headers/openvr.h',
                'skse/extern/minhook/src/hde/hde64.c'}
    missing = required - payloads.keys()
    if missing:
        raise RuntimeError(f'Required licenses/source notices missing: {sorted(missing)}')
    manifest = {'created_utc': datetime.now(timezone.utc).isoformat(),
                'previous_git_history_included': False,
                'upstream_skycraft': {'repository': 'https://github.com/chasmlol/SkyCraft',
                                     'base_commit': 'bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32'},
                'dependency': {'name': 'CommonLibSSE-NG', 'commit': commit,
                               'repository': COMMON_URL, 'build_source_included': True,
                               'excluded': ['tests', 'examples', 'binary dependencies', 'CI and agent settings']},
                'openvr_headers_commit': revision(COMMON / 'extern/openvr', previous.get('openvr_headers_commit')),
                'minhook_commit': revision(ROOT / 'skse/build/_deps/hde64-src', previous.get('minhook_commit')),
                'sha256': {name: hashlib.sha256(data).hexdigest() for name, data in payloads.items()}}
    payloads['SOURCE-MANIFEST.json'] = json.dumps(manifest, indent=2).encode('utf-8')
    parent = ROOT / 'dist/github'
    parent.mkdir(parents=True, exist_ok=True)
    target = parent / ('SkyCraft-' + datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S'))
    if not target.resolve().is_relative_to(parent.resolve()):
        raise RuntimeError('Source snapshot target is outside the export directory.')
    target.mkdir()
    for name, data in payloads.items():
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    subprocess.run(['git', '-C', str(target), 'init', '--initial-branch=main'], check=True,
                   capture_output=True)
    # This directory contains only the audited payloads. CommonLib intentionally
    # ignores some tracked CMake sources; retain them in this vendored snapshot.
    subprocess.run(['git', '-C', str(target), 'add', '--force', '--all'], check=True, capture_output=True)
    staged = subprocess.run(['git', '-C', str(target), 'diff', '--cached', '--name-only', '-z'],
                            check=True, capture_output=True).stdout
    staged_names = {name.decode('utf-8') for name in staged.split(b'\0') if name}
    missing = set(payloads) - staged_names
    if missing:
        raise RuntimeError(f'Required source files ignored by Git: {sorted(missing)}')
    # No commit is made: the user chooses a GitHub identity and no-reply email first.
    (parent / 'latest-source.json').write_text(json.dumps({'directory': target.name,
        'files': len(payloads), 'dependency_commit': commit}, indent=2), encoding='utf-8')
    print(f'GitHub source snapshot: dist/github/{target.name} ({len(payloads)} audited files)')
    print('Branch main prepared; no old history, commit, remote or upload.')


if __name__ == '__main__':
    main()
