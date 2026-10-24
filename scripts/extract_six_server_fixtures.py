"""Derive reviewed public grammar fixtures and private source references without changing originals.

Run investigate_six_servers.py first. All source references and participant identities stay ignored.
Labels are owner-provided, independent of parser predictions. Log CHAT origins are approximations;
packet origin is used when an exact diagnostic observation exists.
"""
import datetime as dt
import json
import pathlib
import re
import statistics

OUT = pathlib.Path('.local-diagnostics/0.1.5')


def clock_epoch(line):
    clock = re.search(r'\[(\d\d:\d\d:\d\d)\]', line)[1]
    return int(dt.datetime.fromisoformat('2026-10-24T'+clock+'-04:00').timestamp()*1000)

def flat(s):
    return ' '.join(re.sub('§.', '', s.replace(r'\n', '\n')).split())

def is_round_boundary(host, text):
    t=flat(text.split('[CHAT] ',1)[-1])
    if host=='east.catpvp.net': return bool(re.fullmatch(r'\w+ won the round\.',t))
    if host=='fishffa.gg': return bool(re.fullmatch(r'⭐ Winner: \w+ \(\d+(?:\.\d+)?❤\)',t))
    if host=='pvplegacy.net': return bool(re.fullmatch(r'Round \d+ has started\.|\w+ has won the round\. They need to win \d+ more round to win\.',t))
    if host=='minemen.club': return bool(re.fullmatch(r'Round \d+ started!|\w+(?: \([^()]*\))? won! \d+ - \d+',t))
    return False

