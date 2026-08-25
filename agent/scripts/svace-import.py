#!/usr/bin/env python3
"""A Svace JSON export -> this program's two inputs, for one project at one branch.

    svace-import.py EXPORT.json PROJECT BRANCH REPO_URL TREE OUT_DIR

Writes `markers.txt` lines and `severities.tsv` rows to OUT_DIR, and reports what it dropped.

WHY IT TAKES THE TREE. The analyser records absolute paths from the machine that ran it, and this
export carries at least two different roots for one project -- `/.build/...` and
`/builds/gitlab/nrdirect/ca2/ca2_back/...`. A stripper written against the first produces keys that
match nothing for every marker written under the second, and nothing fails: the queue is accepted,
every prove opens a file that is not there, and three hundred markers settle as though the code were
missing. So each path is resolved against the actual tree and a marker whose file cannot be found is
reported rather than queued.

THE EXPORT IS BIGGER THAN THE SUBJECT. 169,211 markers across 18 projects and 177 project-branch
snapshots, in one 416 MB array; only the pair named on the command line is wanted. It is read
incrementally for that reason, one object at a time, and never held whole.
"""
import json
import os
import sys
from collections import Counter


def objects(path):
    """Top-level objects of a pretty-printed JSON array, one at a time.

    Line-based on purpose: a character-by-character parser over this file spent minutes and was
    killed twice before finishing. The export is written one object per block at indent 1.
    """
    buf, inside = [], False
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            if not inside:
                if line == " {":
                    inside, buf = True, ["{"]
                continue
            if line in (" }", " },"):
                yield json.loads("\n".join(buf) + "}")
                inside, buf = False, []
                continue
            buf.append(line)


def in_tree(reported, tree):
    """The longest suffix of the analyser's path that names a file in the tree, or None.

    Walking from the left rather than stripping a known prefix: the prefixes differ per snapshot and
    a new one appears whenever the build moves. What does not change is that the tail of the path is
    the repository-relative one.
    """
    parts = [p for p in reported.split("/") if p]
    for start in range(len(parts)):
        candidate = "/".join(parts[start:])
        if os.path.isfile(os.path.join(tree, candidate)):
            return candidate
    return None


def main(export, project, branch, repo, tree, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    seen, rows, severities = set(), [], {}
    kept = Counter()
    dropped = Counter()
    missing_examples = []

    for marker in objects(export):
        if marker.get("_project") != project or marker.get("_branch") != branch:
            continue
        reported, line = marker.get("file", ""), marker.get("line")
        checker = marker.get("warnClass", "")
        if not reported or not line or not checker:
            dropped["incomplete row"] += 1
            continue
        found = in_tree(reported, tree)
        if found is None:
            dropped["file not in the tree"] += 1
            if len(missing_examples) < 5:
                missing_examples.append(reported)
            continue
        key = "%s|%s|%s|%s" % (repo, found, line, checker)
        if key in seen:
            # One warning appears once per trace role in the export; the queue wants it once.
            dropped["duplicate of an earlier row"] += 1
            continue
        seen.add(key)
        rows.append(key)
        kept[marker.get("checker_severity", "")] += 1
        # FIVE FIELDS, NAMING THE SUBJECT. `file|line|checker` is unique inside one project and is
        # not across two, and this program can now carry more than one.
        severities[(repo, found.rsplit("/", 1)[-1], str(line), checker)] = \
            marker.get("checker_severity", "")

    with open(os.path.join(out_dir, "markers.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(rows) + ("\n" if rows else ""))
    with open(os.path.join(out_dir, "severities.tsv"), "w", encoding="utf-8") as handle:
        for (r, base, line, checker), severity in severities.items():
            handle.write("\t".join([r, base, line, checker, severity]) + "\n")

    print("queued   %d marker(s)" % len(rows))
    print("severity %s" % dict(kept))
    print("dropped  %s" % (dict(dropped) or "nothing"))
    for example in missing_examples:
        print("           not in the tree: %s" % example)


if __name__ == "__main__":
    if len(sys.argv) != 7:
        print(__doc__)
        sys.exit(2)
    main(*sys.argv[1:])
