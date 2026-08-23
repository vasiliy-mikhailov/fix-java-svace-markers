#!/usr/bin/env python3
"""
THE CHECKER REFERENCE, TAKEN FROM THE ANALYSERS RATHER THAN WRITTEN HERE.

This program judges Svace markers. What a checker MEANS is the analyser's fact, not this
repository's, and the 48 hand-written notes this replaces were neither: they were generated in one
session against one subject, they grew WebGoat's class names and lesson architecture, and several of
them dictated the settlement outright. They also could not travel — point the pipeline at a
different repository and they are wrong, point it at a different Svace and the checker names differ.

THREE CATALOGUES, BECAUSE `FB.*` ARE IMPORTED DETECTORS. Svace documents its own checkers; the
`FB.` prefix marks a SpotBugs detector, and the security ones come from the find-sec-bugs plugin.
Each source is the tool describing its own checker.

EVERY CHECKER, NOT THE ONES ONE CORPUS HAPPENED TO PRODUCE. The next repository will raise checkers
this one never did, and a reference that covers only what has already been seen is a reference that
is missing exactly when it is needed.

    python3 agent/scripts/checker-catalogue.py <svace.html> <spotbugs.html> <find-sec-bugs.html> \
        > agent/src/main/resources/checkers.tsv

The pages are not always reachable from a developer machine; fetching them is a separate step, and
the OUTPUT is committed so that neither the build nor a prove depends on three websites being up.
"""
import html
import re
import sys


def text(fragment: str) -> str:
    """Markup to one line of readable prose."""
    t = re.sub(r"<(script|style)[^>]*>.*?</\1>", " ", fragment, flags=re.S | re.I)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"\s+", " ", html.unescape(t)).strip()


def sections(page: str, ids: list[tuple[str, int]], stop: int) -> dict[str, str]:
    """Each anchor's markup, up to the next anchor — the catalogues are flat, so this is enough."""
    out = {}
    for i, (name, at) in enumerate(ids):
        end = ids[i + 1][1] if i + 1 < len(ids) else min(at + stop, len(page))
        out[name] = page[at:end]
    return out


def table(fragment: str) -> str:
    """Svace states severity and reliability per LANGUAGE, in a table.

    Flattened by a tag-stripper it reads `Language Situation Severity Reliability Enabled Java
    Quality Major Unknown Yes`, which is worse than useless in front of fifteen agents. Read as a
    table it is one clause. Nothing else in these pages is tabular, so this is the only special case.
    """
    head = [text(c) for c in re.findall(r"<th[^>]*>(.*?)</th>", fragment, re.S)]
    if not head:
        return ""
    said = []
    for row in re.findall(r"<tr[^>]*>((?:\s*<td.*?</td>\s*)+)</tr>", fragment, re.S):
        cells = [text(c) for c in re.findall(r"<td[^>]*>(.*?)</td>", row, re.S)]
        pairs = [f"{h.lower()} {v.lower()}" for h, v in zip(head[1:], cells[1:]) if v]
        if cells:
            said.append(f"{cells[0]}: " + ", ".join(pairs))
    return "; ".join(said)


def entry(name: str, fragment: str) -> str:
    """One checker, as prose: what it is called, its per-language grading, then what it reports."""
    attributes = table(fragment)
    body = text(re.sub(r"<table.*?</table>", " ", fragment, flags=re.S))
    # THE ANCHOR ITSELF IS NOT CONTENT. `id="X">X` and `name="X">` open most of these fragments and
    # reached the agent verbatim in the first cut of this file.
    body = re.sub(r'^(?:id|name)="[^"]*">\s*', "", body)
    if body.startswith(name):
        body = body[len(name):].strip()
    parts = [p for p in (attributes, body) if p]
    return " — ".join(parts)[:2000]


def svace(page: str) -> dict[str, str]:
    ids = [(m.group(1), m.start()) for m in re.finditer(r'id="([A-Z][A-Z0-9_.]{2,})"', page)]
    return sections(page, ids, 4000)


def spotbugs(page: str) -> dict[str, str]:
    """Slugged sections; the detector name is parenthesised in the heading, not in the id.

    A `<span id="…">` sits between the section and its heading, so the two cannot be matched as
    adjacent — the headings are found on their own and the name read out of the title.
    """
    ids = []
    for m in re.finditer(r"<h\d[^>]*>(.*?)</h\d>", page, re.S):
        found = re.findall(r"\(([A-Z][A-Z0-9_]{3,})\)", text(m.group(1)))
        if found:
            ids.append((found[-1], m.start()))
    return sections(page, ids, 4000)


def findsecbugs(page: str) -> dict[str, str]:
    """`<a class="anchor" name="NAME">`, the old-style anchor — this page carries no matching id."""
    ids = [(m.group(1), m.start()) for m in re.finditer(r'name="([A-Z][A-Z0-9_]{3,})"', page)]
    return sections(page, ids, 4000)


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    pages = [open(p, encoding="utf-8", errors="replace").read() for p in sys.argv[1:]]
    found = [("svace", svace(pages[0])),
             ("spotbugs", spotbugs(pages[1])),
             ("find-sec-bugs", findsecbugs(pages[2]))]
    rows = {}
    for source, entries in found:
        for name, body in entries.items():
            # A DETECTOR IS REACHED BY THE NAME THE MARKER CARRIES. Svace prefixes an imported
            # SpotBugs detector with `FB.`; the plugins document it unprefixed.
            key = name if source == "svace" else "FB." + name
            said = entry(name, body)
            if key in rows or len(said) < 60:
                continue
            rows[key] = (source, said)
    print("checker\tsource\tdescription")
    for key in sorted(rows):
        source, body = rows[key]
        print(f"{key}\t{source}\t{body}")
    print(f"  {len(rows)} checkers from {len(found)} catalogues", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
