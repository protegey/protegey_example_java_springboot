package com.protegey.example;

import com.protegey.sdk.Protegey;
import com.protegey.sdk.ProtegeyApiException;
import com.protegey.sdk.WebhookVerifier;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Minimal demo of protegey-sdk from a Spring Boot backend — transactions, cash deposits, KYC, KYB, alerts,
 * sanctions screening, the shared signal network, and verifying an incoming webhook. */
@RestController
public class ProtegeyDemoController {
    private static final Logger log = LoggerFactory.getLogger(ProtegeyDemoController.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Reject webhook deliveries whose X-Timestamp is more than 5 minutes off (replay protection). */
    private static final long WEBHOOK_TOLERANCE_SECONDS = 300;

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

    // ---- Transactions ----

    @PostMapping("/protegey/report-transaction")
    public Map<String, Object> reportTransaction(HttpSession session) {
        Map<String, Object> tx = new HashMap<>();
        tx.put("externalTransactionId", "springboot-example-" + UUID.randomUUID());
        tx.put("externalCustomerId", customerId(session));
        tx.put("direction", "DEBIT");          // DEBIT | CREDIT, exact uppercase
        tx.put("amount", 5000);
        tx.put("currency", "XAF");
        tx.put("transactionType", "transfer");
        tx.put("channel", "mobile_app");
        // occurredAt defaults to now in the SDK
        return protegey.transactions.report(tx); // decision, riskScore, deviceAction, alerts[]
    }

    @GetMapping("/protegey/transactions")
    public Map<String, Object> myTransactions(HttpSession session) {
        return protegey.transactions.list(Map.of("externalCustomerId", customerId(session), "limit", 10));
    }

    // ---- Cash deposits ----

    /** A cash deposit taken at a branch — Protegey monitors it as a cash CREDIT, so it runs through
     * the same rules (structuring, large cash, ...) and raises the same alerts as a transaction. */
    @PostMapping("/protegey/report-cash-deposit")
    public Map<String, Object> reportCashDeposit(HttpSession session) {
        Map<String, Object> deposit = new HashMap<>();
        // Your own reference makes retries safe — reusing it gets a 409.
        deposit.put("externalTransactionId", "springboot-deposit-" + UUID.randomUUID());
        deposit.put("externalCustomerId", customerId(session));
        deposit.put("amount", 2500000);
        deposit.put("currency", "XOF");          // ISO 4217, required
        deposit.put("channel", "BRANCH");
        deposit.put("branchId", "DKR-PLATEAU");
        deposit.put("depositorName", "Awa Diallo");
        deposit.put("metadata", Map.of("tellerId", "T-04"));
        // depositedAt defaults to now
        return protegey.cashDeposits.record(deposit); // cashDepositId, decision, riskScore, alerts[]
    }

    @GetMapping("/protegey/cash-deposits")
    public Map<String, Object> myCashDeposits(HttpSession session) {
        // amount is a decimal string in each row, e.g. "2500000.00"
        return protegey.cashDeposits.list(Map.of("externalCustomerId", customerId(session), "limit", 10));
    }

    // ---- KYC ----

    @PostMapping("/protegey/start-kyc")
    public Map<String, Object> startKyc(HttpSession session) {
        Map<String, Object> started = protegey.kyc.startSession(customerId(session));
        session.setAttribute("protegeyDemoKycSessionId", started.get("sessionId"));
        return started; // {sessionId, url}
    }

    /** Polling fallback for kyc.status_changed (whose sessionId is this same id). */
    @GetMapping("/protegey/kyc-status")
    public Map<String, Object> kycStatus(HttpSession session) {
        Object sessionId = session.getAttribute("protegeyDemoKycSessionId");
        if (sessionId == null) {
            return Map.of("error", "start a KYC session first");
        }
        Map<String, Object> kyc = protegey.kyc.getSession((String) sessionId);
        return summarize(kyc);
    }

    /** Exact lookup on your own customer id (defaults to this HTTP session's demo customer). */
    @GetMapping("/protegey/kyc-lookup")
    public Map<String, Object> kycLookup(@RequestParam(required = false) String externalUserId, HttpSession session) {
        String id = blankToNull(externalUserId) != null ? externalUserId : customerId(session);
        Map<String, Object> page = protegey.kyc.findByExternalUserId(id);
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Object row : (List<?>) page.get("data")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) row;
            sessions.add(summarize(s));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("externalUserId", id);
        result.put("total", page.get("total"));
        result.put("sessions", sessions);
        return result;
    }

