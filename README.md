# Protegey — Spring Boot example

Minimal Spring Boot app demonstrating `protegey-sdk` (Java) from a real backend: transaction
reporting, starting a KYC session, and verifying an incoming webhook's HMAC signature.

## Run it

```bash
# Install the SDK into your local ~/.m2 first (not yet on Maven Central):
git clone https://github.com/protegey/protegey_java_sdk.git && (cd protegey_java_sdk && mvn install)

PROTEGEY_API_KEY=your-api-key \
PROTEGEY_BASE_URL=https://api.protegey.com \
PROTEGEY_WEBHOOK_SECRET=your-webhook-secret \
mvn spring-boot:run
```

Open `http://localhost:8080` — two buttons call the backend directly.

## What it does

- `ProtegeyConfig.java` — wires a `Protegey` bean from `protegey.*` properties (`application.properties`).
- `ProtegeyDemoController.java`:
  - `POST /protegey/report-transaction` — `protegey.transactions.report()`.
  - `POST /protegey/start-kyc` — `protegey.kyc.startSession()`, returns the hosted URL, which the
    static page opens in a new tab.
  - `POST /protegey/webhook` — verifies `X-Signature`/`X-Timestamp` via `WebhookVerifier.verify()`
    before trusting the payload. Point your Protegey webhook URL at this route to see it in action.
