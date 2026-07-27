#!/usr/bin/env python3
"""Read the Data Table ids back out of the live n8n DB and rewrite tables.py.

Run this ONCE after the fsm-setup workflow has created the tables on a fresh n8n:

    docker exec fsm-n8n ...            # (setup workflow runs, tables get created)
    python3 sync_tables.py             # on the host, against the mounted n8n-data

Why a script and not manual editing: the ids are random per instance, they appear in several
generators, and a wrong-but-plausible id fails at RUN time (empty reads, no rows written) rather
than at generate time. Reading them from the source of truth removes the transcription step.
"""
import os
import re
import sqlite3
import sys

DEFAULT_DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "n8n-data", "database.sqlite")
WANTED = {"suspicions": "SUSPICIONS_TABLE", "bugs": "BUGS_TABLE",
          "scan_files": "SCAN_FILES_TABLE", "method_runs": "METHOD_RUNS_TABLE"}


def main():
    db_path = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DB)
    if not os.path.exists(db_path):
        raise SystemExit("no n8n database at " + db_path + " (pass the path as argv[1])")
    con = sqlite3.connect("file:" + db_path + "?mode=ro", uri=True)
    rows = con.execute("select id, name from data_table").fetchall()
    con.close()
    found = {name: tid for tid, name in rows}

    missing = [n for n in WANTED if n not in found]
    if missing:
        raise SystemExit("these tables do not exist yet in " + db_path + ": " + ", ".join(missing)
                         + "\nRun the fsm-setup workflow first. Present: "
                         + (", ".join(sorted(found)) or "(none)"))

    tp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tables.py")
    with open(tp) as f:
        src = f.read()
    for name, const in WANTED.items():
        src = re.sub(r'^' + const + r' = ".*"$', const + ' = "' + found[name] + '"', src, flags=re.M)
    with open(tp, "w") as f:
        f.write(src)
    for name, const in sorted(WANTED.items()):
        print("%-14s %s = %s" % (name, const, found[name]))
    print("\nwrote tables.py — now regenerate the workflows.")


if __name__ == "__main__":
    main()
