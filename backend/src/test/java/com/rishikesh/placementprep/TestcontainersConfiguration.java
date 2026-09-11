package com.rishikesh.placementprep;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	/**
	 * Starts a throwaway PostgreSQL container for the duration of the test run.
	 *
	 * <p>We use the pgvector image rather than the plain postgres image because it ships
	 * with the {@code vector} extension preinstalled, which V1__enable_pgvector.sql needs.
	 * Testcontainers refuses image names it does not recognise, so we explicitly declare
	 * this one as a stand-in for the official postgres image.
	 *
	 * <p>{@code @ServiceConnection} hands the container's generated host, port, username,
	 * and password straight to Spring, so no test-specific datasource properties are needed.
	 */
	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg16")
						.asCompatibleSubstituteFor("postgres"));
	}

}
