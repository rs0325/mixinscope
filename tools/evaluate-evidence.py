#!/usr/bin/env python3
"""Compare authored controls and summarize fixed-corpus evidence; never run third-party code."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CASES = {
    'SafeCalculator': ({'MS001'}, {'result': [6], 'originalCalls': [1], 'callbacks': [2]}, None),
    'ChainCalculator': ({'MS001', 'MS007'}, {'result': [9], 'originalCalls': [1]}, None),
    'MethodCalculator': ({'MS001', 'MS007'}, {'result': [9], 'originalCalls': [1]}, None),
    'BypassCalculator': ({'MS001', 'MS007'}, {'result': [99, 101], 'originalCalls': [0]}, 'WRAPPER_CALL_UNVERIFIED'),
    'DelegatedCalculator': (set(), {'result': [7], 'originalCalls': [1]}, 'WRAPPER_CALL_UNVERIFIED'),
    'OverwriteCalculator': ({'MS001', 'MS002'}, {'result': [11, 22], 'originalCalls': [0]}, None),
    'RedirectCalculator': ({'MS001', 'MS003'}, {'expectedFailure': ['org.spongepowered.asm.mixin.injection.throwables.InjectionError']}, None),
    'ArgumentCalculator': ({'MS001', 'MS006'}, {'result': [14, 16], 'originalCalls': [1]}, None),
    'InitializerCalculator': (set(), {'targetSeed': [3], 'mixinInstanceSeed': [7], 'mixinStaticSeed': [5]}, 'MIXIN_INITIALIZER_UNANALYZED'),
    'OptionalInheritedChild': (set(), {'result': [6], 'callbacks': [0]}, 'TARGET_INHERITED_NOT_LOCAL'),
    'OwnedOverrideChild': (set(), {'result': [9], 'callbacks': [1]}, None),
    'RequiredInheritedChild': (set(), {'expectedFailure': ['org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException']}, 'TARGET_INHERITED_NOT_LOCAL'),
    'OverwriteInheritedChild': (set(), {'expectedFailure': ['org.spongepowered.asm.mixin.transformer.throwables.InvalidMixinException']}, 'TARGET_INHERITED_NOT_LOCAL'),
    'ExplicitOwnerCalculator': (set(), {'expectedFailure': ['org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException']}, 'SELECTOR_UNSUPPORTED'),
}

def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fixtures():
    runtime = read(ROOT / 'build/runtime-fixture/results.json')
    require(runtime['mixin'] == '0.15.5+mixin.0.8.7' and runtime['mixinExtras'] == '0.4.1', 'Unexpected runtime profile')
    require(set(runtime['cases']) == set(CASES), 'Runtime case set changed')
    results = []
    for name, (rules, observations, diagnostic) in sorted(CASES.items()):
        report = read(ROOT / 'build/runtime-fixture/static' / (name + '.json'))
        require(report['schemaVersion'] == '1.1' and report['rulesetVersion'] == '4', name + ': unexpected report contract')
        require(not report['coverage']['partial'], name + ': partial report')
        require({f['rule'] for f in report['findings']} == rules, name + ': static rules changed')
        expected_severity = {'MS001': 'LOW', 'MS007': 'LOW', 'MS002': 'HIGH', 'MS003': 'HIGH', 'MS006': 'MEDIUM'}
        for finding in report['findings']:
            require(finding['severity'] == expected_severity[finding['rule']] and finding['confidence'] == 'HIGH', name + ': classification changed')
        codes = sorted({d['code'] for d in report['coverage']['diagnostics']})
        require(diagnostic is None or diagnostic in codes, name + ': missing coverage diagnostic')
        observed = runtime['cases'][name]
        for key, values in observations.items():
            require(observed.get(key) in values, name + ': runtime observation changed: ' + key)
        results.append({'case': name, 'rules': sorted(rules), 'findings': [{'rule': f['rule'], 'severity': f['severity'], 'confidence': f['confidence']} for f in report['findings']], 'diagnostics': codes, 'observed': {k: v for k, v in observed.items() if k != 'message'}, 'matched': True})
    return {'profile': {'mixin': runtime['mixin'], 'mixinExtras': runtime['mixinExtras']}, 'matchedCases': len(results), 'cases': results}


def real_corpus(javap):
    manifest_path = ROOT / 'docs/real-mods-manifest.json'
    manifest = read(manifest_path)
    summaries = []
    for pack in manifest['packs']:
        report = read(ROOT / 'build/real-mods/reports' / (pack['id'] + '.json'))
        require(report['schemaVersion'] == '1.1' and report['rulesetVersion'] == '4', 'Unexpected corpus report contract')
        require(not report['coverage']['partial'], pack['id'] + ': partial report')
        modifications = {m['id']: m for m in report['modifications']}
        require(len(modifications) == len(report['modifications']), 'Duplicate modification ID')
        for finding in report['findings']:
            require(all(mid in modifications for mid in finding['modifications']), 'Dangling finding reference')
            require(finding['confidence'] == 'LOW', 'JAR-only corpus confidence changed; review evidence')
        dimensions = {key: dict(sorted(Counter(f[key] for f in report['findings']).items())) for key in ('rule', 'severity', 'confidence', 'category')}
        summaries.append({'pack': pack['id'], 'findings': len(report['findings']), 'dimensions': dimensions, 'runtimeGroundTruth': 'UNAVAILABLE'})
    report = read(ROOT / 'build/real-mods/reports/fabric-1.21.1.json')
    selected = sorted((f for f in report['findings'] if f['severity'] == 'HIGH' and f['category'] == 'BETWEEN_MODS'), key=lambda f: f['id'])
    reviewed_ids = {'926b470667b34d173d6603dbb95b5308f32e959663f8171209f069fc7392977a', '215e68080e020bc60185922c3d2e110986f99d59af3c9e28f9b3f18010887b3c', 'f4f8429bbaeb23129f6b7e54b1ba0d70aa0f15d299f40bab42fd1bea649b6ae1'}
    require({f['id'] for f in selected} == reviewed_ids, 'HIGH between-mod sample changed; review it before updating IDs')
    modifications = {m['id']: m for m in report['modifications']}
    artifacts = {a['hash']: a for a in report['artifacts']}
    fixed_artifacts = {a['sha256']: a for a in manifest['artifacts']}
    evidence = []
    for finding in selected:
        members = []
        for mid in finding['modifications']:
            mod = modifications[mid]
            artifact = artifacts[mod['artifact']]
            fixed = fixed_artifacts[mod['artifact']]
            jar = ROOT / 'build/real-mods/cache' / (fixed['versionId'] + '.jar')
            require(digest(jar) == fixed['sha256'], 'Corpus JAR hash mismatch')
            require(not artifact['nested'], 'Selected sample must use root artifacts')
            with zipfile.ZipFile(jar) as archive:
                config = json.loads(archive.read(mod['config']))
                declared = config.get('mixins', []) + config.get('client', []) + config.get('server', [])
                require(mod['mixin'] in [config['package'] + '.' + name for name in declared], 'Mixin not in registered config')
                class_hash = hashlib.sha256(archive.read(mod['mixin'].replace('.', '/') + '.class')).hexdigest()
                refmap = json.loads(archive.read(config['refmap'])) if config.get('refmap') else {}
            disassembly = subprocess.run([javap, '-v', '-p', '-classpath', str(jar), mod['mixin']], capture_output=True, text=True, check=True, timeout=30).stdout
            lines = disassembly.splitlines()
            start = next(i for i, line in enumerate(lines) if re.match(r'^  (?:public|private|protected).*\b' + re.escape(mod['handler']) + r'\(', line))
            end = next((i for i in range(start + 1, len(lines)) if re.match(r'^  (?:public|private|protected).*\(', lines[i]) or lines[i] == '}'), len(lines))
            method = '\n'.join(lines[start:end])
            require('descriptor: ' + mod['handlerDescriptor'] in method, 'Handler descriptor mismatch')
            annotation = 'org.spongepowered.asm.mixin.' + ('Overwrite' if mod['kind'] == 'Overwrite' else 'injection.Inject(')
            require(annotation in method, 'Handler annotation mismatch')
            require('value=[class L' + finding['target']['owner'] + ';]' in disassembly, 'Mixin target owner mismatch')
            target = finding['target']
            if mod['kind'] == 'Overwrite':
                require(mod['handler'] == target['name'] and mod['handlerDescriptor'] == target['descriptor'], 'Overwrite target mismatch')
                mapping = None
            else:
                require('cancellable=true' in method and 'value="HEAD"' in method, 'Inject options mismatch')
                require(len(mod['selectors']) == 1 and 'method=["' + mod['selectors'][0] + '"]' in method, 'Inject selector mismatch')
                mapping = refmap['mappings'][mod['mixin'].replace('.', '/')][mod['selectors'][0]]
                require(mapping == 'L' + target['owner'] + ';' + target['name'] + target['descriptor'], 'Refmap target mismatch')
            require(mod['applicability'] == 'CONDITIONAL' and config.get('plugin'), 'Sample activation uncertainty changed')
            members.append({'artifactSha256': mod['artifact'], 'classSha256': class_hash, 'mixin': mod['mixin'], 'handler': mod['handler'], 'descriptor': mod['handlerDescriptor'], 'kind': mod['kind'], 'selector': mod['selectors'], 'refmapValue': mapping, 'plugin': config['plugin'], 'declarationMatched': True})
        evidence.append({'findingId': finding['id'], 'rule': finding['rule'], 'target': finding['target'], 'members': members, 'runtimeGroundTruth': 'UNAVAILABLE'})
    return {'manifestSha256': digest(manifest_path), 'packs': summaries, 'reviewSelection': 'All three HIGH BETWEEN_MODS findings in fabric-1.21.1; not a random sample', 'declarationReviews': evidence}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--real', action='store_true', help='Also audit already cached fixed real-mod JARs with javap (no download)')
    parser.add_argument('--javap', default=shutil.which('javap'), help='JDK javap executable for --real')
    args = parser.parse_args()
    result = {'scope': 'Authored controls plus optional static declaration review; no Minecraft or downloaded mod/plugin execution', 'metrics': {'precision': None, 'recall': None, 'reason': 'No representative Minecraft runtime ground truth. Risk is not a binary crash prediction.'}, 'authoredControls': fixtures()}
    if args.real:
        require(args.javap is not None, '--real requires JDK javap')
        result['realCorpus'] = real_corpus(args.javap)
    output = ROOT / 'build/evidence-review' / ('results.json' if args.real else 'authored-results.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f"Matched {result['authoredControls']['matchedCases']} authored controls" + ('; reviewed 6 corpus reports and 3 HIGH declaration pairs' if args.real else '') + f'; {output.relative_to(ROOT)}')

if __name__ == '__main__':
    main()
