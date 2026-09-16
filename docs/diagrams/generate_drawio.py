#!/usr/bin/env python3
"""Generate `edc-payment-flows.drawio` from the normalised API contract.

Run: python docs/diagrams/generate_drawio.py

Source of truth for the journeys is `docs/EDC_PAYMENT_APP_DESIGN.md` (J1-J12,
screen inventory in section 5). The API shapes are the normalised v1 contract
being designed to replace the legacy app's 43 CDCP/QRIS endpoints with 16.
"""

from __future__ import annotations

import os

from drawio_lib import Document

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "edc-payment-flows.drawio")

# ===========================================================================
# Shared contract fragments
# ===========================================================================

ENVELOPE = """{
  "status":     "SUCCESS",          // SUCCESS | ERROR
  "code":       "OK",               // string enum, never a number
  "message":    "...",              // log/debug only - never branch on it
  "requestId":  "0f3c9a1e-...",     // echoes X-Request-Id, ties to Graylog
  "serverTime": "2026-09-15T10:22:31.482+07:00",
  "data":       { ... }             // null for operations with no payload
}"""

HEADERS = """X-Key-Id      : key_7f3a91              <- NEW, see note
X-Signature   : base64(ECDSA-SHA256 over canonical)
X-Timestamp   : 1789456951482
X-Nonce       : 0f3c9a1e-4b2d-...
X-Request-Id  : 0f3c9a1e-4b2d-...   (echoed back as requestId)

canonical = METHOD \\n path+query \\n timestamp \\n nonce \\n b64(sha256(body))"""

PAYMENT_RESOURCE = """{
  "paymentId":     "pay_01J8Z3K...",   // backend-owned primary key
  "invoiceNumber": "000123",           // human-readable, printed on receipt
  "merchantRefId": "8f2a...",          // caller-owned idempotency key
  "status":  "APPROVED",   // PENDING | APPROVED | DECLINED | CANCELLED
                           // | EXPIRED | VOIDED | REVERSED
  "instrument": "CARD",    // CARD | QRIS_DYNAMIC | QRIS_STATIC
  "type":       "SALE",    // SALE | INSTALLMENT | VOID | REVERSAL
  "amount":     150000,    // Long, rupiah penuh (bukan Int - lihat hal. 11)
  "tipAmount":  0,
  "currency":   "IDR",
  "createdAt":  "2026-09-15T10:22:31.482+07:00",
  "paidAt":     "2026-09-15T10:22:39.101+07:00",
  "acquirerRrn":  "512834771902",
  "approvalCode": "884213",
  "batchNumber":  "000012",

  "card": {                        // hadir hanya bila instrument = CARD
    "maskedPan":        "481111******1114",
    "cardHolderName":   "BUDI SANTOSO",
    "entryMode":        "IC",      // IC | TAP | SWIPE
    "acquirerName":     "BCA",
    "applicationLabel": "DEBIT BCA",
    "isDebit":          true
  },
  "qris": {                        // hadir hanya bila instrument = QRIS_*
    "qrPayload":    "00020101021226...",
    "wallet":       "GOPAY",
    "customerPan":  "936000...",
    "customerName": "B*** S***",
    "issuerName":   "Gopay"
  },
  "metadata": {}                   // titipan pemanggil, tidak ditafsirkan
}"""

ERROR_CODES = """HTTP  code                    arti / tindakan device
----  ----------------------  ----------------------------------
400   VALIDATION_ERROR        bug wiring - jangan retry
401   SIGNATURE_INVALID       cek clock skew, retry backoff
401   DEVICE_NOT_PROVISIONED  paksa ke alur provisioning
403   DEVICE_REVOKED          hapus key+config, ke Login Teknisi   <- §7 (a)
404   PAYMENT_NOT_FOUND       -
409   PAYMENT_STATE_CONFLICT  mis. void transaksi yang sudah void
422   UNSUPPORTED_METHOD      metode tidak aktif di config terminal
429   RATE_LIMITED            retry dengan backoff
503   ACQUIRER_UNAVAILABLE    transient - retry backoff             <- §7 (b)

PENTING: kartu DITOLAK bukan error HTTP.
  Itu interaksi API yang sukses dengan hasil pembayaran negatif:
  HTTP 200 + data.status = "DECLINED".
  4xx/5xx hanya untuk request cacat, auth gagal, atau server rusak."""

MQTT_PAYLOAD = """topic:  cashup/edc/{tid}/events        // satu topic, dua jenis pesan

// 1 - status pembayaran (menggantikan payment/{userDevice} lama)
{ "eventType": "PAYMENT_STATUS",
  "paymentId": "pay_01J8Z3K...",
  "status":    "APPROVED",
  "occurredAt": "2026-09-15T10:22:39.101+07:00" }

// 2 - config merchant berubah (menggantikan get_app_config saat start)
{ "eventType": "CONFIG_CHANGED",
  "configVersion": 8,
  "occurredAt": "2026-09-15T11:03:00.000+07:00" }

Device menerima CONFIG_CHANGED -> panggil POST /v1/provisioning/refresh."""


# ===========================================================================
# Page 1 - User journey, full app
# ===========================================================================

