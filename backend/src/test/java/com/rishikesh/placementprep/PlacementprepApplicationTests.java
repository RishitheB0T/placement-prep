package com.rishikesh.placementprep;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PlacementprepApplicationTests {

	/** The scheduler every @Scheduled job in the application runs on. */
	@Autowired
	private TaskScheduler taskScheduler;

	@Test
	void contextLoads() {
	}

	/**
	 * The resume review can stay busy for minutes while it drains its queue, and the
	 * deadline reminders must still go out meanwhile. So while one fixed-delay job is
	 * running, another must still be able to start.
	 */
	@Test
	void aBusyScheduledJobDoesNotHoldUpTheOthers() throws InterruptedException {
		CountDownLatch busyJobStarted = new CountDownLatch(1);
		CountDownLatch releaseBusyJob = new CountDownLatch(1);
		CountDownLatch otherJobRan = new CountDownLatch(1);

		ScheduledFuture<?> busyJob = taskScheduler.scheduleWithFixedDelay(() -> {
			busyJobStarted.countDown();
			awaitQuietly(releaseBusyJob);
		}, Duration.ofHours(1));
		ScheduledFuture<?> otherJob = null;
		try {
			assertThat(busyJobStarted.await(5, SECONDS)).isTrue();

			otherJob = taskScheduler.scheduleWithFixedDelay(otherJobRan::countDown, Duration.ofHours(1));

			assertThat(otherJobRan.await(5, SECONDS))
					.as("a second job ran while the first was still busy")
					.isTrue();
		} finally {
			releaseBusyJob.countDown();
			busyJob.cancel(true);
			if (otherJob != null) {
				otherJob.cancel(true);
			}
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(30, SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
