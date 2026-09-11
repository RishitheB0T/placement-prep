package com.rishikesh.placementprep;

import org.springframework.boot.SpringApplication;

public class TestPlacementprepApplication {

	public static void main(String[] args) {
		SpringApplication.from(PlacementprepApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