def page_journey(doc: Document) -> None:
    p = doc.page("1 · User Journey — Full App")
    p.title(
        "User Journey — mobile-cashup-payment (EDC)",
        "Tiga jalur pemicu transaksi · app fully stateless · auth = digital signature, tanpa login harian",
    )

    p.node("<b>Cashier</b><br>di device", 60, 150, 150, 50, "actor")
    p.node("<b>App kasir eksternal</b><br>deeplink cashlez://", 60, 230, 150, 50, "actor")
    p.node("<b>Controller ECR/POSH</b><br>via bridge-api", 60, 310, 150, 50, "actor")
    p.node("<b>Teknisi</b><br>kredensial admin Cashup", 60, 430, 150, 50, "actor")

    splash = p.node("<b>Splash / Bootstrap</b><br>cek signing key di Keystore", 290, 150, 230, 60, "screen")
    prov_q = p.node("Sudah<br>terprovisioning?", 290, 250, 230, 70, "decision")

    login = p.node("<b>Provisioning — Login Teknisi</b>", 290, 430, 230, 50, "screen")
    scan = p.node("<b>Provisioning — Scan QR</b>", 290, 510, 230, 50, "screen")
    proc = p.node("<b>Provisioning — Processing</b><br>inject DUKPT + simpan key (atomic)", 290, 590, 230, 60, "api")
    presult = p.node("<b>Provisioning — Result</b>", 290, 680, 230, 50, "ok")

    home = p.node("<b>Home / Standby</b><br>juga listening state untuk<br>trigger app-to-app & ECR", 620, 250, 230, 70, "screen")

    p.edge(splash, prov_q)
    p.edge(prov_q, login, "belum (J1)")
    p.edge(prov_q, home, "sudah")
    p.edge(login, scan)
    p.edge(scan, proc)
    p.edge(proc, presult)
    p.edge(presult, home, "sukses")
    p.edge(presult, scan, "gagal — retry (JWT masih hidup)", weak=True)

    # Journey clusters
    sale_entry = p.node("<b>Sale Entry</b><br>nominal + metode, satu layar", 620, 400, 230, 55, "screen")
    p.edge(home, sale_entry, "J4/J5/J6 — Sale")

    card_proc = p.node("<b>Card Payment Processing</b><br>tap/insert/swipe + PIN", 950, 370, 230, 55, "hw")
    inst = p.node("<b>Installment Selection</b><br>tenor / bank", 950, 290, 230, 45, "screen")
    qr_gen = p.node("<b>QRIS Generate</b><br>Dynamic (amount) / Static (tanpa)", 950, 460, 230, 55, "screen")

    p.edge(sale_entry, card_proc, "Card")
    p.edge(sale_entry, qr_gen, "QRIS")
    p.edge(card_proc, inst, "eligible installment (J10)", weak=True)
    p.edge(inst, card_proc, "", weak=True)

    result = p.node("<b>Payment Result</b><br>approved / declined / expired", 1270, 415, 220, 60, "ok")
    p.edge(card_proc, result)
    p.edge(qr_gen, result, "MQTT push, poll >5s")

    p.node(
        "Hasil → Print Receipt · Done · Void<br>"
        "App-to-app/ECR → countdown + callback",
        1270, 500, 220, 55, "note",
    )

    hist = p.node("<b>Transaction History</b><br>live-query, tanpa cache lokal", 620, 500, 230, 50, "screen")
    detail = p.node("<b>Transaction Detail</b>", 950, 560, 230, 45, "screen")
    p.edge(home, hist, "J9/J12")
    p.edge(hist, detail)
    p.edge(detail, result, "Void (J9) / Reprint (J12)", weak=True)

    settle = p.node("<b>Settlement</b> → <b>Settlement Result</b><br>total trx, total amount, batch", 620, 580, 230, 55, "screen")
    p.edge(home, settle, "J11")

    devset = p.node("<b>Device Settings</b><br>Re-Provisioning (J2) · Deactivate (J3)", 620, 670, 230, 55, "screen")
    p.edge(home, devset)
    p.edge(devset, login, "keduanya lewat login teknisi", weak=True)

    p.node("Cashier memulai di Home (J4–J6, J9, J11, J12)", 230, 155, 0, 0, "subtitle")

    a2a = p.node("<b>App-to-App / Deeplink Router</b><br>komponen, bukan screen<br>validasi provisioned → langsung ke Processing", 290, 810, 300, 70, "api")
    ecr = p.node("<b>ECR Bridge Status Pusher</b><br>push tiap perubahan state → tunggu acknowledge", 620, 810, 300, 70, "api")
    recon = p.node("<b>Reconciliation-on-launch</b><br>GET /v1/payments?status=PENDING<br>pengganti offline queue (app stateless)", 950, 810, 300, 70, "api")
    p.edge(a2a, card_proc, "skip Home & Sale Entry (J7)", weak=True)
    p.edge(ecr, result, "J8", weak=True)
    p.edge(splash, recon, "tiap app start (§7)", weak=True)

    p.code("Header tiap request ke Front-facing API", HEADERS, 1270, 150, 430)
    p.code("Envelope response — seragam untuk 16 endpoint", ENVELOPE, 1270, 630, 430)


# ===========================================================================
# Page 2 - Screen map
# ===========================================================================

