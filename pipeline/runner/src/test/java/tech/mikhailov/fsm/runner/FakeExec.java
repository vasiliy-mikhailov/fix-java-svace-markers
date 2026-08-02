package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A scripted {@link Proc.Exec}: what git and Maven would have said, without git or Maven.
 *
 * <p>WHY THE SUITE NEEDS THIS AT ALL. The behaviour worth testing here is the ORCHESTRATION — clone once
 * then reset, RED before the fix and GREEN after, one retry onto the JDK a build demands, the reply's
 * shape. Every one of those is decided by what the child process printed, and a suite that really shelled
 * out could not script "this module needs JDK 25", would need a network and a Nexus, and would take longer
 * than the run it is testing. So the child process is the seam, and it is the ONLY one: the workspace, the
 * file writes, the edits and the JSON are all real.
 */
final class FakeExec implements Proc.Exec {

    /** One invocation, as the production {@link Proc#execFile} would have received it. */
    record Call(List<String> command, Path cwd, Map<String, String> env, long timeoutMillis) {

        String joined() {
            return String.join(" ", command);
        }

        boolean isGitClone() {
            return command.size() > 1 && "git".equals(command.getFirst())
                    && "clone".equals(command.get(1));
        }
    }

    @FunctionalInterface
    interface Handler {
        Proc.Result handle(Call call) throws IOException;
    }

    private final Handler handler;
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    FakeExec(Handler handler) {
        this.handler = handler;
    }

    @Override
    public Proc.Result run(List<String> command, Path cwd, Map<String, String> env,
                           long timeoutMillis) {
        Call call = new Call(List.copyOf(command), cwd, env, timeoutMillis);
        calls.add(call);
        try {
            return handler.handle(call);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Every command line as one string, for asserting on what was and was not run. */
    List<String> commands() {
        return calls().stream().map(Call::joined).toList();
    }

    static Proc.Result ok(String out) {
        return new Proc.Result(0, out);
    }

    static Proc.Result failed(String out) {
        return new Proc.Result(1, out);
    }

    /**
     * Do what a successful {@code git clone} does to the disk: leave a directory with a {@code .git} in
     * it. Both {@code prepareWs} and {@code prepareFs} branch on that directory existing, so a fake that
     * only returned 0 would test the wrong path on the second call.
     */
    static Path clonedInto(Call call) throws IOException {
        // Guarded, because the mistake it prevents already happened once: applied to a `git -C … rev-parse
        // HEAD` call, the "target" is the literal argument HEAD and this method silently creates a
        // directory called HEAD in the module root. A handler that reaches here for anything but a clone
        // is testing something other than what it says.
        if (!call.isGitClone()) {
            throw new IllegalStateException("not a git clone: " + call.joined());
        }
        Path target = Path.of(call.command().getLast());
        Files.createDirectories(target.resolve(".git"));
        return target;
    }
}
