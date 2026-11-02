"""Summarize scripts/review_reports.mjs output: per report, worst values across viewports and tabs."""
import json, sys, collections
r = json.load(open(sys.argv[1], encoding='utf-8'))
print(r['browser'])
issues = collections.Counter()
for rep in r['reports']:
    worst = collections.defaultdict(int); small = set(); off = set(); esc = set()
    for v in rep['views']:
        a = v['audit']
        for k in ('pageOverflowPx', 'svgTextTooSmall', 'svgTextOutside', 'svgOverlaps'):
            if a[k]:
                worst[k] = max(worst[k], a[k]); issues[(k, v['viewport'], v['tab'])] += a[k]
        aa = v.get('afterInteraction') or {}
        for k in ('pageOverflowPx', 'svgTextOutside', 'svgOverlaps'):
            if aa.get(k): worst['after.' + k] = max(worst['after.' + k], aa[k])
        small.update(a['smallText']); off.update(a['offscreen']); esc.update(a.get('escapesCard', []))
        worst['errors'] += len(v['errors']); worst['externalRequests'] += len(v['externalRequests'])
    t = [v['timing']['loadMs'] for v in rep['views']]; sw = [v['switchMs'] for v in rep['views']]
    nodes = max(v['audit']['domNodes'] for v in rep['views'])
    print(f"{rep['name']:24} {rep['bytes']:>9} B load {min(t)}-{max(t)} ms switch<= {max(sw)} ms domNodes<= {nodes} actions {sum(v['actions'] for v in rep['views'])} {dict(worst)} small={sorted(small)[:4]} off={sorted(off)[:4]} escapesCard={sorted(esc)[:4]}")
for k, n in sorted(issues.items()): print('  ', k, n)
