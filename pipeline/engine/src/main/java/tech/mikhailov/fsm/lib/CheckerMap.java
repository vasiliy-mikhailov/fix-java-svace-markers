package tech.mikhailov.fsm.lib;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each Svace checker MEANS.
 *
 * <p>The reproducer is asked to settle ONE specific claim, so it has to be told what the claim is. A
 * bare checker name ("FB.EI_EXPOSE_REP2") is not a claim; the one-line meaning is.
 *
 * <p>Covers every checker present in the 356-marker WebGoat report.
 *
 * <p>WHY IT IS A TYPE AND NOT A THREE-ELEMENT ARRAY. Read positionally — {@code m[0]},
 * {@code m[1]}, {@code m[2]} — an entry that has lost a field is still TRUTHY, so it is not counted
 * as unmapped either: it produces a row reading "Claim: undefined. Settle-by: undefined." and the
 * prover then spent minutes trying to reproduce a defect whose claim was the word "undefined".
 * Nothing threw, nothing was counted, the run was green. {@link Entry} is a record and {@link SettleBy}
 * is an enum, so both of those are compile errors rather than a silent bad row — the tests
 * keep the checks that a type cannot make for us.
 */
public final class CheckerMap {

    private CheckerMap() {
    }

    /** How a finding can be settled. */
    public enum SettleBy {
        /** A JUnit test can exhibit the defect directly: write it red, fix it green. */
        TEST("test"),
        /**
         * No runtime assertion can demonstrate a DEFECT (style, dead code, a hard-coded secret), so
         * these are expected to end in a written verdict rather than a PR. It is a hint to the
         * reproducer, not a gate: it may still return can_prove=true and be taken at its word.
         */
        ARGUE("argue");

        private final String wire;

        SettleBy(String wire) {
            this.wire = wire;
        }

        /**
         * The lower-case spelling spliced into the evidence line. {@code PrepProver} greps
         * {@code /Settle-by:\s*(\w+)/} back out of it and compares the result against 'argue', so any
         * third spelling reads as 'test' and queues an unprovable finding for a PR.
         */
        public String wire() {
            return wire;
        }
    }

    /**
     * @param category   the suspicions.category cell the backlog is grouped and filtered by
     * @param meaning    the checker's claim in one line — what it MEANS, not what it is called
     * @param settleBy   whether a test can settle this, or only an argument
     */
    public record Entry(String category, String meaning, SettleBy settleBy) {
    }

    private static final Map<String, Entry> MAP = new LinkedHashMap<>();

    private static void put(String checker, String category, String meaning, SettleBy settleBy) {
        MAP.put(checker, new Entry(category, meaning, settleBy));
    }