def page_screens(doc: Document) -> None:
    p = doc.page("2 · Peta Navigasi Screen")
    p.title(
        "Peta Navigasi Screen",
        "Sesuai inventaris screen spec §5 · Amount Entry + Method Selection sudah digabung jadi Sale Entry (§11)",
    )

    splash = p.node("<b>Splash / Bootstrap</b>", 620, 130, 220, 45, "screen")

    login = p.node("<b>Provisioning — Login Teknisi</b>", 120, 240, 230, 45, "screen")
    scan = p.node("<b>Provisioning — Scan QR</b>", 120, 320, 230, 45, "screen")
    proc = p.node("<b>Provisioning — Processing</b>", 120, 400, 230, 45, "api")
    pres = p.node("<b>Provisioning — Result</b>", 120, 480, 230, 45, "ok")
    deact = p.node("<b>Deactivate Device</b>", 120, 560, 230, 45, "fail")

    p.edge(splash, login, "belum terprovisioning")
    p.edge(login, scan, "alur J1/J2")
    p.edge(login, deact, "alur J3")
    p.edge(scan, proc)
    p.edge(proc, pres)

    home = p.node("<b>Home / Standby</b>", 620, 240, 220, 45, "screen")
    p.edge(splash, home, "sudah terprovisioning")
    p.edge(pres, home, "sukses")
    p.edge(pres, scan, "gagal", weak=True)
    p.edge(deact, login, "key & config dihapus", weak=True)

    sale = p.node("<b>Sale Entry</b><br>keypad + Card / QRIS Dyn / QRIS Static", 430, 360, 240, 55, "screen")
    cardp = p.node("<b>Card Payment Processing</b>", 430, 460, 240, 45, "hw")
    instl = p.node("<b>Installment Selection</b>", 430, 540, 240, 45, "screen")
    cardr = p.node("<b>Card Payment Result</b>", 430, 620, 240, 45, "ok")

    qrgd = p.node("<b>QRIS Generate (Dynamic)</b>", 760, 460, 240, 45, "screen")
    qrgs = p.node("<b>QRIS Generate (Static)</b>", 760, 540, 240, 45, "screen")
    qrr = p.node("<b>QRIS Payment Result</b>", 760, 620, 240, 45, "ok")

    p.edge(home, sale, "Sale")
    p.edge(sale, cardp, "Card")
    p.edge(sale, qrgd, "QRIS Dynamic")
    p.edge(sale, qrgs, "QRIS Static")
    p.edge(cardp, instl, "eligible", weak=True)
    p.edge(instl, cardp, "", weak=True)
    p.edge(cardp, cardr)
    p.edge(qrgd, qrr)
    p.edge(qrgs, qrr)

    hist = p.node("<b>Transaction History</b>", 1090, 300, 220, 45, "screen")
    det = p.node("<b>Transaction Detail</b>", 1090, 380, 220, 45, "screen")
    p.edge(home, hist)
    p.edge(hist, det)
    p.edge(det, cardr, "Void", weak=True)

    stl = p.node("<b>Settlement</b>", 1090, 470, 220, 45, "screen")
    stlr = p.node("<b>Settlement Result</b>", 1090, 545, 220, 45, "ok")
    p.edge(home, stl)
    p.edge(stl, stlr)

    rcpt = p.node("<b>Receipt Preview / Print</b><br>dipanggil dari Result & Detail", 1090, 630, 220, 55, "hw")
    p.edge(cardr, rcpt, "", weak=True)
    p.edge(qrr, rcpt, "", weak=True)
    p.edge(stlr, rcpt, "", weak=True)
    p.edge(det, rcpt, "Reprint", weak=True)

    dset = p.node("<b>Device Settings</b><br>serial, TID/MID, versi, status MQTT", 620, 730, 240, 55, "screen")
    p.edge(home, dset)
    p.edge(dset, login, "Re-Provisioning / Deactivate", weak=True)

    p.node(
        "<b>Dihapus dari app lama</b><br>"
        "· Amount Entry terpisah → digabung ke Sale Entry<br>"
        "· Customer Signature (sale & void) → tidak dipakai lagi<br>"
        "· Dual login (middleware + POS) → tidak ada login harian<br>"
        "· Offline queue / PENDING-SYNCED → app stateless",
        120, 680, 300, 110, "note",
    )


# ===========================================================================
# Page 3 - J1 Provisioning
# ===========================================================================

def page_provisioning(doc: Document) -> None:
    p = doc.page("3 · J1 Provisioning")
    p.title(
        "J1 — Provisioning (setup pertama kali)",
        "Tidak ada login teknisi. Otorisasi datang dari challengeCode hasil scan QR. Atomic: gagal di langkah mana pun -> semua key dihapus, "
        "device tetap belum terprovisioning. Lima lifeline (Provisioning.drawio): App Provisioning, EDC, Backend, Payment HSM, GP HSM.",
    )

    ids = p.chain(
        [
            ("<b>App Provisioning</b><br>generate QR -> { challengeCode }", "screen"),
            ("<b>EDC</b><br>scan QR -> decode challengeCode", "screen"),
            ("<b>EDC</b><br>generate RSA (unwrap) + Ed25519 (signing) keypair", "hw"),
            ("<b>POST .../qr-redeem</b><br>TANPA signature (device belum terdaftar)", "api"),
            ("<b>Backend</b><br>validasi QR & nonaktifkan challenge-code", "hw"),
            ("<b>POST .../orders/{orderId}/package</b><br>signed -> wrappedPackageKey", "api"),
            ("<b>EDC</b><br>unwrap RSA di TEE, inject DUKPT (vendor dulu, vault TEE menyusul)", "hw"),
            ("<b>POST .../orders/{orderId}/activate</b><br>signed -> kirim keyCheckValues", "api"),
            ("<b>Provisioning — Result (Sukses)</b><br>tampil serialNumber, orderId, backing", "ok"),
        ],
        60, 130, 320, 55, 26,
    )

    fail = p.node("<b>Provisioning — Result (Gagal)</b><br>rollback total (wipe key + state), device tetap belum terprovisioning", 60, 900, 320, 55, "fail")
    p.edge(ids[7], fail, "gagal di langkah mana pun", weak=True)
    p.edge(fail, ids[1], "retry, tanpa login (tidak ada login sama sekali)", weak=True)

    p.code(
        "POST v1/terminal-key-provisioning/qr-redeem",
        """REQ  {
       "challengeCode":  "ABCD-1234",
       "serialNumber":   "PAX-A920-0012938",
       "rsaPublicKey":   "MIIBIjANBgkqhkiG9w0...",
       "eddsaPublicKey": "MCowBQYDK2VwAyEA..."
     }

RESP data {
       "orderId":         "ord_7f3a91",
       "activationToken": "eyJhbGciOi..."
     }

Tanpa X-Signature - backend belum mengenal public key device;
kunci itu baru dikirim di request ini sendiri.""",
        450, 130, 520,
    )

    p.code(
        "POST v1/terminal-key-provisioning/orders/{orderId}/package",
        """REQ  { "orderId": "ord_7f3a91", "activationToken": "eyJhbGciOi..." }

RESP data {
       "orderId":            "ord_7f3a91",
       "wrappedPackageKey":  "base64(RSA-OAEP(...))",   // #4 DUKPT
       "appEddsaPublicKey":  "MCowBQYDK2VwAyEA..."
     }

Backend: generate keyId (KSN) -> Get IPEK dari Payment HSM ->
Get ED25519 Public Key dari General Purpose HSM -> kirim IPEK
dibungkus RSA. Signed (X-Signature dst, lihat spec §3.3).""",
        450, 420, 520,
    )

    p.code(
        "POST v1/terminal-key-provisioning/orders/{orderId}/activate",
        """REQ  {
       "activationToken":  "eyJhbGciOi...",
       "keyCheckValues":   { "<purpose>": "<kcv hex>" }
     }

RESP data { "status": "OK" }

Signed. Langkah 16 diagram tim ("Callback Provisioning Status" ke
App Provisioning) bukan urusan app ini - backend yang memberitahu,
EDC tidak mengirim apa pun ke App Provisioning.""",
        450, 700, 520,
    )

    p.node(
        "<b>Tiga key, tiga tempat (spec §4.1)</b><br><br>"
        "<b>RSA-2048</b> (unwrap identitas) — AndroidKeyStore kalau MGF1 backend "
        "memungkinkan (§4.5), fallback BouncyCastle + EncryptedSharedPreferences.<br><br>"
        "<b>Ed25519</b> (signing request) — BouncyCastle + EncryptedSharedPreferences. "
        "<b>TIDAK hardware-backed</b>: <code>KEY_ALGORITHM_ED25519</code> tidak ada "
        "di Android sampai API 37.<br><br>"
        "<b>DUKPT (IPEK/KSN)</b> — modul vendor dulu, vault TEE sebagai cermin "
        "(<code>DuktpVaultCompat</code>), hanya ditulis kalau modul vendor konfirmasi sukses.",
        1000, 130, 420, 300, "note",
    )

    p.code("Error yang khas di alur ini", """PROVISIONING_TOKEN_INVALID          token/QR tidak valid, sudah dipakai, atau kedaluwarsa
TERMINAL_KEY_ALREADY_PROVISIONED    device sudah pernah diprovisioning penuh
TERMINAL_NOT_REGISTERED             device belum terdaftar di backend
TERMINAL_INACTIVE                   device tidak aktif
DEVICE_SIGNATURE_REQUIRED           signature tidak diterima backend

Selalu branch pada error.code, tidak pernah pada error.message.""", 1000, 460, 420)


