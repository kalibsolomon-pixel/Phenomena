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
    args=ap.parse_args()
    out=pathlib.Path('.local-diagnostics/0.1.5/report-static')
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
                assert "'Participant '" in code and "'Exchange filter'" in code,html
            assert 'comboTimeline' in code and 'dominance' in code,html
            count+=1
    result=dict(reports=count,reportsWithRounds=rounds,manualReports=manual,uniqueScripts=len(scripts),
        syntax='PASS',embeddedData='PASS',offlineRestrictions='PASS',browserInteractions='BLOCKED: file protocol security policy')
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))

if __name__=='__main__': main()
