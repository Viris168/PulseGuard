package com.viris.PulseGuard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
class PulseGuardApplicationTests {

	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestDatabase.IMAGE);

	static {
		POSTGRES.start();
	}

	@Test
	void contextLoads() {
	}

}