# ===========================================================================
# Page 4 - J2 / J3
# ===========================================================================

def page_reprov(doc: Document) -> None:
    p = doc.page("4 · J2 Re-Provisioning & J3 Deactivation")
    p.title(
        "J2 — Re-Provisioning · J3 — Deactivation / Factory Reset",
        "DI LUAR SCOPE plan provisioning (spec §10): diagram tim tidak punya endpoint untuk keduanya. "
        "Tidak ada login teknisi di J1 sehingga alur di bawah ini juga tidak melalui login — tapi bentuknya masih tebakan.",
    )

    a = p.chain(
        [
            ("<b>Device Settings</b> → Re-Provisioning", "screen"),
            ("<b>Scan QR</b> → challengeCode baru", "screen"),
            ("<b>Generate keypair BARU</b><br>RSA + Ed25519", "hw"),
            ("<b>Endpoint BELUM ADA</b><br>menebak kontraknya sekarang = kerja yang dibuang (spec §10)", "fail"),
            ("<b>Ganti DUKPT + key + config</b><br>key lama dibuang setelah sukses", "hw"),
            ("<b>Result (Sukses)</b> → Home", "ok"),
        ],
        60, 130, 320, 50, 26,
    )

    b = p.chain(
        [
            ("<b>Device Settings</b> → Deactivate Device", "screen"),
            ("<b>Konfirmasi deaktivasi</b>", "decision"),
            ("<b>Endpoint BELUM ADA</b><br>menebak kontraknya sekarang = kerja yang dibuang (spec §10)", "fail"),
            ("<b>Hapus signing key + RSA key + state</b>", "hw"),
            ("<b>TerminalKeyInstaller.wipe()</b><br>hapus DUKPT dari vendor + vault", "hw"),
            ("<b>Kembali ke Gate (belum terprovisioning)</b>", "fail"),
        ],
        430, 130, 320, 50, 26,
    )

    p.node(
        "<b>Kenapa halaman ini tidak punya kontrak HTTP</b><br><br>"
        "<code>Provisioning.drawio</code> tim (sumber kewenangan tertinggi untuk "
        "bentuk body, spec provisioning §1) hanya memuat J1. Tidak ada sequence "
        "diagram untuk refresh key atau deaktivasi.<br><br>"
        "Path/body di bawah ini sebelumnya dikarang sebagai "
        "<code>POST /v1/provisioning/refresh</code> dan "
        "<code>POST /v1/provisioning/deactivate</code> — <b>keduanya tidak "
        "pernah ada di kontrak nyata manapun</b> dan sudah dibuang dari halaman "
        "ini. Menunggu backend menyediakan endpoint (spec provisioning §10) "
        "sebelum diagram ini diisi ulang.",
        800, 130, 480, 260, "note",
    )

    p.code(
        "Yang sudah pasti dari J1 (dapat dipakai ulang)",
        """Otorisasi:      tidak ada login - challengeCode QR (spec §2)
Signing:        Ed25519, canonical string sama (spec §3.3)
Envelope resp:  { data, error{code,message,details}, meta{correlationId} }
                (spec §3.2) - branch pada error.code, bukan message
DUKPT wipe:     TerminalKeyInstaller.wipe() - tidak ada API hapus resmi
                di vault (spec §4.3), kopling ke nama file internal
                edc-sdk sebagai utang sementara.""",
        800, 420, 480,
    )


# ===========================================================================
# Page 5 - J4 / J10 card
# ===========================================================================

