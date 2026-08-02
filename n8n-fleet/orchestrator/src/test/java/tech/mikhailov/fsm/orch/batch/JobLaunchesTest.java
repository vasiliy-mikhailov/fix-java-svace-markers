package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;

/**
 * Single flight — the guarantee that replaces the java-runner's named lease.
 *
 * <p>Two of these are the lease's own job. The third is the one the lease never did: an ingest CLEARS
 * both tables, so starting one while a prove is running deletes the marker the prove is working on,
 * and the prove then settles a row that no longer exists — 45 minutes of runner time spent on an
 * artifact nothing explains.
 *
 * <p>The launcher and the explorer are hand-written rather than mocked, because what is asserted is
 * WHICH job was launched and with what, and a stub that records that is clearer than three verify
 * calls.
 */
class JobLaunchesTest {

    /** Records what it was asked to run and hands back an execution that never actually runs. */
    private static final class RecordingLauncher implements JobLauncher {

        private final List<String> started = new ArrayList<>();
        private final List<JobParameters> parameters = new ArrayList<>();
        private long nextId = 1;

        @Override
        public JobExecution run(Job job, JobParameters jobParameters) {
            started.add(job.getName());
            parameters.add(jobParameters);
            JobExecution execution =
                    new JobExecution(new JobInstance(nextId, job.getName()), jobParameters);
            execution.setId(nextId++);
            return execution;
        }
    }

    /** Only {@code findRunningJobExecutions} is ever called; the rest of the interface defaults. */
    private static final class RunningJobs implements JobExplorer {

        private final Set<String> running = new LinkedHashSet<>();

        @Override
        public Set<JobExecution> findRunningJobExecutions(String jobName) {
            return running.contains(jobName)
                    ? Set.of(new JobExecution(new JobInstance(1L, jobName), new JobParameters()))
                    : Set.of();
        }

        @Override
        public List<JobInstance> getJobInstances(String jobName, int start, int count) {
            return List.of();
        }

        @Override
        public JobExecution getJobExecution(Long executionId) {
            return null;
        }

        @Override
        public org.springframework.batch.core.StepExecution getStepExecution(Long jobExecutionId,
                                                                             Long stepExecutionId) {
            return null;
        }

        @Override
        public JobInstance getJobInstance(Long instanceId) {
            return null;
        }

        @Override
        public List<JobExecution> getJobExecutions(JobInstance jobInstance) {
            return List.of();
        }

        @Override
        public List<String> getJobNames() {
            return List.of();
        }

        @Override
        public List<JobInstance> findJobInstancesByJobName(String jobName, int start, int count) {
            return List.of();
        }

        @Override
        public long getJobInstanceCount(String jobName) {
            return 0;
        }
    }