    static {
        put("PROC_USE.VULNERABLE", "command-injection",
                "a process is launched from a command string built with externally-controlled data",
                SettleBy.TEST);
        put("FB.COMMAND_INJECTION", "command-injection",
                "a shell/process command is assembled from unvalidated input", SettleBy.TEST);
        put("HANDLE_LEAK", "resource-leak",
                "a resource handle is not closed on every path out of the method", SettleBy.TEST);
        put("HANDLE_LEAK.EXCEPTION", "resource-leak",
                "a resource handle is left open when an exception unwinds the method", SettleBy.TEST);
        put("FB.OBL_UNSATISFIED_OBLIGATION", "resource-leak",
                "the obligation to close a stream is not discharged on some path", SettleBy.TEST);
        put("FB.OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE", "resource-leak",
                "the obligation to close a stream is not discharged along an exception edge",
                SettleBy.TEST);
        put("FB.ODR_OPEN_DATABASE_RESOURCE", "resource-leak",
                "a JDBC resource is opened and not closed on all paths", SettleBy.TEST);
        put("FB.OS_OPEN_STREAM", "resource-leak",
                "a stream is opened and never closed", SettleBy.TEST);
        put("DEREF_OF_NULL.RET", "npe",
                "the return value of a call that can be null is dereferenced without a null check",
                SettleBy.TEST);
        put("DEREF_OF_NULL.RET.LIB", "npe",
                "the return value of a LIBRARY call that is documented to return null is dereferenced "
                        + "unchecked", SettleBy.TEST);
        put("DEREF_OF_NULL.RET.STAT", "npe",
                "the return value of a static call that can be null is dereferenced unchecked",
                SettleBy.TEST);
        put("DEREF_AFTER_NULL", "npe",
                "a value that is compared against null on one path is dereferenced on another",
                SettleBy.TEST);
        put("FB.NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", "npe",
                "a possibly-null return value is dereferenced on at least one path", SettleBy.TEST);
        put("FB.NP_NULL_PARAM_DEREF", "npe",
                "a possibly-null value is passed where a non-null argument is required", SettleBy.TEST);
        put("FB.PATH_TRAVERSAL_IN", "path-traversal",
                "a filesystem path is built from externally-controlled data without normalization, so "
                        + "'../' escapes the intended directory", SettleBy.TEST);
        put("TAINTED_PTR", "taint",
                "externally-controlled (tainted) data reaches a sensitive sink without validation",
                SettleBy.TEST);
        put("TAINTED_PTR.MINOR", "taint",
                "externally-controlled data reaches a sink with only weak validation", SettleBy.TEST);
        put("TAINTED_PTR.COOKIE", "taint",
                "data taken from a cookie is used without validation", SettleBy.TEST);
        put("FB.HARD_CODE_PASSWORD", "hardcoded-secret",
                "a password or credential is hard-coded in the source", SettleBy.ARGUE);
        put("FB.PREDICTABLE_RANDOM", "weak-randomness",
                "java.util.Random is used where a cryptographically secure RNG is required",
                SettleBy.TEST);
        put("FB.DMI_RANDOM_USED_ONLY_ONCE", "weak-randomness",
                "a new Random is constructed and used once, so its output is determined by the seed "
                        + "alone", SettleBy.TEST);
        put("FB.EI_EXPOSE_REP", "mutable-exposure",
                "a getter returns a reference to internal mutable state, so a caller can modify the "
                        + "object's internals", SettleBy.TEST);
        put("FB.EI_EXPOSE_REP2", "mutable-exposure",
                "a constructor/setter stores an externally supplied mutable object directly, so the "
                        + "caller retains a handle on the object's internals", SettleBy.TEST);
        put("FB.MS_PKGPROTECT", "mutable-exposure",
                "a mutable static field is more visible than it needs to be", SettleBy.ARGUE);
        put("FB.ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD", "mutable-exposure",
                "an instance method writes to a static field, which races across instances",
                SettleBy.TEST);
        put("FB.DM_DEFAULT_ENCODING", "default-encoding",
                "a String/byte conversion relies on the platform default charset, so behaviour changes "
                        + "with the environment", SettleBy.TEST);
        put("FB.VA_FORMAT_STRING_USES_NEWLINE", "default-encoding",
                "a format string uses \\n where %n is required for platform-correct line endings",
                SettleBy.ARGUE);
        put("FB.RV_RETURN_VALUE_IGNORED", "ignored-result",
                "the return value of a method is discarded although it carries the result of the call",
                SettleBy.TEST);
        put("FB.RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", "ignored-result",
                "the return value of a method that reports failure via its result is discarded",
                SettleBy.TEST);
        put("FB.RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT", "ignored-result",
                "the return value of a side-effect-free method is discarded, so the call does nothing",
                SettleBy.TEST);
        put("UNREACHABLE_CODE", "dead-code",
                "this code cannot be reached on any execution path", SettleBy.ARGUE);
        put("UNREACHABLE_CODE.EXCEPTION", "dead-code",
                "this code is unreachable because an exception always unwinds before it", SettleBy.ARGUE);
        put("FB.DLS_DEAD_LOCAL_STORE", "dead-code",
                "a value assigned to a local variable is never read", SettleBy.ARGUE);
        put("FB.URF_UNREAD_FIELD", "dead-code",
                "a field is written but never read", SettleBy.ARGUE);
        put("UNUSED_VALUE", "dead-code",
                "a computed value is never used", SettleBy.ARGUE);
        put("FB.UC_USELESS_OBJECT", "dead-code",
                "an object is created and populated but never used", SettleBy.ARGUE);
        put("COLLECTION.WRONG_ARG_TYPE", "type-confusion",
                "a collection method is called with an argument whose type can never match the element "
                        + "type, so the call silently does nothing", SettleBy.TEST);
        put("FB.GC_UNRELATED_TYPES", "type-confusion",
                "a generic call is made with unrelated types, so it cannot match at runtime",
                SettleBy.TEST);
        put("NO_CATCH", "exception-handling",
                "an exception that can be thrown here is not handled", SettleBy.TEST);
        put("NO_CATCH.LIBRARY", "exception-handling",
                "an exception documented by a library call is not handled", SettleBy.TEST);
        put("FB.REC_CATCH_EXCEPTION", "exception-handling",
                "catch(Exception) also swallows RuntimeExceptions that were not meant to be caught",
                SettleBy.TEST);
        put("SIMILAR_BRANCHES", "similar-branches",
                "two branches have identical bodies, which usually means a copy-paste error left one "
                        + "branch wrong", SettleBy.TEST);
        put("SIMILAR_BRANCHES.CATCH", "similar-branches",
                "two catch blocks have identical bodies, which usually means one was meant to differ",
                SettleBy.TEST);
        put("FB.ICAST_INTEGER_MULTIPLY_CAST_TO_LONG", "integer-overflow",
                "an int multiplication is cast to long only AFTER the multiplication, so it can already "
                        + "have overflowed", SettleBy.TEST);
        put("FB.UI_INHERITANCE_UNSAFE_GETRESOURCE", "resource-lookup",
                "getClass().getResource() in a subclassable class resolves relative to the SUBCLASS, "
                        + "not this class", SettleBy.TEST);
        put("TEST.INCORRECT_MODIFIERS", "test-quality",
                "a test method has modifiers that stop the framework from running it", SettleBy.TEST);
        put("TEST.FAIL_IN_CATCH", "test-quality",
                "a test calls fail() inside a catch block, which hides the real assertion error",
                SettleBy.ARGUE);
        put("TEST.MULTIPLE_EXCEPTIONAL_CALLS", "test-quality",
                "a test asserts on several calls that can each throw, so a failure does not identify "
                        + "which", SettleBy.ARGUE);
    }

    /**
     * The table, in declaration order and unmodifiable.
     *
     * <p>NOT {@code Map.copyOf}, which was what this returned until the parse-markers port compared
     * this table against the frozen corpus entry by entry and found the two in different orders —
     * a DIFFERENT order on every run, because the JDK's immutable maps salt their iteration with a
     * per-JVM value. Nothing reads it in order today, and the 48 entries themselves were correct, so
     * the only casualty was this sentence being false. It stops being false here rather than in the
     * javadoc, because the next caller to want a stable table dump would otherwise get a fresh
     * shuffle from every container restart and no reason to suspect this method.
     */
    public static Map<String, Entry> entries() {
        return Collections.unmodifiableMap(MAP);
    }

    /**
     * The entry for a checker, or null when the report used one this table does not cover.
     *
     * <p>An unmapped checker is not dropped — that is the right call — but it is ingested under
     * category 'unmapped' with its own NAME as its description, and the reproducer is then asked to
     * settle a claim nobody ever wrote.
     */
    public static Entry get(String checker) {
        return MAP.get(checker);
    }
}
