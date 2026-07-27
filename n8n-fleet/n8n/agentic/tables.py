"""Single source of truth for the n8n Data Table IDs.

A fresh n8n mints its OWN ids when `fsm-setup` runs — they are NOT the fix-java-bugs ids. The parent
repo hard-coded the same four ids in three different generators, so pointing the fleet at a new n8n
meant finding every copy; miss one and that generator silently reads/writes a table that does not
exist on this instance.

After running the setup workflow, run:  python3 sync_tables.py
which reads the ids back out of the n8n database and rewrites this file.
"""

# Placeholders until sync_tables.py fills them in from the live instance.
SUSPICIONS_TABLE = "kC7PYCiSwfxNtfiC"
BUGS_TABLE = "ZQ1hwP73Ce8y0GJi"
SCAN_FILES_TABLE = "DfDnATGu91YbyyuD"
METHOD_RUNS_TABLE = "25YC8Onozyac6H9t"

_PLACEHOLDER_PREFIX = "REPLACE_"


def check():
    """Fail loudly at generate time rather than silently emitting a workflow that points nowhere."""
    missing = [n for n, v in (("suspicions", SUSPICIONS_TABLE), ("bugs", BUGS_TABLE),
                              ("scan_files", SCAN_FILES_TABLE), ("method_runs", METHOD_RUNS_TABLE))
               if v.startswith(_PLACEHOLDER_PREFIX)]
    if missing:
        raise SystemExit(
            "tables.py still has placeholder ids for: " + ", ".join(missing) + "\n"
            "Run the fsm-setup workflow on the target n8n, then `python3 sync_tables.py`.")
