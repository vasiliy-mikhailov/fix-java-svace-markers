package tech.mikhailov.fsm.orch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The orchestrator — what n8n was doing, in one process with the judgement engine it calls.
 *
 * <p>It owns the four things n8n actually contributed: the schedule, the single-flight queue, the two
 * tables, and the run history (Spring Batch's job repository, in the same database as the markers).
 * Every decision about a marker stays in {@code tech.mikhailov.fsm.nodes} and is called in-process as
 * a pure function.
 *
 * <p>NO {@code @EnableBatchProcessing}. In Boot 3 that annotation switches OFF the auto-configuration
 * it looks like it enables, and the job repository would then never be pointed at the DataSource.
 *
 * <p>{@code @EnableScheduling} is what replaces the n8n Schedule Trigger: the prover tick is a
 * {@code @Scheduled} method rather than a cron node, so it is a thing that can be unit-tested.
 */
@SpringBootApplication
@EnableScheduling
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
