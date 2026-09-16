# Session Log — 2026-09-14/16

## Status: Foundation done · vendor-adapters plan cancelled · Provisioning J1 done, on `feat/provisioning`

Repo: `git@github.com:gandharyanto/mobile-cashup-payment.git`
Foundation merged to `main` at `bc48699`. Provisioning (this session) lives on branch
`feat/provisioning`, not yet merged.

---

## How this project started

Session began analyzing the **old** app repo (`cash-pay-tech-mobile-function` — Cashlez POS, multi-module Android) to catalog every endpoint across all modules ahead of a full rework. That analysis is saved there at `docs/ENDPOINT_INVENTORY_FOR_REWORK.md` — ~130 HTTP endpoints across 16 service interfaces, MQTT topics, deeplink schemes, dual-auth architecture, and flagged security issues (hardcoded AES key/IV + trust-all TLS in `bridge-api`).

From a shared architecture whiteboard photo, the actual ask turned out to be narrower: build a **new**, separate project — `mobile-cashup-payment` — for just the **EDC channel** of a multi-channel backend (EDC / Softpos / CashlezLink, each with its own Front-facing API, all behind a shared **Avatar Core**). Softpos and CashlezLink are other teams' apps; out of scope here.

## What `mobile-cashup-payment` is

A **lean payment-execution engine** for physical EDC terminals (PAX/Sunmi/Feitian/Urovo/Centerm/Newland/Nexgo/Topwise/Tianyu) — **not** a POS app with a product catalog/cart. It handles CDCP (card) + QRIS payments, triggered three ways: standalone (cashier menu), app-to-app/deeplink (external caller), and ECR/POSH bridge (external controller device).

