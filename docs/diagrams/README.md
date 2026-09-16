# Diagram

`edc-payment-flows.drawio` — 11 halaman: user journey penuh, peta navigasi screen,
dan satu halaman per flow (J1–J12) lengkap dengan JSON request/response kontrak v1.

Buka di [app.diagrams.net](https://app.diagrams.net) atau ekstensi Draw.io Integration
di VS Code / IntelliJ.

## Jangan disunting manual

File `.drawio` itu **hasil generate**, bukan sumber. Kontrak API masih bergerak,
jadi menyunting 11 halaman dengan tangan akan hilang saat regenerate berikutnya.

Ubah `generate_drawio.py`, lalu:

```bash
python docs/diagrams/generate_drawio.py
```

Tidak ada dependency di luar pustaka standar Python 3.9+.

## Isi

| Berkas | Peran |
|---|---|
| `generate_drawio.py` | Sumber kebenaran — isi journey & kontrak API |
| `drawio_lib.py` | Pembangun dokumen mxGraph (node, edge, blok kode) |
| `edc-payment-flows.drawio` | Hasil generate |

## Rujukan

- `docs/EDC_PAYMENT_APP_DESIGN.md` — spec: journey J1–J12 (§4), inventaris screen (§5)
- Kontrak v1 (16 endpoint) masih dalam pembahasan; halaman 11 merangkum bentuk terkini
  beserta konsekuensinya untuk `signing-core` dan `common-core`.