def main():
    rows = [e for e in json.loads((OUT/'recent-log-evidence.json').read_text(encoding='utf-8')) if e['file']=='latest.log' and re.match(r'\[\d\d:\d\d:\d\d\]',e['text'])]
    LOCAL = next(re.search(r'Setting user: (\w+)', e['text'])[1] for e in rows if 'Setting user:' in e['text'])
    ds = json.loads((OUT/'diagnostic-evidence.json').read_text(encoding='utf-8'))
    packets = [dict(e, source=pathlib.Path(d['file']).name, index=i) for d in ds for i,e in enumerate(d['data']['entries'])]
    # Reviewed boundaries: actual log lines, except MCPvP starts and FishFFA loss use packet entries.
    spec = [
        ('mcpvp.club',1,'LOSS',None,880),('mcpvp.club',2,'WIN',None,907),('mcpvp.club',3,'WIN',None,1010),
        ('east.catpvp.net',1,'WIN',1123,1220),('east.catpvp.net',2,'WIN',1376,1498),('east.catpvp.net',3,'WIN',1642,1698),
        ('minemen.club',1,'WIN',1837,1900),('minemen.club',2,'WIN',1923,2050),('minemen.club',3,'WIN',2091,2272),
        ('pvphq.com',1,'WIN',2363,2373),('pvphq.com',2,'WIN',2446,2458),('pvphq.com',3,'WIN',2556,2565),
        ('fishffa.gg',1,'WIN',2678,2703),('fishffa.gg',2,'LOSS',2733,None),('fishffa.gg',3,'WIN',2804,2823),
        ('pvplegacy.net',1,'WIN',2871,2875),('pvplegacy.net',2,'WIN',2904,2929),('pvplegacy.net',3,'WIN',2960,2963),
    ]
    index={e['line']:e for e in rows}
    recordings=json.loads((OUT/'recording-index.json').read_text(encoding='utf-8'))
    fixtures, matrix, captured=[] , [], []
    clock_offset = int(statistics.median(p['nanos']-p['timestamp']*1_000_000 for p in packets if p['channel']!='LIFECYCLE'))
    mcp_starts=[]
    for p in packets:
        if p['server']=='mcpvp.club' and p.get('text')=='⚔ FIGHT! ⚔':
            if not mcp_starts or p['timestamp']-mcp_starts[-1]['timestamp']>10000:
                mcp_starts.append(p)
    for host,n,label,startline,endline in spec:
        end=index.get(endline)
        endp=None
        if end is None:
            endp=next(p for p in packets if p['server']==host and p.get('text')=='You lost the duel!')
        hi=clock_epoch(end['text']) if end else endp['timestamp']
        start=index.get(startline)
        startp=mcp_starts[n-1] if start is None else None
        lo=clock_epoch(start['text']) if start else startp['timestamp']
        segment=[e for e in rows if lo<=clock_epoch(e['text'])<=hi+1000 and '[CHAT] ' in e['text']]
        texts=[e['text'].split('[CHAT] ',1)[1] for e in segment]
        if host=='mcpvp.club':
            kill=next(flat(t) for t in texts if re.fullmatch(r'\w+ was (?:smashed|shot) by \w+',flat(t)))
            opponent=next(w for w in (kill.split()[0],kill.split()[-1]) if w!=LOCAL)
        elif host=='pvplegacy.net':
            kill=next(flat(t) for t in texts if re.fullmatch(r'\w+ was slain by \w+',flat(t)))
            opponent=next(w for w in (kill.split()[0],kill.split()[-1]) if w!=LOCAL)
        else:
            opponent=re.search(r'Opponent: (\w+)',start['text'])[1]
        messages=[]
        for e in segment:
            text=e['text'].split('[CHAT] ',1)[1]
            f=flat(text)
            if '[System] [CHAT]' not in e['text'] or '[KWAMSTER]' in text:
                continue
            keep=bool(re.search(r'(Opponent:|Match Results|Match Complete| won the (?:round|game)|Winner:|Loser:|MATCH REPORT|Round \d+ (?:has )?started|Round \d+ started!|Match started!| won!|was (?:killed|slain|smashed|shot) by|\[GameManager\] Game has ended)',f))
            if not keep: continue
            # Only the public server grammar above is exported. Local names are replaced per match.
            text=text.replace(LOCAL,'PlayerA').replace(opponent,'PlayerB')
            exact=[p for p in packets if p['server']==host and p.get('text') is not None and flat(p['text'])==f and abs(p['timestamp']-clock_epoch(e['text']))<1500]
            origin=exact[0]['channel'] if exact else 'SYSTEM'
            messages.append({'t':round((clock_epoch(e['text'])-lo)/1000,3),'text':text,'origin':origin})
        refs=[]
        if startp:
            messages.insert(0,{'t':0,'text':startp['text'],'origin':startp['channel']})
            refs.append({'source':startp['source'],'eventIndex':startp['index'],'role':'start'})
        else:
            refs.append({'source':'latest.log','line':startline,'role':'start'})
        if endp:
            messages.append({'t':round((hi-lo)/1000,3),'text':endp['text'],'origin':endp['channel']})
            refs.append({'source':endp['source'],'eventIndex':endp['index'],'role':'end'})
        else:
            refs.append({'source':'latest.log','line':endline,'role':'end'})
        # Preserve repeated title packets to test duplicate suppression, including the real spacing.
        if startp:
            for p in packets:
                if p['server']==host and p.get('text')=='⚔ FIGHT! ⚔' and lo<p['timestamp']<lo+4000:
                    messages.append({'t':round((p['timestamp']-lo)/1000,3),'text':p['text'],'origin':'TITLE'})
        messages.sort(key=lambda m:m['t'])
        existing=[f for f in recordings if f.get('opponentLabel')==opponent and f['startEpochMillis']<=hi+1500 and f['endEpochMillis']>=lo]
        fixtures.append(dict(server=host,match=n,ownerResult=label,opponent='PlayerB',duration=round((hi-lo)/1000,3),messages=messages))
        replay_messages=[]
        for message in messages:
            raw=message['text'].replace('PlayerA',LOCAL).replace('PlayerB',opponent)
            epoch=lo+message['t']*1000
            exact=[p for p in packets if p['server']==host and p.get('text') is not None and flat(p['text'])==flat(raw) and p['channel']==message['origin'] and abs(p['timestamp']-epoch)<1500]
            packet=min(exact,key=lambda p:abs(p['timestamp']-epoch)) if exact else None
            replay_messages.append(dict(nanos=packet['nanos'] if packet else int(epoch*1_000_000+clock_offset),
                text=raw,origin=message['origin'],timing='packet' if packet else 'log second aligned to packet clock'))
        actual_events={}
        labels={}
        for recording in existing:
            raw_record=json.loads(pathlib.Path(recording['file']).read_text(encoding='utf-8'))
            if raw_record.get('opponent'): labels[raw_record['opponent']]=raw_record.get('opponentLabel')
            for event in raw_record['events']: actual_events[event['sequence']]=event
        captured.append(dict(server=host,match=n,ownerResult=label,local=LOCAL,labels=labels,
            messages=replay_messages,events=sorted(actual_events.values(),key=lambda e:e['nanoTime'])))
        matrix.append(dict(server=host,ownerMatch=n,ownerResult=label,sourceLog='latest.log',session='2026-10-24 '+host,
            opponent=opponent,startEpochMillis=lo,endEpochMillis=hi,references=refs,
            startEvidence=start['text'] if start else startp['text'],endEvidence=end['text'] if end else endp['text'],
            roundBoundaries=[dict(line=e['line'],text=e['text']) for e in segment if is_round_boundary(host,e['text'])],
            existing=[{k:f[k] for k in ('id','outcome','reason','startEpochMillis','endEpochMillis')} for f in existing],
            existingStartCorrect=bool(existing and abs(existing[0]['startEpochMillis']-lo)<2000),
            existingEndCorrect=bool(len(existing)==1 and abs(existing[0]['endEpochMillis']-hi)<2000),
            correctedResult=label,correctedValidation='Pending replay',confidence='High outcome/session correlation; opponent binding inferred on unnamed starts',
            ambiguity='Owner reports 3-1; captured final score is 2-1' if host=='east.catpvp.net' and n==1 else
                'Second round victory is owner-confirmed; only first round result and final duel result are explicit' if host=='fishffa.gg' and n==3 else
                'Loss requires captured subtitle; no result block in Minecraft log' if host=='fishffa.gg' and n==2 else 'None for overall outcome'))
    target=pathlib.Path('src/test/resources/fixtures/six-servers.json')
    target.write_text(json.dumps(dict(description='18 owner labels; redacted observed grammar; combat in replay is synthetic. Relative times from logs/packets; raw references remain private.',fixtures=fixtures),indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    (OUT/'correlation-matrix.json').write_text(json.dumps(matrix,indent=2,ensure_ascii=False),encoding='utf-8')
    (OUT/'captured-replay.json').write_text(json.dumps(captured,ensure_ascii=False),encoding='utf-8')
    print('Extracted',len(fixtures),'owner-labeled fixtures')
    for m in matrix:
        print(m['server'],m['ownerMatch'],m['ownerResult'],[f['outcome'] for f in m['existing']],m['existingStartCorrect'],m['existingEndCorrect'])

if __name__=='__main__': main()
