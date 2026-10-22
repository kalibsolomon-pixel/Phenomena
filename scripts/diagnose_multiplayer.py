"""Read-only multiplayer investigation. Output contains private data: keep it local.

python scripts/diagnose_multiplayer.py INSTANCE --out .local-diagnostics/multiplayer
No dependencies, network access, or modification of the supplied instance.
"""
import argparse
import collections
import datetime as dt
import gzip
import json
import pathlib
import re
from zoneinfo import ZoneInfo

NAME = r"[A-Za-z0-9_]{1,16}"
RESULT = re.compile(rf"Winner: ({NAME}).*?Loser: ({NAME})")
SINGLE = re.compile(rf"^[^\w]*Winner: ({NAME})(?: \([^\n]*\))?(?: \[Click\])?$")
KILL = re.compile(rf"^({NAME}) was killed by ({NAME})!$")
START = re.compile(rf"Opponent: ({NAME})")
ROUND = re.compile(r"^Round (\d+) started!$")


def analyze(instance, out, timezone="-04:00"):
    tz = dt.timezone(dt.timedelta(hours=int(timezone[:3]), minutes=int(timezone[4:]) * (-1 if timezone.startswith("-") else 1))) if re.fullmatch(r"[+-]\d\d:\d\d", timezone) else ZoneInfo(timezone)
    out.mkdir(parents=True, exist_ok=True)
    announcements, coverage, uncertain = [], [], []
    # Launcher mirror logs are inventoried but not counted twice.
    logs = sorted((instance / "logs").glob("*.log.gz"))
    logs += [instance / "logs/latest.log"]
    for file in logs:
        if not file.exists():
            continue
        text = (gzip.open(file, "rt", encoding="utf-8", errors="replace").read()
                if file.suffix == ".gz" else file.read_text(encoding="utf-8", errors="replace"))
        date = file.name[:10] if file.suffix == ".gz" else dt.datetime.fromtimestamp(file.stat().st_mtime, tz).date().isoformat()
        server, chat_count, last_seconds, day = "unknown", 0, -1, dt.date.fromisoformat(date)
        for number, line in enumerate(text.splitlines(), 1):
            clock = re.match(r"\[(\d\d):(\d\d):(\d\d)\]", line)
            if not clock:
                continue
            seconds = sum(int(n) * m for n, m in zip(clock.groups(), [3600, 60, 1]))
            if last_seconds - seconds > 12 * 3600:
                day += dt.timedelta(days=1)
            last_seconds = seconds
            connected = re.search(r"\]: Connecting to ([^,]+),", line)
            if connected:
                server = connected[1]
            if "[CHAT] " not in line:
                continue
            chat_count += 1
            original = line.split("[CHAT] ", 1)[1]
            msg = re.sub(r"§.", "", original.replace(r"\n", "\n")).strip()
            flat = " ".join(msg.split())
            kind, winner, loser, opponent = None, None, None, None
            result, single, kill, start = RESULT.search(flat), SINGLE.fullmatch(flat), KILL.fullmatch(flat), START.search(flat)
            if result and flat.startswith("Match Results (Click to view)"):
                kind, winner, loser = "result", result[1], result[2]
            elif single:
                kind, winner = "winner", single[1]
            elif kill:
                kind, loser, winner = "kill", kill[1], kill[2]
            elif start and (flat.startswith("Casual Match") or re.fullmatch(rf"[^\w]*Opponent: {NAME}", flat)):
                kind, opponent = "start", start[1]
            elif ROUND.fullmatch(flat):
                kind = "round-start"
            elif re.search(r"\b(?:Winner:|Loser:|Won|Lost|Round|round|killed|died|Starting|starts in)\b", flat):
                uncertain.append({"log": file.name, "line": number, "text": original, "reason": "Unrecognized or player-chat context; not authoritative"})
            if kind:
                timestamp = dt.datetime.combine(day, dt.time()).replace(tzinfo=tz) + dt.timedelta(seconds=seconds)
                announcements.append(dict(log=file.name, line=number, timestamp=timestamp.isoformat(), epochMillis=int(timestamp.timestamp()*1000), original=original, kind=kind, winner=winner, loser=loser, opponent=opponent, server=server, origin="log CHAT marker; packet origin unavailable"))
        coverage.append({"log": file.name, "chatMessages": chat_count})

    fights = []
    for file in sorted((instance / "kwamster/fights").rglob("*.json")):
        try:
            f = json.loads(file.read_text(encoding="utf-8"))
            fights.append({k: f.get(k) for k in ("id", "startEpochMillis", "endEpochMillis", "outcome", "reason", "opponentLabel")})
        except (ValueError, OSError):
            uncertain.append({"file": str(file), "reason": "Unreadable recording; preserved"})
    groups, current = [], {}
    for a in announcements:
        key = a["log"], a["server"]
        group = current.get(key)
        if a["kind"] in ("start", "round-start") or group is None or a["epochMillis"] - group["lastEpoch"] > 600000:
            group = {"log": a["log"], "server": a["server"], "announcements": [], "lastEpoch": a["epochMillis"]}
            groups.append(group)
            current[key] = group
        group["announcements"].append(a)
        group["lastEpoch"] = a["epochMillis"]
    matched = set()
    for group in groups:
        lo = min(a["epochMillis"] for a in group["announcements"])
        hi = group["lastEpoch"]
        names = {a[k] for a in group["announcements"] for k in ("opponent", "winner", "loser") if a[k]}
        candidates = [f for f in fights if f["startEpochMillis"] <= hi + 2000 and f["endEpochMillis"] >= lo - 2000 and f["opponentLabel"] in names]
        group["recordings"] = [f["id"] for f in candidates]
        group["correlation"] = "unmatched" if not candidates else "ambiguous / fragmented" if len(candidates) > 1 else "likely (wall clock and participant; not proof)"
        matched.update(f["id"] for f in candidates)
    reasons = collections.Counter(f["reason"] for f in fights)
    report = {"coverage": coverage, "groups": groups, "unrecognized": uncertain, "recordings": fights, "unmatchedRecordings": [f["id"] for f in fights if f["id"] not in matched], "reasonCounts": dict(reasons), "explanation": "Legacy tracker never read chat. Disconnected includes world/dimension replacement and unavailable worlds; Target changed splits participants; event cap interrupts long rounds. Opponent death alone was Unknown. Correlation is diagnostic and does not rewrite outcomes. Logs may omit title/actionbar announcements."}
    (out / "diagnostic.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    summary = {"logs": len(coverage), "logsWithChat": sum(c["chatMessages"] > 0 for c in coverage), "announcements": len(announcements), "groups": len(groups), "ambiguousGroups": sum(g["correlation"].startswith("ambiguous") for g in groups), "unmatchedGroups": sum(g["correlation"] == "unmatched" for g in groups), "recordings": len(fights), "unmatchedRecordings": len(report["unmatchedRecordings"]), "reasonCounts": dict(reasons), "explanation": report["explanation"]}
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=pathlib.Path)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--timezone", default="-04:00", help="Log timezone: explicit UTC offset, or IANA zone if tzdata is installed")
    args = parser.parse_args()
    analyze(args.instance.resolve(), args.out.resolve(), args.timezone)
