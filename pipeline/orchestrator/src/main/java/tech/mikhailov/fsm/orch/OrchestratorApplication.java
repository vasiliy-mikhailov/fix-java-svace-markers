package tech.mikhailov.fsm.orch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The orchestrator: the schedule, the single-flight queue, the two tables, and the run history
 * (Spring Batch's job repository, in the same database as the markers).
 *
 * <p>It owns those and nothing else. Every decision about a marker stays in
 * {@code tech.mikhailov.fsm.nodes} and is called in-process as a pure function.
 *
 * <p>NO {@code @EnableBatchProcessing}. In Boot 3 that annotation switches OFF the auto-configuration
 * it looks like it enables, and the job repository would then never be pointed at the DataSource.
 *
 * <p>{@code @EnableScheduling} drives the prover tick as a {@code @Scheduled} method, which is what
 * makes the schedule a thing a unit test can call.
 */
@SpringBootApplication
@EnableScheduling
public class OrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
