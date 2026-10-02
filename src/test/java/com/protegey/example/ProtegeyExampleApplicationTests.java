package com.protegey.example;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
	"protegey.api-key=test-api-key",
	"protegey.webhook-secret=test-webhook-secret"
})
class ProtegeyExampleApplicationTests {

	@Test
	void contextLoads() {
	}

}
