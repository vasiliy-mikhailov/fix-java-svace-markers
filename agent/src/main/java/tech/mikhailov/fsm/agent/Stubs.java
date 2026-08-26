package tech.mikhailov.fsm.agent;

import java.util.Map;

import com.deepagents.langchain4j.subagents.SubAgentRuntime;
import com.deepagents.langchain4j.logging.ToolInvocationLogMode;

/**
 * THE TWO AGENTS SHAPE 1 HAS, AND WHY THERE ARE ONLY TWO.
 *
 * <p>A prove has fifteen, in five planner/doer/verifier triples, because its verifiers have to be
 * models: nothing but a reader can say whether an argument is sound. Here the verifier is
 * {@code mvn} or {@code gradle} behind a fence it cannot switch off, and the doer is
 * {@link Fabricate}, which is code. So the model appears only where a judgement genuinely needs one
 * — deciding WHAT is missing — and it proposes into a grammar with no room for a body.
 *
 * <p>NO TOOLS, DELIBERATELY. The planners are handed the compiler's own words and the build files,
 * fenced. Giving them a file reader would let them wander a tree of eight hundred classes looking
 * for context that javac has already summarised exactly, and every one of those calls is a chance to
 * read something that is not evidence. The one input that matters is the error list.
 */
final class Stubs {

    private Stubs() {
    }

    /**
     * The planner that decides which stand-ins the compiler is asking for.
     *
     * <p>THE PROMPT'S WHOLE JOB IS TO STOP IT BEING HELPFUL. A model asked to make a build pass will
     * offer to implement the method, guess the constant, or extend the base class — every one of
     * which is refused by the generator, wasting a turn. Saying so up front turns three wasted
     * rounds into none.
     */
    static Agents.Agent planner(JsonlTrace trace, String repo) {
        return runtime("stub-planner", trace, repo, """
                You decide WHAT IS MISSING from a Java module, so that a generator can write empty
                stand-ins for it. You never write Java. You cannot: your answer is a list of
                declarations in the grammar below, and it has no syntax for a method body.

                You are given the compiler's own list of unresolved symbols. That list is the whole
                job. Do not infer types it did not name, and do not skip ones it did.

                ANSWER IN THIS GRAMMAR, one declaration per block:

                    interface  ru.nsd.core.wrauthclient.service.WRAuthService
                    class      ru.nsd.a.Boom extends java.lang.RuntimeException
                      method   go boolean java.lang.String,int
                      field    CA_FORM java.lang.String
                    enum       ru.nsd.a.State
                      constant CREATED
                    annotation ru.nsd.a.HasPermission
                    why        javac named it at SampleController:29

                `field` is a static constant. `method` takes a name, a return type, and a
                comma-separated parameter list with no spaces (omit it for no arguments). Use
                `interface` wherever you can: an interface has no bodies at all and is the only
                stand-in that cannot mislead anybody.

                WHAT WILL BE REFUSED, so do not spend a turn on it:

                  - extending anything except java.lang.Object, RuntimeException, Exception or
                    Throwable. Inheritance is a body somebody else wrote: `extends
                    MessageRepositoryBase` hands every call site a real implementation.
                  - a type this tree already defines. It is not missing.
                  - a value for a constant. Constants are emitted as their type's default and
                    nothing else; there is nowhere to put a chosen one.

                WHAT YOU ARE ACTUALLY DECIDING. `symbol: class X` means a TYPE is absent — one
                declaration. `symbol: method m()` or `symbol: variable V` with a `location:` means
                the type EXISTS and is missing a member: add that member to the declaration you
                already made for it, keeping everything you declared before. A rewrite may only ADD.

                IF THE COMPILER'S LIST IS EMPTY, or every symbol on it is one you have already
                declared, say exactly:

                    nothing further can be honestly stubbed

                and one line saying why. That is a real answer and a good one — a module whose tests
                need a running permission aspect, a Kafka router or a real database cannot be made
                honest by writing types, and saying so is worth more than twenty turns of trying.
                """);
    }

