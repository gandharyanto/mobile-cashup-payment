# Home Navigation Shell (app-v3 UI Parity, Sub-project 2a) — Design Spec

**Tanggal:** 2026-09-22
**Status:** disetujui, siap dijadikan implementation plan
**Bagian dari inisiatif lebih besar:** UI parity dengan `app-v3` (`mobile-apps-cashlez`) — sub-project 2 dari roadmap `docs/superpowers/specs/2026-09-22-vendor-sdk-ui-transaction-adoption-roadmap.md`. app-v3 punya 31 activities + 8 fragments — terlalu besar untuk satu spec, didekomposisi per-flow. Ini adalah **potongan pertama (2a): shell navigasi Home**, tempat semua flow lain (CDCP yang sudah ada, dan flow-flow mendatang) di-hook.

---

## 1. Konteks

Saat ini `mobile-cashup-payment` **belum punya Home sama sekali** — `GateFragment`/`ResultFragment` (alur provisioning) langsung lompat ke `CardPaymentFragment` (kalkulator quick-pay, dibangun ad-hoc di ujung sub-project 1) begitu device sudah ter-provisioning. Spec ini menyisipkan shell Home bottom-navigation di antaranya, meniru `HomeActivity` app-v3 **sama persis secara visual** (icon, warna, ukuran) — instruksi eksplisit user: *"sama persis button, icon, ukuran, semuanya sama jangan ada yang berbeda disisi UI"*.

**Batasan penting yang membedakan dari app-v3, sudah dikonfirmasi user:**
- **Tidak ada login** — project ini pakai auth device-signature, bukan akun merchant (prinsip lama project ini, dikonfirmasi ulang: "tanpa login"). Tidak ada `LoginActivity` yang diport.
- **Tidak ada POS cart** — project ini "lean payment-execution engine", bukan POS app. User eksplisit: *"POS nanti dulu"* — toggle Simple/POS tetap ada **secara visual** (karena app-v3 punya itu), tapi mode POS-nya tidak difungsikan.
- **Tab yang datanya tidak ada** (Merchant Info — butuh akun; History/Features — butuh flow lain yang belum dibangun) — user: *"dummy dulu aja"*. Tab-tab ini tetap ada secara visual/bisa diklik, isinya placeholder statis, diisi fungsi nyata di sub-project 2b/2c dst.

---

## 2. Ringkasan perubahan

| Aspek | Sekarang | Spec ini |
|---|---|---|
| Setelah provisioning selesai | Langsung ke `CardPaymentFragment` (kalkulator) | Ke `HomeFragment` (shell bottom-nav baru); tab Home default menampilkan `CardPaymentFragment` yang sama |
| Struktur navigasi Home | Tidak ada | `BottomNavigationView` 4 tab: Home, History, Features, Merchant Info — **posisi/icon/warna identik app-v3** |
| Arsitektur | Single-Activity + Navigation Component (`MainActivity` + `nav_provisioning.xml`) | **Tidak berubah** — `HomeFragment` adalah Fragment baru di graph yang sama, BUKAN Activity terpisah seperti `HomeActivity` app-v3 (app-v3 pakai Activity-per-screen; project ini sudah established single-Activity — visual sama, pola navigasi ikut project ini) |
| Tab Home | — | `CardPaymentFragment` (reuse, tidak diubah) + toggle Simple/POS **visual-only** di atasnya (POS non-fungsional) |
| Tab History, Features, Merchant Info | — | Fragment baru, layout pixel-exact dari app-v3, konten dummy/statis |

---

## 3. Arsitektur & struktur module

Tetap di module `app` (bukan module baru) — `HomeFragment` dkk adalah bagian dari "composition root dan navigasi" yang sudah jadi tanggung jawab `app` per README project ini. Package baru: `com.cashup.app.ui.home` (sejajar dengan `com.cashup.app.ui.provisioning` yang sudah ada).

```
app/src/main/kotlin/com/cashup/app/ui/home/
    HomeFragment.kt          -- host BottomNavigationView + child NavHostFragment
    ModeSelectorView.kt      -- (atau inline di HomeFragment) toggle Simple/POS, visual-only
    HistoryFragment.kt       -- dummy
    FeaturesFragment.kt      -- dummy
    MerchantInfoFragment.kt  -- dummy, isi device/provisioning info alih-alih akun

app/src/main/res/
    layout/fragment_home.xml           -- porting activity_home.xml (root ConstraintLayout jadi Fragment)
    layout/layout_mode_selector.xml    -- porting persis dari app-v3
    layout/fragment_history_dummy.xml
    layout/fragment_features_dummy.xml
    layout/fragment_merchant_info_dummy.xml
    menu/bottom_nav_menu.xml           -- porting persis dari app-v3 (4 item, id/icon/title sama)
    navigation/nav_home.xml            -- child nav graph untuk 4 tab
    drawable/ic_nav_{home,history,feature,profile}_{active,default}_figma.xml  -- disalin dari app-v3
    color/bottom_navigation_selector.xml     -- disalin persis (state_checked #252D63 / default #646464 / disabled #C6C6C6)
    color/mode_selector_icon_selector.xml    -- disalin persis
    color/mode_selector_text_selector.xml    -- disalin persis
    drawable/mode_selector_container_background.xml  -- disalin persis
    drawable/ic_revamp_transaction.xml, ic_revamp_cashier.xml  -- disalin persis
```

