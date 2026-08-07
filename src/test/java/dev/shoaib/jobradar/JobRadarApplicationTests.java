package dev.shoaib.jobradar;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// app.startup-run.enabled=false keeps this context-load test from firing a real
// network ingest pass (every adapter hitting live external sites) on every test run.
@SpringBootTest(properties = "app.startup-run.enabled=false")
class JobRadarApplicationTests {

	@Test
	void contextLoads() {
	}

}
