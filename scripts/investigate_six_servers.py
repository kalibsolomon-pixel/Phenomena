"""Read originals only; derived private evidence stays in ignored diagnostics."""
import argparse
import collections
import gzip
import json
import pathlib
import re

OUT = pathlib.Path('.local-diagnostics/0.1.5')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=pathlib.Path)
    args = parser.parse_args()
    ROOT = args.instance

    coverage, recent = [], []
    # Read every logging artifact, including mirrors and nested artifacts; never decompress to source.
    for p in sorted((ROOT / 'logs').rglob('*')):
        if not p.is_file():
            continue
        try:
            raw = gzip.open(p, 'rt', encoding='utf-8', errors='replace').read() if p.suffix == '.gz' else p.read_text(encoding='utf-8', errors='replace')
        except (OSError, EOFError) as exc:
            coverage.append({'file': str(p), 'error': str(exc)})
            continue
        connections = [{'line': i, 'text': line} for i, line in enumerate(raw.splitlines(), 1) if 'Connecting to ' in line]
        coverage.append({'file': str(p), 'lines': len(raw.splitlines()), 'connections': connections})
        if p.name in ('latest.log', '2026-10-24-1.log.gz'):
            selected = [{'file': p.name, 'line': i, 'text': line} for i, line in enumerate(raw.splitlines(), 1) if '[CHAT]' in line or 'Setting user:' in line or 'Connecting to ' in line or re.search(r'kwamster|disconnect', line, re.I)]
            recent.extend(selected)
    (OUT / 'log-coverage.json').write_text(json.dumps(coverage, indent=2), encoding='utf-8')
    (OUT / 'recent-log-evidence.json').write_text(json.dumps(recent, indent=2, ensure_ascii=False), encoding='utf-8')
    (OUT / 'recent-chat.txt').write_text('\n'.join(f"{e['file']}:{e['line']} {e['text']}" for e in recent), encoding='utf-8')
    diagnostics = []
    for p in sorted((ROOT / 'kwamster/diagnostics').rglob('*.json')):
        data = json.loads(p.read_text(encoding='utf-8'))
        diagnostics.append({'file': str(p), 'keys': list(data) if isinstance(data, dict) else 'list', 'data': data})
    (OUT / 'diagnostic-evidence.json').write_text(json.dumps(diagnostics, indent=2, ensure_ascii=False), encoding='utf-8')
    print('Logging artifacts read:', len(coverage), 'diagnostic JSON files:', len(diagnostics))
    for e in coverage:
        if e['file'].endswith(('latest.log', '2026-10-24-1.log.gz')):
            print(e['file'], e.get('connections'))
    for e in diagnostics:
        d=e['data']
        print(pathlib.Path(e['file']).name, e['keys'], len(d.get('entries', [])) if isinstance(d, dict) else len(d))

if __name__ == '__main__':
    main()