def page_card(doc: Document) -> None:
    p = doc.page("5 · J4 Card Sale & J10 Installment")
    p.title(
        "J4 — Standalone Sale (Kartu / CDCP) · J10 — Installment",
        "Menggantikan sale/sale_trx + sale/installment_trx + sale/is_debit_check (yang semuanya balas Call<String> mentah)",
    )

    ids = p.chain(
        [
            ("<b>Sale Entry</b><br>nominal + pilih Card", "screen"),
            ("<b>Device buat merchantRefId (UUID)</b><br>sebelum kirim — kunci idempotency", "hw"),
            ("<b>Card Payment Processing</b><br>tap/insert/swipe + PIN (UI vendor SDK)", "hw"),
            ("<b>Installment Selection</b><br>hanya bila kartu eligible (J10)", "screen"),
            ("<b>POST /v1/payments/card</b><br>ditandatangani SigningInterceptor", "api"),
            ("<b>Card Payment Result</b><br>approved / declined", "ok"),
            ("<b>Print · Done · Void</b>", "screen"),
        ],
        60, 130, 330, 55, 28,
    )

    p.code(
        "POST /v1/payments/card   — request",
        """{
  "merchantRefId": "8f2a7c14-...",   // dibuat device, retry aman
  "amount":        150000,
  "tipAmount":     0,
  "type":          "SALE",           // SALE | INSTALLMENT
  "installment":   null,             // diisi hanya bila type=INSTALLMENT
  "card": {
    "entryMode":      "IC",          // IC | TAP | SWIPE
    "track2":         "4811...",
    "icData":         "9F2608...",
    "pinBlock":       "A1B2...",
    "isPinUsed":      true,
    "cardHolderName": "BUDI SANTOSO"
  },
  "pointerId": null,                 // pass-through app-to-app, opaque
  "metadata":  {}
}

// varian J10 — installment:
  "type": "INSTALLMENT",
  "installment": { "planCode": "BCA06", "tenor": 6 }""",
        460, 130, 520,
    )

    p.code("RESP 200 — data (resource Payment seragam)", PAYMENT_RESOURCE, 1010, 130, 520)

    p.node(
        "<b>is_debit_check &amp; card_routing_check</b><br><br>"
        "Dua endpoint terpisah di app lama. <code>isDebit</code> sekarang jadi "
        "field di <code>data.card</code> pada response yang sama — satu "
        "round-trip hilang dari setiap transaksi kartu.<br><br>"
        "<code>card_routing_check</code> ditunda dari v1 sesuai spec §2, "
        "bersama <code>loyalty_point_trx</code>.",
        460, 560, 520, 120, "note",
    )

    p.node(
        "<b>Kartu ditolak ≠ error HTTP</b><br><br>"
        "Penolakan issuer adalah hasil bisnis, bukan kegagalan API: "
        "<b>HTTP 200</b> dengan <code>data.status = \"DECLINED\"</code> dan "
        "<code>data.approvalCode = null</code>. Device menampilkan layar "
        "Declined, bukan layar error jaringan.<br><br>"
        "Ini penting karena <code>safeApiCall</code> memetakan non-2xx ke "
        "<code>ApiResult.Failure</code> — kalau decline dikirim sebagai 4xx, "
        "device akan salah menyebutnya gangguan koneksi.",
        460, 700, 520, 140, "note",
    )


# ===========================================================================
# Page 6 + 7 - QRIS
# ===========================================================================

def page_qris_dynamic(doc: Document) -> None:
    p = doc.page("6 · J5 QRIS Dynamic")
    p.title(
        "J5 — Standalone Sale (QRIS Dynamic)",
        "MQTT push sebagai jalur utama · poll fallback hanya dibuka setelah 5 detik tanpa push, lalu ditutup lagi",
    )

    ids = p.chain(
        [
            ("<b>Sale Entry</b><br>nominal + pilih QRIS Dynamic", "screen"),
            ("<b>POST /v1/payments/qris</b><br>mode = DYNAMIC", "api"),
            ("<b>QRIS Generate (Dynamic)</b><br>render qrPayload, status = PENDING", "screen"),
            ("Push MQTT<br>datang &lt; 5 detik?", "decision"),
            ("<b>QRIS Payment Result</b>", "ok"),
        ],
        60, 130, 330, 55, 30,
    )

    poll = p.node("<b>Buka poll fallback</b><br>GET /v1/payments/{id} tiap 3 detik<br>berhenti saat final atau timeout", 460, 385, 300, 70, "api")
    p.edge(ids[3], poll, "tidak")
    p.edge(poll, ids[4], "status final")
    p.edge(ids[3], ids[4], "ya")

    cancel = p.node("<b>Cancel / Timeout</b><br>POST /v1/payments/{id}/cancel", 60, 480, 330, 50, "fail")
    p.edge(ids[2], cancel, "cashier batal", weak=True)

    p.code(
        "POST /v1/payments/qris   — request",
        """{
  "merchantRefId": "b41d9e02-...",
  "amount":        150000,        // WAJIB bila mode = DYNAMIC
  "mode":          "DYNAMIC",     // DYNAMIC | STATIC
  "wallet":        "QRIS",
  "pointerId":     null,
  "metadata":      {}
}""",
        800, 130, 500,
    )

    p.code(
        "RESP 201 — data (potongan yang relevan)",
        """{
  "paymentId":     "pay_01J8Z3K...",
  "invoiceNumber": "000124",
  "status":        "PENDING",
  "instrument":    "QRIS_DYNAMIC",
  "amount":        150000,
  "expiresAt":     "2026-09-15T10:27:31+07:00",
  "qris": {
    "qrPayload": "00020101021226610014COM.GO-JEK...",
    "wallet":    "QRIS"
  }
}

Catatan: qrPayload dikirim SIAP PAKAI, tidak terenkripsi DUKPT.
Enkripsi hs_qr_string_enc di app lama ada karena QR dibuat di
host tanpa kanal ter-otentikasi. Sekarang seluruh response sudah
lewat kanal yang ditandatangani, jadi lapisan itu redundan.""",
        800, 300, 500,
    )

    p.code("MQTT — satu topic, dua jenis pesan", MQTT_PAYLOAD, 800, 640, 500)


