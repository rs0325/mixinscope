#!/usr/bin/env python3
"""Verify built ZIP/TAR distributions and assemble local release assets. Does not publish."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    version = (ROOT / 'VERSION').read_text().strip()
    require(re.fullmatch(r'\d+\.\d+\.\d+(?:-alpha\.\d+)?', version), '正式版またはalpha版のバージョンが必要です')
    name = 'mixinscope-' + version
    zip_path = ROOT / 'build/distributions' / (name + '.zip')
    tar_path = ROOT / 'build/distributions' / (name + '.tar')
    with zipfile.ZipFile(zip_path) as archive:
        zip_files = {i.filename: archive.read(i) for i in archive.infolist() if not i.is_dir()}
        require(len(zip_files) == len([i for i in archive.infolist() if not i.is_dir()]), 'Duplicate ZIP entries')
        unix_mode = archive.getinfo(name + '/bin/mixinscope').external_attr >> 16
        require(unix_mode & 0o111, 'Unix launcher is not executable in ZIP')
    with tarfile.open(tar_path) as archive:
        members = archive.getmembers()
        require(all(m.isdir() or m.isfile() for m in members), 'Unexpected TAR entry type')
        tar_files = {m.name: archive.extractfile(m).read() for m in members if m.isfile()}
        require(len(tar_files) == len([m for m in members if m.isfile()]), 'Duplicate TAR entries')
        require(archive.getmember(name + '/bin/mixinscope').mode & 0o111, 'Unix launcher is not executable in TAR')
    require(zip_files == tar_files, 'ZIP and TAR contents differ')
    for path in zip_files:
        parts = PurePosixPath(path).parts
        require(parts and parts[0] == name and '..' not in parts and '\\' not in path, 'Unexpected archive path')
    expected = ['LICENSE', 'README.md', 'CHANGELOG.md', 'THIRD-PARTY.md', 'VERSION', 'docs/report.schema.json', 'docs/release-notes.md', 'licenses/manifest.json', 'bin/mixinscope', 'bin/mixinscope.bat', 'lib/' + name + '.jar']
    for path in expected:
        require(name + '/' + path in zip_files, 'Missing distribution file: ' + path)
    require(zip_files[name + '/VERSION'].decode().strip() == version, 'Packaged version mismatch')
    require(zip_files[name + '/LICENSE'] == (ROOT / 'LICENSE').read_bytes(), 'Project license mismatch')
    manifest = json.loads(zip_files[name + '/licenses/manifest.json'])
    runtime_files = {path.removeprefix(name + '/lib/'): data for path, data in zip_files.items() if path.startswith(name + '/lib/')}
    require(set(runtime_files) == {i['file'] for i in manifest} | {name + '.jar'}, 'Unexpected runtime JAR set')
    for item in manifest:
        require(sha(runtime_files[item['file']]) == item['sha256'], 'Dependency JAR hash mismatch: ' + item['file'])
        require(item['notices'], 'Missing third-party notice: ' + item['file'])
        for notice in item['notices']:
            require(name + '/' + notice in zip_files, 'Notice not packaged: ' + notice)
            require(zip_files[name + '/' + notice] == (ROOT / notice).read_bytes(), 'Notice content mismatch')
    # Extract only the already verified, repository-built distribution, not input Mod JARs.
    work = ROOT / 'build/release-verification'
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=work) as directory:
        for path, data in zip_files.items():
            file = Path(directory).joinpath(*PurePosixPath(path).parts)
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)
        launcher = Path(directory) / name / 'bin' / ('mixinscope.bat' if os.name == 'nt' else 'mixinscope')
        if os.name != 'nt':
            launcher.chmod(0o755)
        result = subprocess.run([str(launcher), '--version'], capture_output=True, text=True, check=True, timeout=30)
        require(result.stdout.strip() == 'MixinScope ' + version, 'Packaged CLI version mismatch')
        report_path = Path(directory) / 'report.json'
        subprocess.run([str(launcher), 'scan', str(ROOT / 'build/demo/mods'), '--format', 'json', '--output', str(report_path)], capture_output=True, text=True, check=True, timeout=30)
        report = json.loads(report_path.read_text(encoding='utf-8'))
        require(report['toolVersion'] == version and report['schemaVersion'] == '1.1' and report['rulesetVersion'] == '4', 'Packaged report version mismatch')
        require(not report['coverage']['partial'] and any(f['rule'] == 'MS002' for f in report['findings']), 'Packaged demo scan failed')
    output = ROOT / 'build/release' / version
    output.mkdir(parents=True, exist_ok=True)
    sums = []
    for path in (zip_path, tar_path):
        shutil.copyfile(path, output / path.name)
        sums.append(sha(path.read_bytes()) + '  ' + path.name)
    (output / 'SHA256SUMS').write_text('\n'.join(sums) + '\n', encoding='utf-8')
    shutil.copyfile(ROOT / 'docs/release-notes.md', output / 'RELEASE-NOTES.md')
    revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    dirty = bool(subprocess.run(['git', 'status', '--porcelain'], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip())
    result = {'version': version, 'sourceRevision': revision, 'sourceDirty': dirty, 'schemaVersion': '1.1', 'rulesetVersion': '4', 'distributionFiles': len(zip_files), 'runtimeJars': sorted(runtime_files), 'checks': ['ZIP/TAR content equality', 'launcher executable modes', 'license and notice inventory', 'dependency hashes', 'extracted ZIP --version', 'extracted ZIP authored demo scan'], 'published': False}
    (output / 'verification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f'Verified {name}: ZIP/TAR, notices, extracted launcher and JSON; assets in {output.relative_to(ROOT)}; sourceDirty={dirty}')

if __name__ == '__main__':
    main()
