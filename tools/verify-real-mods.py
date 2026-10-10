#!/usr/bin/env python3
"""Opt-in, hash-pinned static corpus checks. Downloaded JARs are never executed."""
import argparse
import collections
import hashlib
import json
import os
import pathlib
import platform
import subprocess
import time
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parents[1]


def verify(path, artifact):
    if path.stat().st_size != artifact['size']:
        raise ValueError(f"Size mismatch: {artifact['versionId']}")
    data = path.read_bytes()
    for algorithm in ('sha512', 'sha256', 'sha1'):
        if hashlib.new(algorithm, data).hexdigest() != artifact[algorithm]:
            raise ValueError(f"{algorithm} mismatch: {artifact['versionId']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true', help='Explicitly permit fixed HTTPS downloads from Modrinth')
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'docs/real-mods-manifest.json').read_text(encoding='utf-8'))
    work = ROOT / 'build/real-mods'
    cache, reports = work / 'cache', work / 'reports'
    cache.mkdir(parents=True, exist_ok=True)
    reports.mkdir(parents=True, exist_ok=True)
    artifacts = {a['versionId']: a for a in manifest['artifacts']}
    for artifact in artifacts.values():
        version = artifact['versionId']
        if not version.isalnum() or not 0 < artifact['size'] <= 128 * 1024 * 1024:
            raise ValueError('Invalid manifest cache identity or size')
        path = cache / (version + '.jar')
        if not path.exists():
            if not args.download:
                raise ValueError('Missing cache; use --download to explicitly download the pinned corpus')
            url = urllib.parse.urlparse(artifact['url'])
            if url.scheme != 'https' or url.hostname != 'cdn.modrinth.com':
                raise ValueError('Unexpected download source')
            temporary = path.with_suffix('.download')
            try:
                subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--proto', '=https', '--proto-redir', '=https',
                                '--max-time', '120', '--max-filesize', str(artifact['size']), '--output', str(temporary), artifact['url']], check=True)
                verify(temporary, artifact)
                temporary.replace(path)
            finally:
                temporary.unlink(missing_ok=True)
        verify(path, artifact)  # A pre-existing cache is never trusted implicitly.
    launcher = ROOT / ('build/install/mixinscope/bin/mixinscope.bat' if os.name == 'nt' else 'build/install/mixinscope/bin/mixinscope')
    if not launcher.is_file():
        raise ValueError('Build the CLI first: ./gradlew installDist')
    summaries = []
    for pack in manifest['packs']:
        output = reports / (pack['id'] + '.json')
        command = [str(launcher), 'scan'] + [str(cache / (version + '.jar')) for version in pack['artifacts']]
        for option, key in [('--loader', 'loader'), ('--loader-version', 'loaderVersion'), ('--minecraft-version', 'minecraftVersion'),
                            ('--input-namespace', 'namespace'), ('--mixin-version', 'mixinVersion'), ('--mixinextras-version', 'mixinExtrasVersion')]:
            command += [option, pack[key]]
        if pack.get('baseline'):
            command += ['--baseline', str(reports / (pack['baseline'] + '.json'))]
        command += ['--format', 'json', '--output', str(output)]
        output.unlink(missing_ok=True)
        start = time.monotonic()
        run = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=150)
        seconds = time.monotonic() - start
        output.with_suffix('.stderr.txt').write_text(run.stderr, encoding='utf-8')
        if run.returncode != 0 or not output.is_file():
            raise RuntimeError(f"Corpus scan failed: {pack['id']} (exit={run.returncode})\n{run.stderr}")
        report = json.loads(output.read_text(encoding='utf-8'))
        if report['coverage']['partial'] or not report['modifications']:
            raise ValueError(f"Incomplete or empty corpus scan: {pack['id']}")
        summary = {'pack': pack['id'], 'seconds': round(seconds, 3), 'exit': run.returncode,
                   'artifacts': len(report['artifacts']), 'configs': len(report['configs']), 'modifications': len(report['modifications']),
                   'findings': len(report['findings']), 'targetGroups': len(report['targetGroups']), 'partial': report['coverage']['partial'],
                   'kinds': dict(sorted(collections.Counter(m['kind'] for m in report['modifications']).items())),
                   'diagnostics': dict(sorted(collections.Counter(d['code'] for d in report['coverage']['diagnostics']).items()))}
        if report['baseline']:
            summary['baseline'] = {k: len(v) if isinstance(v, list) else v for k, v in report['baseline'].items()}
        summaries.append(summary)
        print(json.dumps(summary, ensure_ascii=False), flush=True)
    result = {'scope': 'Static JAR reading only; no Minecraft, downloaded Mods or their plugins executed',
              'generatedAtUTC': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
              'manifestSha256': hashlib.sha256((ROOT / 'docs/real-mods-manifest.json').read_bytes()).hexdigest(),
              'schemaVersion': report['schemaVersion'], 'rulesetVersion': report['rulesetVersion'],
              'toolVersion': report['toolVersion'],
              'environment': {'os': platform.platform(), 'python': platform.python_version(), 'architecture': platform.machine()}, 'packs': summaries}
    (work / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
