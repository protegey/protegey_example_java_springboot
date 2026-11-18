package com.protegey.example;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
	"protegey.api-key=test-api-key",
	"protegey.webhook-secret=test-webhook-secret"
})
class WebhookEndpointTests {

	@Autowired
	private MockMvc mvc;

	private static String sign(String timestamp, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void acceptsASignedDelivery() throws Exception {
		String body = "{\"type\":\"alert.created\",\"eventId\":\"e1\",\"alertId\":\"a1\",\"ruleCode\":\"VEL-001\",\"status\":\"OPEN\"}";
		String ts = String.valueOf(System.currentTimeMillis() / 1000);
		mvc.perform(post("/protegey/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Timestamp", ts).header("X-Signature", sign(ts, body)))
			.andExpect(status().isOk());
	}

	@Test
	void rejectsABadSignature() throws Exception {
		String body = "{\"type\":\"webhook.test\",\"eventId\":\"e1\"}";
		String ts = String.valueOf(System.currentTimeMillis() / 1000);
		mvc.perform(post("/protegey/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
				.header("X-Timestamp", ts).header("X-Signature", sign(ts, "{}")))
			.andExpect(status().isUnauthorized());
	}
}
