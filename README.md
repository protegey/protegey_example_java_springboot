# Protegey — Spring Boot example

Minimal Spring Boot app demonstrating `protegey-sdk` (Java) 0.7.0 from a real backend:
transactions, KYC, KYB, alerts, sanctions screening, the shared signal network, and verifying an
incoming webhook's HMAC signature.

## Run it

```bash
# Install the SDK into your local ~/.m2 first (not yet on Maven Central):
git clone https://github.com/protegey/protegey_java_sdk.git && (cd protegey_java_sdk && mvn install)

PROTEGEY_API_KEY=your-api-key \
PROTEGEY_BASE_URL=https://api.protegey.com \
PROTEGEY_WEBHOOK_SECRET=your-webhook-secret \
mvn spring-boot:run
```

Use your **sandbox** API key while trying it out — live vs sandbox is decided by which key you use
(sandbox quotas: 100 transactions and 10 KYC sessions per day).

Open `http://localhost:8080` — each button calls the backend directly.

## What it does

- `ProtegeyConfig.java` — wires a `Protegey` bean from `protegey.*` properties (`application.properties`).
- `ProtegeyDemoController.java`:
  - `POST /protegey/report-transaction` — `transactions.report()`; `GET /protegey/transactions` — `transactions.list()`.
  - `POST /protegey/start-kyc` — `kyc.startSession()`, returns the hosted URL (opened in a new tab);
    `GET /protegey/kyc-status` — `kyc.getSession()` polling fallback.
  - `POST /protegey/invite-business?contactName=&contactEmail=&contactPhone=` — `kyb.invite()` with
    email and/or phone; returns the `applicationUrl` to hand to the applicant.
  - `GET /protegey/alerts` — `alerts.list()` of open alerts; `POST /protegey/alerts/{id}/dismiss` — `alerts.update()`.
  - `POST /protegey/screen?name=&type=person|business` — `sanctions.screen()`.
  - `POST /protegey/shared-signal-check?phoneNumber=` — `sharedSignal.check()` (the network must be
    enabled for your account).
  - `POST /protegey/webhook` — verifies `X-Signature`/`X-Timestamp` via `WebhookVerifier.verify()`
    (with a 5-minute timestamp tolerance) before trusting the payload, then dispatches on `type`.
    Point your Protegey webhook URL at this route to see it in action.
  - `ProtegeyApiException` is mapped to its own status, message and `errorCode`.
