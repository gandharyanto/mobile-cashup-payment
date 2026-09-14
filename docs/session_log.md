# Session Log — 2026-09-14/15

## Status: Foundation plan complete, merged to `main`, pushed to GitHub

Repo: `git@github.com:gandharyanto/mobile-cashup-payment.git`
Latest commit on `main`: `bc48699`

---

## How this project started

Session began analyzing the **old** app repo (`cash-pay-tech-mobile-function` — Cashlez POS, multi-module Android) to catalog every endpoint across all modules ahead of a full rework. That analysis is saved there at `docs/ENDPOINT_INVENTORY_FOR_REWORK.md` — ~130 HTTP endpoints across 16 service interfaces, MQTT topics, deeplink schemes, dual-auth architecture, and flagged security issues (hardcoded AES key/IV + trust-all TLS in `bridge-api`).

From a shared architecture whiteboard photo, the actual ask turned out to be narrower: build a **new**, separate project — `mobile-cashup-payment` — for just the **EDC channel** of a multi-channel backend (EDC / Softpos / CashlezLink, each with its own Front-facing API, all behind a shared **Avatar Core**). Softpos and CashlezLink are other teams' apps; out of scope here.

## What `mobile-cashup-payment` is

A **lean payment-execution engine** for physical EDC terminals (PAX/Sunmi/Feitian/Urovo/Centerm/Newland/Nexgo/Topwise/Tianyu) — **not** a POS app with a product catalog/cart. It handles CDCP (card) + QRIS payments, triggered three ways: standalone (cashier menu), app-to-app/deeplink (external caller), and ECR/POSH bridge (external controller device).

Full design spec: **`docs/EDC_PAYMENT_APP_DESIGN.md`** — read this first when resuming. Key decisions baked in there:
- **No login for daily operation.** Auth is digital-signature request-signing (ECDSA), bootstrapped once via a **technician on-device login** → temp JWT → QR scan → provisioning API call → receive DUKPT + signing key material.
- **DUKPT handling unchanged** from the current app's spec (vendor SDK secure-module injection) — only the new API-signing key is new, stored in Android Keystore (TEE floor, StrongBox opportunistic).
- **Fully stateless** — no local DB. Backend (Front-facing API / Avatar Core) is the single source of truth for history/reprint/settlement.
- **Payment notif**: MQTT push (unified across CDCP+QRIS) + 5s fallback to polling.
- **Signing (new)**: same `busways.jks` keystore file as the old app, but a **new alias with a fresh key pair** — deliberately *not* cert-compatible with the `tms-agent` companion app's signature-permission channel. That integration is explicitly deferred (see spec §9 item 7) — don't build it without reopening that decision.
- Explicit **performance/no-redundancy/minimal-animation** constraints in spec §11 (this came from an explicit ask to trim vs. the old app's bloat).

## What's built so far — the Foundation plan

Plan doc: **`docs/superpowers/plans/2026-09-15-foundation.md`**

Three pure Kotlin/JVM modules (Gradle, Kotlin 2.0.21, JVM 17), fully implemented via subagent-driven-development (task-by-task implement → review → fix loop → final whole-branch review → fix wave → merge):

| Module | What it has |
|---|---|
| `common-core` | `ApiResult`/`ApiError` (typed, no `Any`), `RetrofitFactory`, `safeApiCall` (HTTP→ApiResult mapping, added in the final fix wave), `PaymentLogger` |
| `device-sdk-api` | `CardReader`/`Printer`/`Scanner`/`DeviceSdk` interfaces, `DeviceSdkRegistry` (longest-prefix `Build.MODEL`→vendor resolution, memoized), shared `testFixtures` fakes (`FakeCardReader`/`FakePrinter`/`FakeScanner`/`FakeDeviceSdk`) for downstream modules to use |
| `signing-core` | ECDSA (`SHA256withECDSA`, secp256r1) `RequestSigner` + `SigningInterceptor` — signs method+full-request-target(path+query)+timestamp+nonce+body-hash; throws `DeviceNotProvisionedException` (an `IOException`, not `IllegalStateException`) when unprovisioned so it fails safely on OkHttp's async path |

**28/28 tests passing.** The final whole-branch review (dispatched on Opus) caught real issues before they could propagate — most notably that the signature didn't originally cover the query string (fixed), and that `common-core`/`signing-core` used Gradle `implementation` instead of `api` for types that leak into their public surface (would have broken every consumer module — fixed).

**Parked, not fixed (low stakes, noted for later):**
- `safeApiCall`'s generic-exception branch has no dedicated test (function is correct, just under-tested for that one case).
- Several Minor findings and process recommendations from the final review (version catalog / convention plugin before module #4+, CI setup, `explicitApi()` mode, package-naming consistency across the 3 modules, DER-vs-P1363 ECDSA encoding note for the backend conversation) — read the final review section if resuming a "review the state of things" thread; nothing here blocks progress but they compound the more modules get added.

## What's next (in build order, per the original roadmap)

1. **Provisioning** — `provisioning-core` (technician login screen, QR scan, provisioning/re-provisioning/deactivation API calls, Keystore key storage) + the on-device screens
2. **Notification** — `notification-core` (MQTT client, unified topic, 5s push→poll fallback)
3. **Device SDK vendor adapters** — 9 tasks, one per vendor, implementing `device-sdk-api`'s `DeviceSdk` interface
4. **CDCP (card) domain** — `cdcp-core` + Card Payment screens
5. **QRIS domain** — `qris-core` + QRIS screens
6. **ECR/POSH bridge** — rebuilt `bridge-api` (without the hardcoded-key/trust-all-TLS bugs found in the old app's audit)
7. **App shell** — Home, Sale Entry, Transaction History, Settlement, Device Settings, App-to-app/deeplink router

Each gets its own brainstorm-confirm → spec-update-if-needed → plan → subagent-driven-development cycle, same as Foundation.

## Open items that need backend/team input before the next plans can fully lock down (spec §9)

- Signature scheme detail — algorithm is implemented as ECDSA/P-256 here, but §9 item 1 says this still needs backend alignment (DER vs. raw r‖s encoding matters — see final-review Minor #10 in the fix report).
- Exact provisioning API contract (QR payload shape, temp JWT format, response payload).
- MQTT topic/payload design (new, not reusing the old app's scheme).
- Definitive list of Front-facing API (CDCP+QRIS) endpoints.
- QRIS Static status query should be per-invoice/session, not "last transaction for merchant" (current app's implementation is race-prone — flagged for the new backend).
- "Pending transaction" reconciliation endpoint contract (needed because this app is stateless).

## Recommended first move when resuming at the office

Re-read `docs/EDC_PAYMENT_APP_DESIGN.md` (spec) and this file, then start the **Provisioning** brainstorm — it's the natural next plan since Foundation's `signing-core`/`device-sdk-api` contracts are what it needs to fulfill (`SigningKeyProvider` backed by real Android Keystore, DUKPT injection via a real vendor SDK).