    private static Job named(String name) {
        return new Job() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public void execute(JobExecution execution) {
                throw new UnsupportedOperationException("this test never runs the job");
            }
        };
    }

    private final RecordingLauncher launcher = new RecordingLauncher();
    private final RunningJobs explorer = new RunningJobs();
    private final JobLaunches launches = new JobLaunches(launcher, explorer, named("prove"),
            named("ingest"), Clock.fixed(Instant.parse("2026-07-29T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void aProveIsRefusedWhileAProveIsRunning() {
        explorer.running.add(BatchConfig.PROVE_JOB);

        JobLaunches.Launch launch = launches.prove("schedule");

        assertThat(launch.started()).isFalse();
        assertThat(launch.reason()).contains("already running");
        assertThat(launcher.started).isEmpty();
    }

    @Test
    void anIngestIsRefusedWhileAProveIsRunningBecauseItWouldDeleteTheMarkerBeingProved() {
        explorer.running.add(BatchConfig.PROVE_JOB);

        JobLaunches.Launch launch = launches.ingest(request(), "api");

        assertThat(launch.started()).isFalse();
        assertThat(launch.reason()).contains("delete the marker it is working on");
        assertThat(launcher.started).isEmpty();
    }

    @Test
    void anIdleOrchestratorStartsTheProveAndIdentifiesTheRunByItsTimestamp() {
        JobLaunches.Launch launch = launches.prove("schedule");

        assertThat(launch.started()).isTrue();
        assertThat(launch.executionId()).isEqualTo(1L);
        assertThat(launcher.started).containsExactly(BatchConfig.PROVE_JOB);

        JobParameters used = launcher.parameters.get(0);
        // Identifying, so every tick is its own JobInstance — without it the second prove of the day
        // is refused as "instance already complete".
        assertThat(used.getLong(JobLaunches.LAUNCHED_AT))
                .isEqualTo(Instant.parse("2026-07-29T10:00:00Z").toEpochMilli());
        assertThat(used.getParameters().get(JobLaunches.LAUNCHED_AT).isIdentifying()).isTrue();
        // …and who asked is recorded but NOT identifying: a tick and a POST are the same work.
        assertThat(used.getString(JobLaunches.TRIGGER)).isEqualTo("schedule");
        assertThat(used.getParameters().get(JobLaunches.TRIGGER).isIdentifying()).isFalse();
    }

    @Test
    void anIngestCarriesTheReportItWasAskedFor() {
        JobLaunches.Launch launch = launches.ingest(request(), "api");

        assertThat(launch.started()).isTrue();
        assertThat(launcher.started).containsExactly(BatchConfig.INGEST_JOB);
        JobParameters used = launcher.parameters.get(0);
        assertThat(used.getString(IngestRequest.REPO)).isEqualTo("acme/app");
        assertThat(used.getString(IngestRequest.CSV_PATH)).isEqualTo("/data/markers.csv");
    }

    @Test
    void aSingleMarkerProveCarriesTheKeyAsAnIdentifyingParameter() {
        JobLaunches.Launch launch = launches.proveMarker("acme/app|A.java|7|CHK", "api");

        assertThat(launch.started()).isTrue();
        assertThat(launcher.started).containsExactly(BatchConfig.PROVE_JOB);
        JobParameters used = launcher.parameters.get(0);
        assertThat(used.getString(SuspicionReader.DEDUP_KEY)).isEqualTo("acme/app|A.java|7|CHK");
        // Identifying: two different markers are two JobInstances, so the run history says which
        // marker a debug run was about without anyone opening the step.
        assertThat(used.getParameters().get(SuspicionReader.DEDUP_KEY).isIdentifying()).isTrue();
        assertThat(used.getString(JobLaunches.TRIGGER)).isEqualTo("api");
    }

    @Test
    void aSingleMarkerProveIsRefusedWhileADrainIsRunningBecauseTheRunnerHasOneWorkspace() {
        explorer.running.add(BatchConfig.PROVE_JOB);

        JobLaunches.Launch launch = launches.proveMarker("acme/app|A.java|7|CHK", "api");

        assertThat(launch.started()).isFalse();
        assertThat(launch.reason()).contains("already running");
        assertThat(launcher.started).isEmpty();
    }

    @Test
    void aProveWithNoKeyIsNotSilentlyTurnedIntoADrainOfTheWholeBacklog() {
        // A blank key reaching `prove` would drain 282 markers for someone who asked for one.
        JobLaunches.Launch launch = launches.proveMarker("  ", "api");

        assertThat(launch.started()).isFalse();
        assertThat(launch.reason()).contains("dedup_key");
        assertThat(launcher.started).isEmpty();
    }

    @Test
    void aProveIsStillAllowedWhileAnIngestRuns() {
        // Deliberately asymmetric: an ingest replaces the backlog and must not run under a prove, but
        // a prove starting after an ingest has finished clearing simply finds the new queue.
        explorer.running.add(BatchConfig.INGEST_JOB);

        assertThat(launches.prove("schedule").started()).isTrue();
    }

    private static IngestRequest request() {
        return new IngestRequest("/data/markers.csv", "acme/app", "main", null, null, null, null);
    }
}