def page_qris_static(doc: Document) -> None:
    p = doc.page("7 · J6 QRIS Static")
    p.title(
        "J6 — Standalone Sale (QRIS Static)",
        "\"Static\" = tanpa amount ter-embed, BUKAN QR yang dipakai berulang · tetap generate ulang tiap sesi",
    )

    ids = p.chain(
        [
            ("<b>Sale Entry</b><br>pilih QRIS Static, tanpa isi nominal", "screen"),
            ("<b>POST /v1/payments/qris</b><br>mode = STATIC, amount = null", "api"),
            ("<b>QRIS Generate (Static)</b><br>customer isi nominal di e-wallet", "screen"),
            ("<b>Tunggu status</b><br>MQTT push (utama) + poll fallback", "api"),
            ("<b>QRIS Payment Result</b><br>tampilkan amount yang BENAR-BENAR dibayar", "ok"),
        ],
        60, 130, 340, 60, 30,
    )

    unattended = p.node(
        "<b>Unattended payment</b><br>push datang saat tidak ada QR di layar<br>→ tetap diproses & masuk history",
        460, 320, 300, 70, "note",
    )
    p.edge(ids[3], unattended, "tanpa sesi aktif", weak=True)

    p.node(
        "<b>Perbaikan atas spec §9 item 5 — race condition hilang</b><br><br>"
        "<b>App lama:</b> status dicek dengan \"ambil transaksi TERAKHIR milik "
        "merchant\", lalu device mencocokkan sendiri by invoice/id/timestamp. "
        "Kalau dua device di merchant yang sama menampilkan QRIS Static "
        "berdekatan waktu, device bisa mengklaim pembayaran milik device lain.<br><br>"
        "<b>Kontrak baru:</b> <code>GET /v1/payments/{paymentId}</code> — "
        "session-scoped by construction. Device tidak pernah perlu menebak "
        "transaksi mana miliknya, karena ia menanyakan paymentId yang ia "
        "terima sendiri saat generate.<br><br>"
        "Ini bukan tambalan; jalur yang memungkinkan bug itu tidak ada lagi.",
        800, 130, 520, 220, "note",
    )

    p.code(
        "POST /v1/payments/qris  (STATIC)  — request & response",
        """REQ  {
       "merchantRefId": "c72f1a88-...",
       "amount":        null,          // static = tanpa amount
       "mode":          "STATIC",
       "wallet":        "QRIS",
       "metadata":      {}
     }

RESP data {
       "paymentId":     "pay_01J8Z4M...",
       "invoiceNumber": "000125",
       "status":        "PENDING",
       "instrument":    "QRIS_STATIC",
       "amount":        null,          // belum diketahui
       "qris": { "qrPayload": "00020101021126..." }
     }

Setelah dibayar, GET /v1/payments/{id} mengembalikan:
       "status": "APPROVED",
       "amount": 87500,                // nominal pilihan customer
       "qris": { "customerName": "B*** S***", "wallet": "GOPAY" }""",
        800, 380, 520,
    )

    p.node(
        "<b>Aturan dedupe yang dipertahankan dari app lama</b><br>"
        "Push MQTT untuk QRIS Static <b>tidak</b> digating oleh ada/tidaknya "
        "sesi aktif. Dedupe hanya berdasarkan <code>paymentId</code> yang sudah "
        "diproses. Pembayaran yang masuk saat device idle tetap dicatat dan "
        "muncul di history sebagai unattended — tidak dibuang diam-diam.",
        60, 460, 340, 150, "note",
    )


# ===========================================================================
# Page 8 - J7 / J8
# ===========================================================================

def page_a2a(doc: Document) -> None:
    p = doc.page("8 · J7 App-to-App & J8 ECR Bridge")
    p.title(
        "J7 — App-to-App / Deeplink · J8 — ECR/POSH Bridge",
        "Keduanya melewati Home & Sale Entry · idempotency dijamin backend, bukan dicek device lebih dulu",
    )

    a = p.chain(
        [
            ("<b>Intent / deeplink masuk</b><br>cashlez://pay?amount=&type=&callback=", "actor"),
            ("<b>App-to-App Router</b><br>validasi device terprovisioning", "api"),
            ("<b>Langsung ke Processing / Generate</b><br>skip Home & Sale Entry", "screen"),
            ("<b>POST /v1/payments/{card|qris}</b><br>merchantRefId = id milik pemanggil", "api"),
            ("<b>Result + countdown auto-return</b>", "ok"),
            ("<b>Callback ke app pemanggil</b><br>status, tipe, data transaksi", "actor"),
        ],
        60, 130, 340, 55, 26,
    )

    reject = p.node("<b>Tolak — callback error langsung</b><br>DEVICE_NOT_PROVISIONED", 460, 215, 300, 50, "fail")
    p.edge(a[1], reject, "belum terprovisioning", weak=True)

    b = p.chain(
        [
            ("<b>Controller ECR/POSH trigger</b>", "actor"),
            ("<b>Proses sama seperti J7</b>", "screen"),
            ("<b>bridge-api push status</b><br>tiap perubahan state signifikan", "api"),
            ("<b>Tunggu acknowledge</b>", "decision"),
        ],
        60, 520, 340, 55, 26,
    )

    p.code(
        "Idempotency — jaminan pindah ke backend",
        """App lama (device yang menanggung):
  1. device cek dulu: adakah transaksi dgn merchant_trx_id ini?
  2. kalau tidak ada -> baru generate
  => dua round-trip, dan tetap ada celah balapan di antaranya

Kontrak baru (backend yang menjamin):
  POST /v1/payments/card  { "merchantRefId": "8f2a..." }

  Backend memperlakukan (deviceId, merchantRefId) sebagai UNIK.
  Request kedua dengan pasangan yang sama TIDAK membuat transaksi
  baru - ia mengembalikan transaksi yang sudah ada:

     HTTP 200
     { "status":"SUCCESS", "code":"OK",
       "data": { ...transaksi ASLI, bukan yang baru... } }

  => satu round-trip, tanpa celah balapan.

Berlaku juga untuk standalone: device membuat merchantRefId
sendiri SEBELUM mengirim, sehingga kirim-ulang setelah koneksi
putus (§7) terbukti aman - bukan sekadar diharapkan aman.""",
        800, 130, 540,
    )

    p.code(
        "bridge-api — pesan ke controller ECR",
        """POST {controllerBaseUrl}/payment-status      // satu endpoint,
{                                             // bukan 4 (-cdcp,
  "eventType":  "PAYMENT_STATUS",             // -qris, -va, -bnpl)
  "paymentId":  "pay_01J8Z3K...",
  "instrument": "CARD",
  "status":     "APPROVED",
  "amount":     150000,
  "occurredAt": "2026-09-15T10:22:39+07:00"
}

POST {controllerBaseUrl}/acknowledge
{ "paymentId": "pay_01J8Z3K...", "receivedAt": "..." }""",
        800, 520, 540,
    )

    p.node(
        "<b>Dua lubang keamanan app lama yang TIDAK dibawa</b><br>"
        "Audit <code>bridge-api</code> lama menemukan: (1) kunci &amp; IV AES "
        "di-hardcode di source, (2) TLS trust-all (semua sertifikat diterima). "
        "Keduanya harus absen dari implementasi baru — dicatat di sini supaya "
        "muncul saat review, bukan ditemukan saat pentest.",
        800, 760, 540, 110, "note",
    )