Full design spec: **`docs/EDC_PAYMENT_APP_DESIGN.md`** — read this first when resuming. Key decisions baked in there:
- **No login at all, ever.** Auth is digital-signature request-signing (Ed25519, locked by the team's provisioning diagram — not ECDSA as first guessed here). Provisioning bootstrap has no login step either: authorization comes entirely from the `challengeCode` a QR scan yields. See `docs/superpowers/specs/2026-09-16-provisioning-design.md` for the real 10-step flow.
- **DUKPT handling**: vendor SDK secure-module injection first, TEE vault (`DuktpVaultCompat`) as a mirror — implemented via `device-sdk-edcsdk`, one adapter over the `edc-sdk` AAR (see below). The Ed25519 request-signing key is **not** hardware-backed (`KEY_ALGORITHM_ED25519` doesn't exist on Android before API 37); only the RSA unwrap key and the DUKPT vault get TEE, and even the RSA path degrades to software depending on backend MGF1 confirmation (provisioning spec §4.5, §8 item 1).
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
| `device-sdk-api` | `CardReader`/`Printer`/`Scanner`/`DeviceSdk` interfaces, shared `testFixtures` fakes (`FakeCardReader`/`FakePrinter`/`FakeScanner`/`FakeDeviceSdk`) for downstream modules to use. `DeviceSdkRegistry` (originally here, longest-prefix `Build.MODEL`→vendor resolution) was later removed — see "Device SDK vendor adapters plan — cancelled" below. |
| `signing-core` | ECDSA (`SHA256withECDSA`, secp256r1) `RequestSigner` + `SigningInterceptor` — signs method+full-request-target(path+query)+timestamp+nonce+body-hash; throws `DeviceNotProvisionedException` (an `IOException`, not `IllegalStateException`) when unprovisioned so it fails safely on OkHttp's async path |

**28/28 tests passing.** The final whole-branch review (dispatched on Opus) caught real issues before they could propagate — most notably that the signature didn't originally cover the query string (fixed), and that `common-core`/`signing-core` used Gradle `implementation` instead of `api` for types that leak into their public surface (would have broken every consumer module — fixed).

**Parked, not fixed (low stakes, noted for later):**
- `safeApiCall`'s generic-exception branch has no dedicated test (function is correct, just under-tested for that one case).
- Several Minor findings and process recommendations from the final review (version catalog / convention plugin before module #4+, CI setup, `explicitApi()` mode, package-naming consistency across the 3 modules, DER-vs-P1363 ECDSA encoding note for the backend conversation) — read the final review section if resuming a "review the state of things" thread; nothing here blocks progress but they compound the more modules get added.

## Device SDK vendor adapters plan — cancelled

`docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md` (9 tasks, 67 steps, never executed)
was cancelled outright. It set out to rebuild six vendor adapters from raw AARs, stopping at
`connect()` + `serialNumber()`. `edc-sdk` (the internal vendor SDK, consumed via a single AAR
dependency, not source) already provides that plus DUKPT key injection, printer, card reader, and
EMV for seven vendors. `DeviceSdkRegistry` in `device-sdk-api` (the `Build.MODEL`-prefix matcher
from Foundation) was removed as redundant — `SDKManager.autoDetectDevice()` already does device
detection via `Build.BRAND`. Its tests were removed with it.

## Provisioning (J1) — done

Spec: `docs/superpowers/specs/2026-09-16-provisioning-design.md`. Plan executed as 15 tasks on
branch `feat/provisioning`, task-by-task with review. J1 (initial provisioning) is fully
implemented and runnable on a terminal:

| Module | What it has |
|---|---|
| `device-sdk-edcsdk` | New Android library module, the *only* one importing `com.lib.core.*` — adapter over the `edc-sdk` AAR, implementing `SerialNumberProvider` and `TerminalKeyInstaller` from `device-sdk-api` |
| `signing-core` | Rewritten: `Ed25519RequestSigner`, `SigningInterceptor`, `SigningKeyProvider` |
| `provisioning-core` | New Android library, no UI — `data/remote` (`ProvisioningApi`, DTOs), `data/local` (`ProvisioningStateStore`), `data/domain` (`ProvisionDeviceUseCase`, the 10-step atomic flow), `crypto` (`RsaKeyStore`, `Ed25519KeyStore`, `PackageUnwrapper`, `Kcv`), `audit` (`Evidence`/`ProvisioningJournal` — debug-only, has a `revealSecrets` kill switch gated on `BuildConfig.DEBUG`, slated for removal before production release) |
| `app` | New Android application shell — manual DI (`AppContainer`), Navigation Component + ViewBinding, the four provisioning screens (Gate/ScanQr/Processing/Result) and `ProvisioningViewModel` |

No login anywhere in this flow — authorization is the `challengeCode` from a QR scan. Full 10-step
sequence and HTTP contract are in the provisioning spec §2–§3.

## What's next (in build order)

1. **Notification** — `notification-core` (MQTT client, unified topic, 5s push→poll fallback)
2. **CDCP (card) domain** — `cdcp-core` + Card Payment screens
3. **QRIS domain** — `qris-core` + QRIS screens
4. **ECR/POSH bridge** — rebuilt `bridge-api` (without the hardcoded-key/trust-all-TLS bugs found in the old app's audit)
5. **App shell** — remaining screens: Home, Sale Entry, Transaction History, Settlement, Device Settings, App-to-app/deeplink router
6. **J2 Re-Provisioning and J3 Deactivation** — only once the backend provides endpoints for them (see blocking items below; the team's provisioning diagram has no sequence for either, so guessing the contract now would be thrown-away work — provisioning spec §10)

Each gets its own brainstorm-confirm → spec-update-if-needed → plan → subagent-driven-development cycle, same as Foundation and Provisioning.

## Blocking: eight backend confirmations needed (provisioning spec §8)

Item 1 is the most urgent — it decides whether the RSA unwrap key can live in the TEE at all, and
the answer can change `KeyPackageResponse`, `PackageUnwrapper`, and `AppContainer`. Fastest way to
prove it: ask backend for one sample `wrappedPackageKey` plus its plaintext, then try opening it
with both MGF1 combinations.

1. **RSA wrap shape — most decisive.** (a) Capacity: does the package fit RSA-2048/OAEP-SHA256's
   190-byte limit, or is it a hybrid (RSA-wrapped AES key) scheme like `edc-mobile` uses? (c) One
   IPEK with four derived purposes, or four genuinely separate IPEKs (only one of which can get
   vendor-module hardware protection — see spec §4.3)? (b) MGF1 digest — SHA-1 or SHA-256? This
   alone decides whether the TEE path is usable at all on the Android 7–11 terminals we target
   (spec §4.5): AndroidKeyStore's `setMgf1Digests` doesn't exist before API 35, so the key is
   locked to whichever digest the backend actually wraps with.
2. DUKPT purpose names and count — diagram just says "#4 DUKPT"; implementation is data-driven so
   this doesn't block code, but KCVs must be reported under the names backend expects.
3. Exact shape of the `activate` request body — diagram only says "Send KCV".
4. Whether backend really identifies the device by `serialNumber` (what we send) rather than a
   backend-issued UUID `deviceId` (what `edc-mobile` uses).
5. Whether the Front-facing API path really mirrors `corepayment` 1:1, including `orderId`
   appearing in both path and body on `/package`.
6. `GET` vs `POST` for `/package` — `corepayment` uses `GET` + `X-Activation-Token` header; the
   team's diagram uses `POST` + body. Spec follows the diagram.
7. Canonical string and signature format/encoding — diagram is silent; spec follows `corepayment`
   (the only proven-working reference) and needs confirmation, especially the raw-64-byte
   Base64 URL-safe-no-padding Ed25519 signature encoding.
8. Definitive provisioning error code list for the Front-facing API.

## Older open items — payment domain (spec §9, not yet superseded)

- MQTT topic/payload design (new, not reusing the old app's scheme).
- Definitive list of Front-facing API (CDCP+QRIS) endpoints.
- QRIS Static status query should be per-invoice/session, not "last transaction for merchant" (current app's implementation is race-prone — flagged for the new backend).
- "Pending transaction" reconciliation endpoint contract (needed because this app is stateless).
- TMS Agent integration trust reconfiguration (deferred, not reopened — spec §9 item 7).

## Recommended first move when resuming at the office

Re-read `docs/EDC_PAYMENT_APP_DESIGN.md` (spec) and this file, then chase backend confirmation on
provisioning spec §8 item 1 (RSA wrap shape / MGF1 digest) — it is the single item most likely to
force a code change (`KeyPackageResponse`, `PackageUnwrapper`, `AppContainer`) if the guess turns
out wrong, and it blocks manual validation on a physical terminal. In parallel, start the
**Notification** brainstorm — it's next in the build order and doesn't depend on that answer.
