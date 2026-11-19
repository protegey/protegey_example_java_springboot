package com.protegey.example;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the cash-deposit and KYC lookup routes against a local fake of the Protegey API. */
@SpringBootTest
@AutoConfigureMockMvc
class DemoEndpointsTests {

	private static final HttpServer FAKE_API = startFakeApi();
	private static volatile String lastMethod;
	private static volatile String lastPathAndQuery;
	private static volatile String lastBody;

	private static HttpServer startFakeApi() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", exchange -> {
				lastMethod = exchange.getRequestMethod();
				String query = exchange.getRequestURI().getRawQuery();
				lastPathAndQuery = exchange.getRequestURI().getRawPath() + (query != null ? "?" + query : "");
				lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				String response;
				int status = 200;
				if (lastPathAndQuery.startsWith("/partner-api/cash-deposits") && "POST".equals(lastMethod)) {
					status = 201;
					response = "{\"cashDepositId\":\"cd-1\",\"externalTransactionId\":\"dep-1\",\"decision\":\"REVIEW\",\"riskScore\":20,\"alerts\":[]}";
				} else if (lastPathAndQuery.startsWith("/partner-api/cash-deposits")) {
					response = "{\"data\":[{\"cashDepositId\":\"cd-1\",\"amount\":\"2500000.00\"}],\"total\":1,\"page\":1,\"limit\":10,\"totalPages\":1}";
				} else {
					response = "{\"data\":[{\"sessionId\":\"s1\",\"externalUserId\":\"cust-9981\",\"status\":\"APPROVED\",\"recommendation\":\"APPROVE\","
						+ "\"documentPhoto\":\"base64...\",\"images\":{\"documentFront\":null,\"documentBack\":null,"
						+ "\"selfie\":{\"url\":\"https://s3.test/selfie.jpg\",\"expiresAt\":\"2026-11-19T13:00:00.000Z\"},\"documentFaceCrop\":null}}],"
						+ "\"total\":1,\"page\":1,\"limit\":20,\"totalPages\":1}";
				}
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
			server.start();
			return server;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void protegeyProperties(DynamicPropertyRegistry registry) {
		registry.add("protegey.api-key", () -> "test-api-key");
		registry.add("protegey.webhook-secret", () -> "test-webhook-secret");
		registry.add("protegey.base-url", () -> "http://localhost:" + FAKE_API.getAddress().getPort());
	}

	@AfterAll
	static void stopFakeApi() {
		FAKE_API.stop(0);
	}

	@Autowired
	private MockMvc mvc;

	@Test
	void recordsACashDeposit() throws Exception {
		mvc.perform(post("/protegey/report-cash-deposit"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cashDepositId").value("cd-1"))
			.andExpect(jsonPath("$.decision").value("REVIEW"));

		assertEquals("POST", lastMethod);
		assertEquals("/partner-api/cash-deposits", lastPathAndQuery);
		assertTrue(lastBody.contains("\"currency\":\"XOF\""));
		assertTrue(lastBody.contains("\"externalTransactionId\":\"springboot-deposit-"));
	}

	@Test
	void listsCashDeposits() throws Exception {
		mvc.perform(get("/protegey/cash-deposits"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].amount").value("2500000.00"));

		assertTrue(lastPathAndQuery.startsWith("/partner-api/cash-deposits?"));
	}

	@Test
	void looksUpKycSessionsByExternalUserId() throws Exception {
		mvc.perform(get("/protegey/kyc-lookup").param("externalUserId", "cust-9981"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessions[0].recommendation").value("APPROVE"))
			.andExpect(jsonPath("$.sessions[0].images.selfie.url").value("https://s3.test/selfie.jpg"))
			.andExpect(jsonPath("$.sessions[0].documentPhoto").doesNotExist());

		assertEquals("/partner-api/kyc/sessions?externalUserId=cust-9981", lastPathAndQuery);
	}
}