# ===========================================================================
# Page 9 - J9 void / reversal
# ===========================================================================

def page_void(doc: Document) -> None:
    p = doc.page("9 · J9 Void & Reversal")
    p.title(
        "J9 — Void · dan Reversal (teknis)",
        "Sengaja TIDAK dilebur: void = pembatalan bisnis atas transaksi sukses, reversal = host tidak menjawab",
    )

    a = p.chain(
        [
            ("<b>Transaction History</b><br>GET /v1/payments?from=&amp;to=", "screen"),
            ("<b>Transaction Detail</b><br>GET /v1/payments/{id}", "screen"),
            ("<b>Konfirmasi Void</b>", "decision"),
            ("<b>POST /v1/payments/{id}/void</b>", "api"),
            ("<b>Result (varian void)</b>", "ok"),
        ],
        60, 140, 330, 55, 28,
    )

    b = p.chain(
        [
            ("<b>Transaksi kartu dikirim</b>", "api"),
            ("<b>Host tidak menjawab / timeout</b>", "fail"),
            ("<b>POST /v1/payments/{id}/reversal</b><br>otomatis, tanpa interaksi cashier", "api"),
            ("<b>Transaksi dibatalkan di sisi host</b>", "ok"),
        ],
        460, 140, 330, 55, 28,
    )

    p.code(
        "POST /v1/payments/{id}/void     (J9 — bisnis)",
        """REQ  { "reason": "CASHIER_REQUEST" }

RESP data {
       "paymentId":   "pay_01J8Z3K...",   // transaksi ASAL
       "status":      "VOIDED",
       "voidPaymentId": "pay_01J8Z9Q...", // transaksi void-nya
       "voidedAt":    "2026-09-15T10:41:02+07:00"
     }

409 PAYMENT_STATE_CONFLICT bila sudah ter-void
409 juga bila transaksi sudah masuk batch settlement""",
        860, 140, 500,
    )

    p.code(
        "POST /v1/payments/{id}/reversal   (teknis)",
        """REQ  { "reason": "HOST_TIMEOUT" }

RESP data { "paymentId": "...", "status": "REVERSED" }

Dipicu device secara otomatis, tidak pernah oleh cashier.""",
        860, 420, 500,
    )

    p.node(
        "<b>Kenapa void dan reversal tidak dilebur</b><br><br>"
        "Keduanya \"membatalkan transaksi\", tapi artinya berbeda saat "
        "rekonsiliasi:<br><br>"
        "<b>Void</b> — transaksi benar-benar terjadi, lalu dibatalkan atas "
        "permintaan. Muncul di laporan sebagai sale + void.<br><br>"
        "<b>Reversal</b> — device tidak pernah tahu apakah transaksi jadi atau "
        "tidak, karena host diam. Ini pernyataan \"anggap tidak pernah terjadi\".<br><br>"
        "Meleburnya akan membuat laporan settlement tidak bisa membedakan "
        "pembatalan yang disengaja dari kegagalan komunikasi.",
        860, 560, 500, 220, "note",
    )


# ===========================================================================
# Page 10 - J11 / J12
# ===========================================================================

def page_settlement(doc: Document) -> None:
    p = doc.page("10 · J11 Settlement & J12 History/Reprint")
    p.title(
        "J11 — Settlement · J12 — Reprint / Lookup Histori",
        "6 endpoint history + 5 endpoint receipt di app lama → masing-masing jadi 1",
    )

    a = p.chain(
        [
            ("<b>Home</b> → Settlement", "screen"),
            ("<b>POST /v1/settlements</b><br>batch untuk terminal ini", "api"),
            ("<b>Settlement Result</b><br>total trx, total amount, batch number", "ok"),
            ("<b>GET /v1/settlements/{id}/receipt</b> → Print", "hw"),
        ],
        60, 140, 330, 55, 30,
    )

    b = p.chain(
        [
            ("<b>Home</b> → Transaction History", "screen"),
            ("<b>GET /v1/payments?from=&amp;to=&amp;status=</b><br>live-query, tanpa cache lokal", "api"),
            ("<b>Transaction Detail</b><br>GET /v1/payments/{id}", "screen"),
            ("<b>GET /v1/payments/{id}/receipt</b> → Reprint", "hw"),
        ],
        460, 140, 330, 55, 30,
    )

    p.code(
        "GET /v1/payments  — satu endpoint menggantikan enam",
        """GET /v1/payments?from=2026-09-01&to=2026-09-15
                 &status=APPROVED&instrument=CARD
                 &settlementId=stl_01J8&limit=50&cursor=...

Menggantikan:
  additional_service/get_all_success_sale_list
  additional_service/get_all_success_void_list
  trx_history/get_all_success_payment_list      (x5 modul)
  trx_history/get_all_pending_payment_list      (x4 modul)
  trx_history/get_all_refund_list
  trx_history/get_last_trx_data                 (x5 modul)

RESP data {
  "items": [ { ...resource Payment... } ],
  "nextCursor": "eyJvZmZzZXQiOjUwfQ",
  "totalCount": 137
}

Rekonsiliasi saat launch (§7) tidak butuh endpoint sendiri:
  GET /v1/payments?status=PENDING""",
        860, 140, 520,
    )

    p.code(
        "Settlement",
        """POST /v1/settlements
REQ  {}                        // terminal sudah diketahui dari X-Key-Id
RESP data {
       "settlementId":  "stl_01J8Z9...",
       "batchNumber":   "000012",
       "totalCount":    37,
       "totalAmount":   4875000,
       "settledAt":     "2026-09-15T21:00:04+07:00",
       "breakdown": [
         { "instrument":"CARD",          "count":21, "amount":3120000 },
         { "instrument":"QRIS_DYNAMIC",  "count":14, "amount":1655000 },
         { "instrument":"QRIS_STATIC",   "count": 2, "amount": 100000 }
       ]
     }

GET /v1/settlements?from=&to=        -> daftar batch
GET /v1/settlements/{id}             -> detail + ringkasan
GET /v1/settlements/{id}/receipt     -> struk batch
GET /v1/payments?settlementId={id}   -> transaksi dalam batch""",
        860, 520, 520,
    )

    p.node(
        "<b>5 endpoint receipt → 1</b><br>"
        "<code>sale/print_receipt</code>, <code>void/print_receipt</code>, "
        "<code>settlement/print_receipt</code>, "
        "<code>reprint_trx_receipt_after_settlement</code>, "
        "<code>reprint_last_settlement</code><br><br>"
        "→ <code>GET /v1/payments/{id}/receipt</code> dan "
        "<code>GET /v1/settlements/{id}/receipt</code>.<br>"
        "\"Reprint\" bukan operasi terpisah — mengambil struk transaksi yang "
        "sudah lewat identik dengan mengambil struk transaksi baru.",
        60, 420, 730, 130, "note",
    )