    /**
     * The planner that reads a build tool refusing to read a project at all.
     *
     * <p>SEPARATE FROM THE STUB PLANNER BECAUSE THE FAILURES ARE NOT ALIKE. "Maven cannot parse this
     * pom" and "javac cannot find this type" have different evidence, different fixes and different
     * files, and one agent given both will offer a stand-in for a pom.
     */
    static Agents.Agent amender(JsonlTrace trace, String repo) {
        return runtime("amend-planner", trace, repo, """
                You read a build tool refusing to read a project, and say what would make it
                readable. You do not fabricate Java: nothing is missing yet, because nothing has
                compiled yet.

                The usual causes, in the order they are worth checking:

                  - a PARENT POM on a repository this machine cannot reach. The fix is a public
                    stand-in that pins the same versions — spring-boot-starter-parent at the version
                    this project's own dependencies imply.
                  - a `${property}` the missing parent defined, left unresolved in a plugin or a
                    dependency version.
                  - a dependency with no version, which the missing parent used to pin.
                  - a Gradle wrapper whose distributionUrl points at an unreachable host. Check
                    whether the file already carries a public URL, commented out.

                Answer as a short list of edits: the file, the element, and what it should say. Say
                which versions you are choosing and what evidence in the project implies them —
                a version nobody can justify is a guess that will fail later and take a day to find.

                Do NOT propose turning a check off. `maven.test.failure.ignore`,
                `maven.compiler.failOnError`, `skipTests`, narrowing an include, or disabling a test
                task are all refused by the harness and none of them makes a project readable.
                """);
    }

    /**
     * The planner that says which Java this subject is.
     *
     * <p>A MODEL BECAUSE THE EVIDENCE DISAGREES WITH ITSELF, not because the lookup is hard. CA2's
     * poms name no Java version at all; the answer is in a parent pom's version STRING, in CI
     * images and in Dockerfile bases, and those three can differ. edo-biz-mon declares
     * {@code sourceCompatibility = 11} and pins a Gradle that cannot run on anything newer than 13,
     * which is a second constraint pointing the same way. Weighing that is a judgement; running the
     * build afterwards is not, and the build is what decides.
     */
    static Agents.Agent detector(JsonlTrace trace, String repo) {
        return runtime("detect-planner", trace, repo, """
                You say which Java version a project is built with, from evidence in the project.

                Answer with exactly one line:

                    jdk <number>

                and then, on following lines, the evidence you used and anything that disagreed. The
                number must be one of the versions this image has. Nothing else on the first line.

                WHERE THE ANSWER USUALLY IS, in the order worth trusting:

                  - an explicit `<java.version>`, `maven.compiler.release`, `sourceCompatibility` or
                    toolchain declaration. If one exists, it is almost always the answer.
                  - a PARENT POM's version string. A parent that cannot be downloaded is still
                    evidence: `107.0-jdk21-SNAPSHOT` says 21 in plain text.
                  - the CI image and the Dockerfile base. Sometimes the only place it is written
                    down: `eclipse-temurin:java21_certs`, `image: jdk-21`.

                AND ONE CONSTRAINT THAT OVERRIDES ALL OF THEM. For a Gradle project, Gradle's own
                version caps the JDK it can run on, and exceeding it fails while parsing the build
                script — before any of the project is read, with an error about class file versions
                that looks nothing like a JDK problem. If the wrapper pins an old Gradle, the answer
                is capped by it whatever the source compatibility says.

                If the evidence conflicts, say so and pick the one a build would actually use. If
                there is no evidence at all, say `jdk` and the newest version in the image, and say
                that you are guessing.
                """);
    }

    /**
     * The same construction {@code Agents.runtime} uses, minus the tools.
     *
     * <p>THE PROMPT IS RECORDED BEFORE ANYTHING CAN THROW, and it is editable through the same
     * mechanism as every other prompt in this program: the settings page writes an override and
     * {@code Prompts.effective} prefers it. A shape whose prompts could not be read or edited would
     * be the one part of the pipeline nobody could see into.
     */
    private static Agents.Agent runtime(String name, JsonlTrace trace, String repo, String builtIn) {
        Agents.declare(name, builtIn);
        String prompt = Prompts.effective(name, builtIn);
        SubAgentRuntime runtime = new SubAgentRuntime(Prove.model(name, trace), prompt, Map.of(),
                "agent:" + name, ToolInvocationLogMode.NONE, trace);
        return task -> {
            String reply = runtime.run(task);
            reply = reply == null ? "" : reply;
            trace.asked(name, prompt + "\n\n---\n\n" + task, reply);
            return reply;
        };
    }
}
