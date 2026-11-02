"""Static offline report contracts and JavaScript syntax; does not claim browser interaction QA."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('folders',nargs='+',type=pathlib.Path)
    ap.add_argument('--output',type=pathlib.Path,default=pathlib.Path('.local-diagnostics/0.1.5/report-static'))
    args=ap.parse_args()
    out=args.output
    out.mkdir(parents=True,exist_ok=True)
    scripts=set()
    count=rounds=manual=0
    for folder in args.folders:
        for html in folder.rglob('index.html'):
            text=html.read_text(encoding='utf-8')
            raw=json.loads(html.with_name('report.json').read_text(encoding='utf-8'))
            payload=json.loads(re.search(r'<script id="data" type="application/json">(.*?)</script>',text,re.S)[1])
            fight=payload['fight']
            assert raw['id']==fight['id'] and raw['outcome']==fight['outcome'],html
            summary=payload['derived']['summary']
            stats=fight['statistics']
            if stats.get('measurementVersion',0):
                attacks=[e for e in fight['events'] if e['kind']=='ATTACK' and e.get('actor')=='local']
                ids=stats['attackCanonicalIds']; classes=stats['attackClassifications']
                assert summary['totalInputs']==len(attacks)==len(ids),html
                assert summary['uniqueAttacks']==len(set(ids.values()))==len(classes),html
                assert summary['deduplicatedAttacks']==len(attacks)-len(classes),html
                assert summary['excludedSwings']==sum(v=='EXCLUDED' for v in classes.values()),html
                assert summary['attempts']==len(classes)-summary['excludedSwings'],html
                assert summary['hits']==sum(v=='HIT' for v in classes.values()),html
                assert summary['eligibleMisses']==sum(v=='MISS' for v in classes.values()),html
                assert summary['unresolved']==sum(v=='UNRESOLVED' for v in classes.values()),html
                assert summary['uncertainShield']==sum(v=='UNCERTAIN_SHIELD' for v in classes.values()),html
                assert summary['accuracyDenominator']==summary['attempts']-sum(v=='SHIELD_BLOCK' for v in classes.values()),html
                assert len(payload['derived']['hits'])==summary['hits'],html
                assert abs(sum(e['end']-e['start'] for e in payload['derived']['combatExchanges'])-summary['combatActiveSeconds'])<1e-6,html
                for sample in payload['derived']['timingSamples']:
                    assert str(sample['seq']) in classes and classes[str(sample['seq'])]!='EXCLUDED',html
            assert "connect-src 'none'" in text and "default-src 'none'" in text,html
            assert not re.search(r'<(?:script|link|img|iframe)[^>]+(?:src|href)\s*=\s*[\"\'](?:https?:)?//',text,re.I),html
            code='\n'.join(re.findall(r'<script>(.*?)</script>',text,re.S))
            assert not re.search(r'\b(?:fetch|XMLHttpRequest|WebSocket|EventSource)\s*\(',code),html
            digest=hashlib.sha256(code.encode()).hexdigest()
            if digest not in scripts:
                file=out/(digest+'.js')
                file.write_text(code,encoding='utf-8')
                subprocess.run(['node','--check',str(file)],check=True,capture_output=True,text=True)
                scripts.add(digest)
            evidence=fight.get('matchEvidence',{})
            assert evidence.get('rounds',[])==raw.get('matchEvidence',{}).get('rounds',[]),html
            if evidence.get('rounds'):
                rounds+=1
                assert "'Match rounds'" in code and 'Not announced' in code,html
            if fight.get('session'):
                manual+=1
                assert "'Participant filter'" in code and "'Exchange filter'" in code,html
            assert 'comboTimeline' in code and 'dominance' in code,html
            count+=1
    result=dict(reports=count,reportsWithRounds=rounds,manualReports=manual,uniqueScripts=len(scripts),
        syntax='PASS',embeddedData='PASS',offlineRestrictions='PASS',browserInteractions='NOT TESTED by static checker')
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))

if __name__=='__main__': main()