# ===========================================================================
# Page 11 - contract summary
# ===========================================================================

def page_contract(doc: Document) -> None:
    p = doc.page("11 · Kontrak API — 16 Endpoint")
    p.title(
        "Kontrak v1 — 16 endpoint menggantikan 43",
        "150 endpoint di app lama → 91 hilang karena penyempitan scope (spec §12) → 43 relevan → 16 setelah normalisasi",
    )

    p.code(
        "Provisioning  (3, sesuai spec 2026-09-16-provisioning-design.md §3)",
        """POST v1/terminal-key-provisioning/qr-redeem              TANPA signature
POST v1/terminal-key-provisioning/orders/{orderId}/package    signed
POST v1/terminal-key-provisioning/orders/{orderId}/activate   signed

Tidak ada login teknisi / temp JWT - otorisasi dari challengeCode QR.
J2 Re-Provisioning & J3 Deactivation: endpoint BELUM ADA (spec §10).""",
        60, 140, 600,
    )

    p.code(
        "Payment  (7)",
        """POST /v1/payments/card            sale + installment
POST /v1/payments/qris            dynamic + static
GET  /v1/payments/{id}            status - SESSION-SCOPED (§9 item 5)
GET  /v1/payments                 history + reconciliation (§9 item 6)
POST /v1/payments/{id}/void       pembatalan bisnis
POST /v1/payments/{id}/reversal   host tidak menjawab
POST /v1/payments/{id}/cancel     batalkan sesi QR yang belum dibayar
GET  /v1/payments/{id}/receipt    struk (termasuk reprint)""",
        60, 290, 600,
    )

    p.code(
        "Settlement  (4)",
        """POST /v1/settlements              jalankan batch
GET  /v1/settlements              daftar batch
GET  /v1/settlements/{id}         detail batch
GET  /v1/settlements/{id}/receipt struk batch""",
        60, 500, 600,
    )

    p.code("Kode error & pemetaannya ke ApiResult", ERROR_CODES, 700, 140, 620)

    p.node(
        "<b>Yang hilang, dan kenapa</b><br><br>"
        "<b>91 endpoint</b> — POS, Prepaid, VA, BNPL, CNP, PPOB, Cash, Promo, "
        "CashlezLink. Di luar scope EDC channel (spec §12).<br><br>"
        "<b>8 ejaan \"get config\"</b> → nol. Config menumpang response "
        "provisioning; perubahan disiarkan lewat MQTT.<br><br>"
        "<b>2 endpoint signature pelanggan</b> → dihapus, tidak dipakai lagi.<br><br>"
        "<b>card_routing_check, loyalty_point_trx</b> → ditunda dari v1 (spec §2).<br><br>"
        "<b>is_debit_check</b> → jadi field <code>data.card.isDebit</code>.<br><br>"
        "<b>6 endpoint history</b> → satu GET dengan filter.<br>"
        "<b>5 endpoint receipt</b> → dua GET sub-resource.",
        700, 480, 620, 300, "note",
    )

    p.node(
        "<b>Konsekuensi untuk kode yang sudah ada</b><br><br>"
        "<b>1. <code>signing-core</code> kurang satu header.</b> "
        "<code>SigningInterceptor</code> mengirim X-Signature, X-Timestamp, "
        "X-Nonce — tapi tidak ada yang memberitahu backend device mana yang "
        "mengirim, sehingga kunci publik mana yang harus dipakai untuk "
        "verifikasi. Kontrak ini menambahkan <b>X-Key-Id</b>.<br><br>"
        "<b>2. <code>safeApiCall</code> menuntut HTTP status yang jujur.</b> "
        "Ia bercabang di <code>response.isSuccessful</code>; kalau backend "
        "selalu balas 200, setiap penolakan terbaca sebagai sukses.<br><br>"
        "<b>3. Uang harus <code>Long</code>.</b> App lama memakai "
        "<code>Int</code> untuk <code>base_amount</code> di QRIS (mentok "
        "Rp 2,1 miliar) tapi <code>Long</code> di CDCP. Total settlement "
        "harian bisa melewati batas itu — dan yang terjadi bukan error, "
        "melainkan angka yang salah diam-diam.",
        60, 620, 600, 300, "note",
    )


# ===========================================================================

def main() -> None:
    doc = Document()
    page_journey(doc)
    page_screens(doc)
    page_provisioning(doc)
    page_reprov(doc)
    page_card(doc)
    page_qris_dynamic(doc)
    page_qris_static(doc)
    page_a2a(doc)
    page_void(doc)
    page_settlement(doc)
    page_contract(doc)
    doc.write(OUT)
    print(f"wrote {OUT}  ({len(doc.pages)} pages)")


if __name__ == "__main__":
    main()
