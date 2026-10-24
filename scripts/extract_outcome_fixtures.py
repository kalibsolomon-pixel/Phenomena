"""Extract anonymized match-outcome fixtures from Minecraft logs. Read-only.

python -I scripts/extract_outcome_fixtures.py INSTANCE --out src/test/resources/fixtures/outcomes.json

Each fixture is one announced match: the system-chat messages from its start announcement to the
next start (or a two-minute gap), with times relative to the start. Player names are replaced:
the local account becomes PlayerA, the announced opponent PlayerB, anyone else PlayerC, PlayerD...

Labels come from an independent consensus rule written here, not from KWAMSTER's Java adapters.
Match-level statements decide when present (Minemen paired results; PVPHQ winner/loser lines right
after MATCH REPORT). Otherwise the deciding round's winner, then the last winner/loser lines, then
the last kill message. Agreeing votes give WIN or LOSS, disagreement AMBIGUOUS, no vote NONE.
This measures the parser and lifecycle against the server's own statements. It is not independent
ground truth, and says nothing about servers not in the logs. No network access; the instance is
never modified.
"""
import argparse
import gzip
import json
import pathlib
import re

NAME = r"[A-Za-z0-9_]{1,16}"
START = [
    re.compile(r"^[A-Za-z][A-Za-z0-9 ]{0,40}? (?:Match|Duel) \W*Map: .+? \W*Opponent: (" + NAME + r") \W*Ping: \d+ ms$"),
    re.compile(r"^\W*Opponent: (" + NAME + r")$"),
]
RELEVANT = re.compile(
    r"Winner:|Loser:|killed by|won!|Round \d+ started!|Match started!|MATCH REPORT|UNRANKED MATCH|RANKED MATCH")
SERVERS = {"minemen.club": "minemen.club", "minemen.net": "minemen.club", "pvphq.com": "pvphq.com"}
NEWLINE = "\\n"  # literal backslash-n escapes as written in the log
PLACEHOLDER = "\x01"
KEYWORDS = {"Match", "Round", "MATCH", "UNRANKED", "RANKED", "Opponent", "Winner", "Loser"}


def flat(text):
    return " ".join(re.sub(r"§.", "", text.replace(NEWLINE, "\n")).split())


def server_of(host):
    host = host.lower().split(":")[0]
    for domain, name in SERVERS.items():
        if host == domain or host.endswith("." + domain):
            return name
    return None


def votes(messages, local, opponent):
    match, rounds, kills, lines = [], [], [], []
    report_at = None
    for m in messages:
        t = m["flat"]
        if "MATCH REPORT" in t:
            report_at = m["t"]
        r = re.search(r"Winner: (" + NAME + r")\W+Loser: (" + NAME + r")$", t)
        if r:
            match.append(r[1] == local)
            continue
        after_report = report_at is not None and m["t"] - report_at <= 2
        r = re.search(r"Winner: (" + NAME + r") \(", t)
        if r and r[1] in (local, opponent):
            (match if after_report else lines).append(r[1] == local)
        r = re.search(r"Loser: (" + NAME + r")(?: \([^()]*\))? \[Click\]$", t)
        if r and r[1] in (local, opponent):
            (match if after_report else lines).append(r[1] != local)
        r = re.search(r"^\W*(" + NAME + r") (?:was|has been) killed by (" + NAME + r")[!.]$", t)
        if r and {r[1], r[2]} == {local, opponent}:
            kills.append(r[2] == local)
        r = re.search(r"^(" + NAME + r")(?: \([^()]*\))? won! (\d+) - (\d+)$", t)
        if r and r[1] in (local, opponent):
            rounds.append(r[1] == local)
    if match:
        return match
    if rounds:
        return rounds[-1:]
    if lines:
        return lines[-2:]
    return kills[-1:]


