package com.rishikesh.placementprep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling switches on @Scheduled methods - ResumeReviewJob's background review
// of uploaded resumes. Without it the annotation is silently ignored and nothing runs.
@SpringBootApplication
@EnableScheduling
public class PlacementprepApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlacementprepApplication.class, args);
	}

}
