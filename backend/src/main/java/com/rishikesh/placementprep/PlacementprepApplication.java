package com.rishikesh.placementprep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// @EnableScheduling switches on @Scheduled methods - the resume review, the deadline
// reminders and the orphaned-file sweep. Without it the annotation is silently ignored
// and nothing runs.
@SpringBootApplication
@EnableScheduling
public class PlacementprepApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlacementprepApplication.class, args);
	}

	/**
	 * The threads the @Scheduled jobs run on: a pool of spring.task.scheduling.pool.size.
	 *
	 * <p>Declared by hand because virtual threads are switched on. With them, Spring Boot's
	 * own scheduler runs every fixed-delay job on one shared thread (to keep each job's
	 * "wait this long after the last run ended" timing exact), so the resume review - which
	 * can spend minutes draining its queue - would hold up the deadline reminders and the
	 * file sweep until it finished. A pool gives each job a thread of its own.
	 *
	 * <p>Ordinary platform threads are the right choice here. Virtual threads pay off when
	 * thousands of tasks are waiting at once, as requests can; there are three jobs.
	 */
	@Bean
	ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
		return builder.build();
	}

}