    /** status = final decision; recommendation = Protegey's own verdict (APPROVE | REVIEW | REJECT |
     * null), never changed by a manual override; images = pre-signed photo links ({url, expiresAt}
     * or null), valid 1 hour and re-issued on every call — never store them. The legacy base64
     * photo fields are left out to keep the output readable. */
    private static Map<String, Object> summarize(Map<String, Object> kyc) {
        Map<String, Object> summary = new HashMap<>();
        for (String key : new String[] {"sessionId", "externalUserId", "status", "recommendation", "outcome", "fullName", "documentType", "country", "images"}) {
            summary.put(key, kyc.get(key));
        }
        return summary;
    }

    // ---- KYB ----

    /** Invites a business by email and/or phone; hand the returned applicationUrl to the applicant. */
    @PostMapping("/protegey/invite-business")
    public Map<String, Object> inviteBusiness(
        @RequestParam String contactName,
        @RequestParam(required = false) String contactEmail,
        @RequestParam(required = false) String contactPhone
    ) {
        Map<String, Object> applicant = protegey.kyb.invite(contactName, blankToNull(contactEmail), blankToNull(contactPhone));
        return Map.of(
            "clientId", applicant.get("id"),
            "status", applicant.get("status"),
            "applicationUrl", applicant.get("applicationUrl"));
    }

    // ---- Alerts ----

    @GetMapping("/protegey/alerts")
    public Map<String, Object> openAlerts() {
        return protegey.alerts.list(Map.of("status", "OPEN", "limit", 10)); // SDK lowercases the filter
    }

    @PostMapping("/protegey/alerts/{alertId}/dismiss")
    public Map<String, Object> dismissAlert(@PathVariable String alertId) {
        return protegey.alerts.update(alertId, Map.of("status", "DISMISSED", "disposition", "FALSE_POSITIVE"));
    }

    // ---- Sanctions / shared signal ----

    @PostMapping("/protegey/screen")
    public Map<String, Object> screen(@RequestParam String name, @RequestParam(defaultValue = "person") String type) {
        return protegey.sanctions.screen(name, type); // decision BLOCKED | REVIEW | CLEAR
    }

    @PostMapping("/protegey/shared-signal-check")
    public Map<String, Object> sharedSignalCheck(@RequestParam String phoneNumber, HttpSession session) {
        return protegey.sharedSignal.check(Map.of("phoneNumber", phoneNumber, "externalCustomerId", customerId(session)));
    }

    // ---- Webhook ----

    /** Route a partner would point their Protegey webhook URL at. */
    @PostMapping("/protegey/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
        @RequestBody String rawBody, // raw body — required, a re-serialized one won't match
        @RequestHeader(WebhookVerifier.TIMESTAMP_HEADER) String timestamp,
        @RequestHeader(WebhookVerifier.SIGNATURE_HEADER) String signature
    ) {
        if (!WebhookVerifier.verify(rawBody, timestamp, signature, webhookSecret, WEBHOOK_TOLERANCE_SECONDS)) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid signature"));
        }

        Map<String, Object> event = JsonParserFactory.getJsonParser().parseMap(rawBody);
        // Deliveries are retried (up to 5 attempts over ~3 h) — deduplicate on eventId in a real integration.
        String type = String.valueOf(event.get("type"));
        switch (type) {
            // Fires on an automatic result and on a manual status change; sessionId is the id
            // startSession() returned.
            case WebhookVerifier.KYC_STATUS_CHANGED ->
                log.info("KYC session {} for user {}: {} (Protegey recommendation {})",
                    event.get("sessionId"), event.get("externalUserId"), event.get("status"), event.get("recommendation"));
            // submissionStatus is UPPER_SNAKE_CASE like status (e.g. APPROVED).
            case WebhookVerifier.KYB_STATUS_CHANGED ->
                log.info("KYB client {} is now {} (submission {})", event.get("clientId"), event.get("status"), event.get("submissionStatus"));
            case WebhookVerifier.ALERT_CREATED, WebhookVerifier.ALERT_STATUS_CHANGED ->
                log.info("Alert {} ({}) {}", event.get("alertId"), event.get("ruleCode"), event.get("status"));
            case WebhookVerifier.TRANSACTION_BLOCKED ->
                log.info("Transaction {} blocked (risk {})", event.get("externalTransactionId"), event.get("riskScore"));
            case WebhookVerifier.WEBHOOK_TEST -> log.info("Webhook test received");
            default -> log.info("Ignoring unknown webhook type {}", type);
        }
        return ResponseEntity.ok(Map.of("received", true));
    }

    /** Surface Protegey's own error (status, message, errorCode) instead of a generic 500. */
    @ExceptionHandler(ProtegeyApiException.class)
    public ResponseEntity<Map<String, Object>> onProtegeyError(ProtegeyApiException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getMessage());
        body.put("errorCode", e.getErrorCode());
        int status = e.getStatus() >= 400 ? e.getStatus() : 502;
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onBadInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
