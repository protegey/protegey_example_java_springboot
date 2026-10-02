package com.protegey.example;

import com.protegey.sdk.Protegey;
import com.protegey.sdk.WebhookVerifier;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.Map;

/** Minimal demo of protegey-sdk from a Spring Boot backend — transaction reporting, starting a
 * KYC session, and verifying an incoming webhook's signature. */
@RestController
public class ProtegeyDemoController {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Protegey protegey;
    private final String webhookSecret;

    public ProtegeyDemoController(Protegey protegey, @Value("${protegey.webhook-secret}") String webhookSecret) {
        this.protegey = protegey;
        this.webhookSecret = webhookSecret;
    }

    /** A fresh id per HTTP session — real integrations pass the end user's own stable id instead. */
    private String customerId(HttpSession session) {
        Object existing = session.getAttribute("protegeyDemoCustomerId");
        if (existing != null) {
            return (String) existing;
        }
        String id = "customer-" + Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
        session.setAttribute("protegeyDemoCustomerId", id);
        return id;
    }

    @PostMapping("/protegey/report-transaction")
    public Map<String, Object> reportTransaction(HttpSession session) {
        return protegey.transactions.report(Map.of(
            "externalTransactionId", "springboot-example-" + System.currentTimeMillis(),
            "externalCustomerId", customerId(session),
            "direction", "DEBIT",
            "amount", 5000,
            "currency", "XAF",
            "transactionType", "test"
        ));
    }

    @PostMapping("/protegey/start-kyc")
    public Map<String, Object> startKyc(HttpSession session) {
        return protegey.kyc.startSession(customerId(session));
    }

    /** Route a partner would point their Protegey webhook URL at. */
    @PostMapping("/protegey/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
        @RequestBody String rawBody, // raw body — required, a re-serialized one won't match
        @RequestHeader("X-Timestamp") String timestamp,
        @RequestHeader("X-Signature") String signature
    ) {
        if (!WebhookVerifier.verify(rawBody, timestamp, signature, webhookSecret)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
        }

        // Parse rawBody here and update the matching case/session in your own system.
        return ResponseEntity.ok(Map.of("received", true));
    }
}
