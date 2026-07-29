// @ts-nocheck
'use strict';
/**
 * What each Svace checker MEANS.
 *
 * The reproducer is asked to settle ONE specific claim, so it has to be told what the claim is. A bare
 * checker name ("FB.EI_EXPOSE_REP2") is not a claim; the one-line meaning is.
 *
 * The third field marks how the finding can be settled:
 *   "test"  — a JUnit test can exhibit the defect directly (write it red, fix it green)
 *   "argue" — no runtime assertion can demonstrate a DEFECT (style, dead code, a hard-coded secret),
 *             so these are expected to end in a written verdict rather than a PR. It is a hint to the
 *             reproducer, not a gate: it may still return can_prove=true and be taken at its word.
 *
 * Covers every checker present in the 356-marker WebGoat report.
 */
const CHECKER_MAP = {
  'PROC_USE.VULNERABLE':                         ["command-injection", "a process is launched from a command string built with externally-controlled data", "test"],
  'FB.COMMAND_INJECTION':                        ["command-injection", "a shell/process command is assembled from unvalidated input", "test"],
  'HANDLE_LEAK':                                 ["resource-leak", "a resource handle is not closed on every path out of the method", "test"],
  'HANDLE_LEAK.EXCEPTION':                       ["resource-leak", "a resource handle is left open when an exception unwinds the method", "test"],
  'FB.OBL_UNSATISFIED_OBLIGATION':               ["resource-leak", "the obligation to close a stream is not discharged on some path", "test"],
  'FB.OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE': ["resource-leak", "the obligation to close a stream is not discharged along an exception edge", "test"],
  'FB.ODR_OPEN_DATABASE_RESOURCE':               ["resource-leak", "a JDBC resource is opened and not closed on all paths", "test"],
  'FB.OS_OPEN_STREAM':                           ["resource-leak", "a stream is opened and never closed", "test"],
  'DEREF_OF_NULL.RET':                           ["npe", "the return value of a call that can be null is dereferenced without a null check", "test"],
  'DEREF_OF_NULL.RET.LIB':                       ["npe", "the return value of a LIBRARY call that is documented to return null is dereferenced unchecked", "test"],
  'DEREF_OF_NULL.RET.STAT':                      ["npe", "the return value of a static call that can be null is dereferenced unchecked", "test"],
  'DEREF_AFTER_NULL':                            ["npe", "a value that is compared against null on one path is dereferenced on another", "test"],
  'FB.NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE':   ["npe", "a possibly-null return value is dereferenced on at least one path", "test"],
  'FB.NP_NULL_PARAM_DEREF':                      ["npe", "a possibly-null value is passed where a non-null argument is required", "test"],
  'FB.PATH_TRAVERSAL_IN':                        ["path-traversal", "a filesystem path is built from externally-controlled data without normalization, so '../' escapes the intended directory", "test"],
  'TAINTED_PTR':                                 ["taint", "externally-controlled (tainted) data reaches a sensitive sink without validation", "test"],
  'TAINTED_PTR.MINOR':                           ["taint", "externally-controlled data reaches a sink with only weak validation", "test"],
  'TAINTED_PTR.COOKIE':                          ["taint", "data taken from a cookie is used without validation", "test"],
  'FB.HARD_CODE_PASSWORD':                       ["hardcoded-secret", "a password or credential is hard-coded in the source", "argue"],
  'FB.PREDICTABLE_RANDOM':                       ["weak-randomness", "java.util.Random is used where a cryptographically secure RNG is required", "test"],
  'FB.DMI_RANDOM_USED_ONLY_ONCE':                ["weak-randomness", "a new Random is constructed and used once, so its output is determined by the seed alone", "test"],
  'FB.EI_EXPOSE_REP':                            ["mutable-exposure", "a getter returns a reference to internal mutable state, so a caller can modify the object's internals", "test"],
  'FB.EI_EXPOSE_REP2':                           ["mutable-exposure", "a constructor/setter stores an externally supplied mutable object directly, so the caller retains a handle on the object's internals", "test"],
  'FB.MS_PKGPROTECT':                            ["mutable-exposure", "a mutable static field is more visible than it needs to be", "argue"],
  'FB.ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD':  ["mutable-exposure", "an instance method writes to a static field, which races across instances", "test"],
  'FB.DM_DEFAULT_ENCODING':                      ["default-encoding", "a String/byte conversion relies on the platform default charset, so behaviour changes with the environment", "test"],
  'FB.VA_FORMAT_STRING_USES_NEWLINE':            ["default-encoding", "a format string uses \\n where %n is required for platform-correct line endings", "argue"],
  'FB.RV_RETURN_VALUE_IGNORED':                  ["ignored-result", "the return value of a method is discarded although it carries the result of the call", "test"],
  'FB.RV_RETURN_VALUE_IGNORED_BAD_PRACTICE':     ["ignored-result", "the return value of a method that reports failure via its result is discarded", "test"],
  'FB.RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT':   ["ignored-result", "the return value of a side-effect-free method is discarded, so the call does nothing", "test"],
  'UNREACHABLE_CODE':                            ["dead-code", "this code cannot be reached on any execution path", "argue"],
  'UNREACHABLE_CODE.EXCEPTION':                  ["dead-code", "this code is unreachable because an exception always unwinds before it", "argue"],
  'FB.DLS_DEAD_LOCAL_STORE':                     ["dead-code", "a value assigned to a local variable is never read", "argue"],
  'FB.URF_UNREAD_FIELD':                         ["dead-code", "a field is written but never read", "argue"],
  'UNUSED_VALUE':                                ["dead-code", "a computed value is never used", "argue"],
  'FB.UC_USELESS_OBJECT':                        ["dead-code", "an object is created and populated but never used", "argue"],
  'COLLECTION.WRONG_ARG_TYPE':                   ["type-confusion", "a collection method is called with an argument whose type can never match the element type, so the call silently does nothing", "test"],
  'FB.GC_UNRELATED_TYPES':                       ["type-confusion", "a generic call is made with unrelated types, so it cannot match at runtime", "test"],
  'NO_CATCH':                                    ["exception-handling", "an exception that can be thrown here is not handled", "test"],
  'NO_CATCH.LIBRARY':                            ["exception-handling", "an exception documented by a library call is not handled", "test"],
  'FB.REC_CATCH_EXCEPTION':                      ["exception-handling", "catch(Exception) also swallows RuntimeExceptions that were not meant to be caught", "test"],
  'SIMILAR_BRANCHES':                            ["similar-branches", "two branches have identical bodies, which usually means a copy-paste error left one branch wrong", "test"],
  'SIMILAR_BRANCHES.CATCH':                      ["similar-branches", "two catch blocks have identical bodies, which usually means one was meant to differ", "test"],
  'FB.ICAST_INTEGER_MULTIPLY_CAST_TO_LONG':      ["integer-overflow", "an int multiplication is cast to long only AFTER the multiplication, so it can already have overflowed", "test"],
  'FB.UI_INHERITANCE_UNSAFE_GETRESOURCE':        ["resource-lookup", "getClass().getResource() in a subclassable class resolves relative to the SUBCLASS, not this class", "test"],
  'TEST.INCORRECT_MODIFIERS':                    ["test-quality", "a test method has modifiers that stop the framework from running it", "test"],
  'TEST.FAIL_IN_CATCH':                          ["test-quality", "a test calls fail() inside a catch block, which hides the real assertion error", "argue"],
  'TEST.MULTIPLE_EXCEPTIONAL_CALLS':             ["test-quality", "a test asserts on several calls that can each throw, so a failure does not identify which", "argue"],
};

const SEVERITY_MAP = {"Critical": "high", "Major": "high", "Normal": "medium", "Minor": "low"};
const SEVERITY_RANK = {"Critical": 3, "Major": 2, "Normal": 1, "Minor": 0};

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { CHECKER_MAP, SEVERITY_MAP, SEVERITY_RANK };