`HomeFragment` (Fragment, bukan Activity) berisi struktur yang sama seperti `activity_home.xml` app-v3, hanya root elemen jadi `<androidx.constraintlayout.widget.ConstraintLayout>` biasa (bukan dibungkus `<layout>` DataBinding jika project ini tidak pakai DataBinding di `app` — cek `viewBinding` sudah `true` di `app/build.gradle.kts`, pakai ViewBinding seperti fragment provisioning yang sudah ada, bukan DataBinding):
- `ll_header` → header sederhana (logo Cashup + status online), tanpa greeting merchant (tidak ada nama merchant untuk ditampilkan — pakai teks statis atau kosongkan area itu, bukan crash/placeholder aneh).
- `toolbar` → include `layout_mode_selector.xml`, toggle Simple/POS **visual saja**: `btnSimple`/`btnPos` mengubah `isSelected` dan container fragment (`fragment_container`), TAPI `btnPos` untuk sekarang menampilkan `FeaturesFragment`-style dummy ("Mode Kasir — segera hadir") alih-alih `PosFragment` sungguhan (yang tidak dibangun di spec ini).
- `fragment_container` → default `CardPaymentFragment` (tab Home / Simple mode).
- `bottomNavigationView` → persis spesifikasi §4.

Warna/dimensi (`@dimen/_50sdp` dkk, sistem scalable-size app-v3) — cek apakah project ini sudah punya dependency `com.intuit.sdp`/sejenis (dipakai `feature-card-payment` untuk parity kalkulator, kemungkinan sudah ada). Kalau sudah, reuse; kalau belum, itu temuan untuk task implementasi pertama (bukan diasumsikan di sini).

---

## 4. Detail visual — bottom navigation (dari riset app-v3, harus persis)

`menu/bottom_nav_menu.xml` — 4 item:

| id | icon (drawable) | title |
|---|---|---|
| `nav_home` | `ic_nav_home_selector` (active: `ic_nav_home_active_figma`, default: `ic_nav_home_default_figma`) | "Home" |
| `nav_history` | `ic_nav_history_selector` (active/default `_figma` sama pola) | "History" |
| `nav_features` | `ic_nav_feature_selector` | "Features" |
| `nav_merchant_info` | `ic_nav_profile_selector` | judul akun (di sini: ganti jadi label netral seperti "Device" karena bukan akun — lihat §6) |

`BottomNavigationView`:
- `layout_height="@dimen/_50sdp"`, `minHeight="@dimen/_45sdp"`, `background="@color/light_100"` (`#FFFFFF`)
- padding start/end `_12sdp`, top `_4sdp`, bottom `_2sdp`
- `app:elevation="0dp"`, `app:itemActiveIndicatorStyle="@null"`, `app:itemIconSize="_18sdp"`
- `app:itemIconTint="@color/bottom_navigation_selector"`, `app:itemTextColor="@color/bottom_navigation_selector"` — state list: checked `#252D63`, default `#646464`, disabled `#C6C6C6`
- `app:labelVisibilityMode="labeled"`, teks 10sp/lineHeight 15sp (active: semibold, inactive: regular — samakan style/font weight yang sudah dipakai `feature-card-payment` untuk font Instrument Sans app-v3)

Toggle mode selector (`layout_mode_selector.xml`): `MaterialCardView` root, `TopRoundedCard` shape, dua `LinearLayout` (`btnSimple`/`btnPos`) masing-masing icon (`ic_revamp_transaction`/`ic_revamp_cashier`) + label ("Transaksi"/"Kasir"), warna state dari `mode_selector_icon_selector`/`mode_selector_text_selector`. Semua disalin persis dari app-v3, hanya listener `btnPos`-nya diarahkan ke dummy (§6), bukan `PosFragment`.

---

## 5. Navigasi (`nav_provisioning.xml`)

```xml
<fragment android:id="@+id/gateFragment" ...>
    <action android:id="@+id/to_sale" app:destination="@id/homeFragment"
        app:popUpTo="@id/gateFragment" app:popUpToInclusive="true" />
</fragment>

<fragment android:id="@+id/resultFragment" ...>
    <action android:id="@+id/to_sale" app:destination="@id/homeFragment"
        app:popUpTo="@id/resultFragment" app:popUpToInclusive="true" />
</fragment>

<fragment android:id="@+id/homeFragment"
    android:name="com.cashup.app.ui.home.HomeFragment"
    tools:layout="@layout/fragment_home" />
```