def anonymize(text, mapping):
    names = sorted(mapping, key=len, reverse=True)
    work = text.replace(NEWLINE, PLACEHOLDER)
    pattern = "|".join(r"(?<![A-Za-z0-9_])" + re.escape(n) + r"(?![A-Za-z0-9_])" for n in names)
    work = re.sub(pattern, lambda m: mapping[m.group(0)], work)
    return work.replace(PLACEHOLDER, NEWLINE)


def participants(flat_text):
    """Names in the positions these formats use for players."""
    found = []
    for pattern in (r"(?:Winner|Loser): (" + NAME + r")",
                    r"killed by (" + NAME + r")",
                    r"^\W*(" + NAME + r") (?:was|has been) killed",
                    r"^(" + NAME + r")(?: \([^()]*\))? won!",
                    r"Opponent: (" + NAME + r")"):
        found += re.findall(pattern, flat_text)
    spectators = re.search(r"Spectators \(\d+\): (.*)$", flat_text)
    if spectators:
        found += re.findall(NAME, spectators[1])
    return [n for n in found if n not in KEYWORDS]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("instance", type=pathlib.Path)
    ap.add_argument("--out", type=pathlib.Path, required=True)
    args = ap.parse_args()
    logs = sorted((args.instance / "logs").glob("*.log.gz")) + [args.instance / "logs" / "latest.log"]
    groups = []
    for path in logs:
        if not path.exists():
            continue
        text = (gzip.open(path, "rt", encoding="utf-8", errors="replace").read()
                if path.suffix == ".gz" else path.read_text(encoding="utf-8", errors="replace"))
        local, host, group = None, None, None
        for line in text.splitlines():
            user = re.search(r"Setting user: (" + NAME + ")", line)
            if user:
                local = user[1]
            conn = re.search(r"Connecting to ([^,]+),", line)
            if conn:
                host = conn[1]
            clock = re.match(r"\[(\d\d):(\d\d):(\d\d)\]", line)
            if not clock or "[CHAT] " not in line or local is None:
                continue
            seconds = int(clock[1]) * 3600 + int(clock[2]) * 60 + int(clock[3])
            original = line.split("[CHAT] ", 1)[1]
            f = flat(original)
            server = server_of(host or "")
            if server is None:
                continue
            start = next((m for m in (p.match(f) for p in START) if m), None)
            if group and (start or seconds - group["last"] > 120 or seconds < group["first"]):
                groups.append(group)
                group = None
            if start:
                group = {"server": server, "opponent": start[1], "local": local, "first": seconds,
                         "last": seconds, "messages": []}
            if group and (start or RELEVANT.search(f)):
                group["messages"].append({"t": seconds - group["first"], "raw": original, "flat": f})
                group["last"] = seconds
        if group:
            groups.append(group)
    out = []
    for g in groups:
        v = votes(g["messages"], g["local"], g["opponent"])
        label = "NONE" if not v else "WIN" if all(v) else "LOSS" if not any(v) else "AMBIGUOUS"
        mapping = {g["local"]: "PlayerA", g["opponent"]: "PlayerB"}
        for m in g["messages"]:
            for n in participants(m["flat"]):
                if n not in mapping:
                    mapping[n] = "Player" + chr(ord("C") + len(mapping) - 2)
        out.append({
            "server": g["server"],
            "label": label,
            "rounds": sum(1 for m in g["messages"] if re.match(r"^Round \d+ started!$", m["flat"])),
            "messages": [{"t": m["t"], "text": anonymize(m["raw"], mapping)} for m in g["messages"]],
        })
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"description": __doc__.strip().splitlines()[0], "fixtures": out},
                                   indent=1, ensure_ascii=False), encoding="utf-8")
    counts = {}
    for f in out:
        key = f["server"] + "/" + f["label"]
        counts[key] = counts.get(key, 0) + 1
    print(json.dumps({"fixtures": len(out), "counts": counts}, indent=1))


if __name__ == "__main__":
    main()
