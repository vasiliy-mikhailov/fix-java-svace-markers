#!/usr/bin/env python3
"""Tests for the search/replace edit applier.

Regression origin (found by e2e, 2026-07-27): the reproducer PROVED the TAINTED_PTR marker in
Assignment5.java — a JUnit test that compiled, ran, and failed on unpatched code — and the fixer then
produced a correct parameterised-query patch that was thrown away with "old_str not found".

WebGoat is google-java-format'ed, so the wrapped concatenation puts `+` at the START of each
continuation line. The model quoted it back with `+` at the END of the previous line. Identical
tokens, different wrapping, no byte-exact match. The result was `fix_failed` on a real, reproduced
bug — the worst possible outcome, because the defect was demonstrated and the fix existed.

    python3 test_apply_edit.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from runner import apply_edit  # noqa: E402

FAILED = []


def check(label, cond, detail=""):
    if cond:
        print("  ok   %s" % label)
    else:
        print("  FAIL %s %s" % (label, detail))
        FAILED.append(label)


# The real file, verbatim from the container (operators lead the continuation lines).
SRC = '''    try (var connection = dataSource.getConnection()) {
      PreparedStatement statement =
          connection.prepareStatement(
              "select password from challenge_users where userid = '"
                  + username_login
                  + "' and password = '"
                  + password_login
                  + "'");
      ResultSet resultSet = statement.executeQuery();
    }
'''

# What the fixer actually sent (operators trail the previous lines).
OLD = '''      PreparedStatement statement =
          connection.prepareStatement(
              "select password from challenge_users where userid = '" +
                  username_login +
                  "' and password = '" +
                  password_login +
                  "'");
      ResultSet resultSet = statement.executeQuery();'''

NEW = '''      PreparedStatement statement =
          connection.prepareStatement(
              "select password from challenge_users where userid = ? and password = ?");
      statement.setString(1, username_login);
      statement.setString(2, password_login);
      ResultSet resultSet = statement.executeQuery();'''


def main():
    print("the real e2e failure")
    # Red, documented: byte-exact matching cannot see it at all.
    check("byte-exact match genuinely fails", SRC.count(OLD) == 0)
    out, note = apply_edit(SRC, OLD, NEW)
    check("edit applies anyway", out is not None, str(note))
    check("caller is told it was fuzzy", "whitespace" in (note or ""), repr(note))
    if out:
        check("concatenation is gone", "+ username_login" not in out)
        check("placeholders present", "userid = ? and password = ?" in out)
        check("setString calls added", out.count("statement.setString") == 2)
        check("surrounding code untouched",
              out.startswith("    try (var connection = dataSource.getConnection()) {"))
        check("trailing code kept", "ResultSet resultSet = statement.executeQuery();" in out)
        check("file indentation preserved (no double indent)", "\n            PreparedStatement" not in out)

    print("\nexact matching still preferred")
    out, note = apply_edit("a\n  b\nc\n", "  b", "  B")
    check("exact match applies", out == "a\n  B\nc\n", repr(out))
    check("exact match reports no note", note == "", repr(note))

    print("\nsafety: ambiguity is refused, not guessed")
    out, note = apply_edit("x = 1;\nx = 1;\n", "x = 1;", "x = 2;")
    check("duplicate exact match refused", out is None and "not unique" in note, repr(note))
    # Two candidates that BOTH differ from old_str only in whitespace, so neither matches byte-exactly
    # and the fuzzy path has no basis to choose. It must refuse rather than patch an arbitrary one.
    amb = "f(a,\n b);\nf(a,  b);\n"
    check("neither candidate matches exactly", amb.count("f(a, b);") == 0)
    out, note = apply_edit(amb, "f(a, b);", "g();")
    check("ambiguous fuzzy match refused", out is None and "ambiguous" in (note or ""), repr(note))

    print("\nsafety: a genuinely absent string is still absent")
    out, note = apply_edit(SRC, "totallyUnrelated(123);", "x")
    check("missing old_str rejected", out is None and "not found" in note, repr(note))

    print("\nwhitespace-only differences")
    out, _ = apply_edit("int  x =\t1;\n", "int x = 1;", "int x = 2;")
    check("tabs and runs collapse", out is not None and "int x = 2;" in out, repr(out))

    print()
    if FAILED:
        raise SystemExit("%d check(s) FAILED: %s" % (len(FAILED), ", ".join(FAILED)))
    print("all checks passed")


if __name__ == "__main__":
    main()