`saleFragment` (destinasi `CardPaymentFragment` langsung) di graph luar **dihapus** — `CardPaymentFragment` sekarang hanya diakses sebagai child destination di dalam `HomeFragment`'s nested nav graph (`nav_home.xml`), bukan destination top-level lagi. `action to_scan` yang ada di `CardPaymentFragment` (kembali ke scan QR, dipakai kalau device belum ter-provisioning saat sale dicoba — cek konteks aslinya) perlu re-routing lewat parent nav controller (`requireActivity().findNavController(R.id.nav_host)` atau setara) karena sekarang berada di nested graph.

---

## 6. Konten dummy (§6.2 roadmap: diisi sub-project 2b/2c dst)

- **`HistoryFragment`** — layout kosong dengan empty-state visual app-v3 (ikon+teks "Belum ada transaksi" kalau app-v3 punya itu; kalau tidak ketemu di riset, pakai empty-state generik dengan style yang sama). Tidak fetch data apa pun.
- **`FeaturesFragment`** — grid tile kosong/statis, bisa cuma render 1 tile "Kartu (CDCP)" yang benar-benar berfungsi (navigasi ke Home tab / `CardPaymentFragment`) sebagai bukti pola grid jalan, sisanya (QRIS, Prepaid, dst) sebagai tile ter-disable/"Segera hadir" — TIDAK build ulang seluruh `DataCapability.getMenuList` app-v3 (itu POS-related, di luar scope).
- **`MerchantInfoFragment`** — ganti total isinya (bukan port dari `AccountMerchantFragment` yang butuh akun): tampilkan `deviceId`, `serialNumber`, status provisioning dari `AppContainer` (data yang SUDAH ada di project ini, lewat `provisioning-core`) — layout tetap pakai kerangka visual app-v3 (card style, spacing) tapi field-nya device info, bukan profil merchant. Label tab di bottom-nav: **"Device"** bukan "Akun" (app-v3's `title_account_profile` tidak relevan tanpa akun).
- **Mode POS (`btnPos`)** — tampilkan dummy sederhana ("Mode Kasir — segera hadir") saat diklik, TIDAK build `PosFragment` app-v3.

---

## 7. Testing

Shell ini murni UI/navigasi — tidak ada logic bisnis baru selain switching tab & toggle mode. Verifikasi:
- Unit test (kalau ada logic non-trivial, mis. helper yang menentukan fragment mana yang aktif) — kalau cuma stock `BottomNavigationView`+`NavController`/`FragmentManager` wiring, tidak perlu unit test terpisah.
- Verifikasi visual via skill `run`: jalankan app, provisioning device test, screenshot tiap tab, bandingkan manual dengan app-v3 (icon/warna/ukuran) — bukan klaim "sama persis" tanpa dilihat.

---

## 8. Di luar scope

- **`PosFragment`/mode Kasir sungguhan** — ditunda eksplisit ("POS nanti dulu"), sub-project terpisah nanti.
- **Data nyata di History/Features/Merchant Info** — dummy dulu, diisi tiap kali flow terkait dibangun (sub-project 2b QRIS, dst).
- **`LoginActivity`/akun merchant** — tidak diport sama sekali, project ini tidak punya konsep akun (dikonfirmasi user: "tanpa login").
- **`LockModeFragment` (mode unattended/kiosk)** — tidak dibahas spec ini, terpisah dari Login, bisa jadi sub-project sendiri kalau dibutuhkan nanti.
- **`SplashActivity`'s device-integrity/root-check/RSA-key-gen-before-provisioning logic** — sudah ada padanannya di alur provisioning project ini (`GateFragment` dkk), tidak diport ulang.

---

## 9. Konsekuensi terhadap kode yang sudah ada

| Berkas | Nasib |
|---|---|
| `app/src/main/res/navigation/nav_provisioning.xml` | Diubah — `to_sale` dari Gate/Result menuju `homeFragment`, `saleFragment` top-level dihapus |
| `app/src/main/kotlin/com/cashup/app/ui/home/*.kt` | Baru (5 file: HomeFragment + 3 dummy Fragment + mode selector) |
| `app/src/main/res/{layout,menu,navigation,drawable,color}/*` | Baru — aset diport pixel-exact dari `mobile-apps-cashlez/app-v3` per §3-4 |
| `feature-card-payment/.../CardPaymentFragment.kt` | **Tidak diubah** — direuse apa adanya sebagai child destination baru |
| `feature-card-payment/.../CardPaymentFragment.kt`'s `to_scan` action | Perlu **re-routing** ke parent NavController karena posisinya sekarang nested (satu-satunya perubahan perilaku pada file yang sudah ada, bukan penulisan ulang logic) |
| `provisioning-core`, `cdcp-core`, `device-sdk-*` | Tidak disentuh |
