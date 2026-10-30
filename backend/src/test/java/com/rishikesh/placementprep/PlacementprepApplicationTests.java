package com.rishikesh.placementprep;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;

import jakarta.servlet.Filter;

/**
 * Starts the whole application on a real port, because the threading questions below are
 * about Tomcat and the scheduler themselves, which MockMvc goes around.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PlacementprepApplicationTests {

	/** The thread that served the most recent request, recorded by the filter below. */
	private static final AtomicReference<Thread> lastRequestThread = new AtomicReference<>();

	/** Adds one servlet filter that notes which thread each request runs on. */
	@TestConfiguration(proxyBeanMethods = false)
	static class RecordRequestThread {

		@Bean
		Filter recordRequestThread() {
			return (request, response, chain) -> {
				lastRequestThread.set(Thread.currentThread());
				chain.doFilter(request, response);
			};
		}
	}

	@LocalServerPort
	private int port;

	/** The scheduler every @Scheduled job in the application runs on. */
	@Autowired
	private TaskScheduler taskScheduler;

	@Test
	void contextLoads() {
	}

	@Test
	void requestsAreServedOnVirtualThreads() throws IOException, InterruptedException {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(lastRequestThread.get().isVirtual()).isTrue();
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
