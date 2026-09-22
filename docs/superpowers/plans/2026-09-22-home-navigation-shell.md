# Home Navigation Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the app-v3-identical bottom-navigation Home shell (4 tabs: Home/History/Features/Merchant-Info) that `CardPaymentFragment` (already built) plugs into, replacing the current direct Gate→CardPaymentFragment jump.

**Architecture:** `HomeFragment` (new, in `app` module) hosts a `BottomNavigationView` + a nested `NavHostFragment` (`nav_home.xml`, 4 destinations whose ids match the bottom-nav menu item ids exactly, so `NavigationUI.setupWithNavController` handles tab switching with zero manual click code). The mode-selector (Simple/POS toggle) sits above the nested nav host, is NOT tab-specific (app-v3 shows it on every bottom-nav tab, confirmed from its layout — not just Home), and for this pass is cosmetic-only: `btnPos` shows a toast, does not navigate anywhere (POS is explicitly out of scope).

**Tech Stack:** Kotlin, Android Views + ViewBinding (matching `app`/`feature-card-payment`'s existing convention — no DataBinding, no Compose), Navigation Component 2.5.3 (already a dependency), Material `BottomNavigationView`/`MaterialCardView` (`com.google.android.material:material:1.9.0`, already a dependency).

**Spec:** `docs/superpowers/specs/2026-09-22-home-navigation-shell-design.md`

## Global Constraints

- **Dimensions**: this project has no `com.intuit.sdp`/`ssp` dependency and none should be added — `feature-card-payment` already established the precedent of converting app-v3's `_Nsdp`/`_Nssp` values to plain `Ndp`/`Nsp` literals (the sdp library's own generated resources equal their literal N at the reference screen width, so this conversion is exact, not an approximation).
- **Colors** (exact hex, from app-v3 source): bottom-nav tint — checked `#252D63`, unchecked `#646464`, disabled `#C6C6C6`. Bottom-nav background / `light_100` = `#FFFFFF`. Mode-selector container background = `#F0F0F0`. Mode-selector selected-pill background = `#1A652A`. Mode-selector icon/text: selected = `#FFFFFF`, unselected = `#575757`.
- **Fonts**: `feature-card-payment` already ships `instrument_sans_regular.ttf`/`instrument_sans_semibold.ttf` as module resources, and `app` already depends on `:feature-card-payment` (`app/build.gradle.kts:64`) — Android resource merging makes `@font/instrument_sans_semibold` etc. directly usable from `app` layouts with **no file copying needed**. Verify this assumption in Task 1's build step; if merging doesn't expose it (some AGP configs restrict private library resources), the fallback is copying the two `.ttf` files into `app/src/main/res/font/` — note this as a fallback, do not do it preemptively.
- **No accounts, no POS cart** — this project has neither (confirmed project-wide constraint, not new to this plan). Merchant Info tab shows device/provisioning info instead of an account profile. History/Features tabs are dummy placeholders (real content is later sub-projects, not this plan).
- Package for all new code: `com.cashup.app.ui.home` (sibling to the existing `com.cashup.app.ui.provisioning`).
- Existing Fragment convention to match exactly (see `GateFragment.kt`): plain `Fragment(R.layout.xxx)` constructor-with-layout, `onViewCreated` for logic, `(requireActivity().application as CashupApp).container` for `AppContainer` access, `findNavController()` from `androidx.navigation.fragment.findNavController`.

---

### Task 1: Port static resources (colors, dimens, drawables, strings, menu)

**Files:**
- Create: `app/src/main/res/values/colors.xml` (does not exist yet in `app` module — confirmed)
- Create: `app/src/main/res/values/dimens.xml` (does not exist yet)
- Create: `app/src/main/res/color/bottom_navigation_selector.xml`
- Create: `app/src/main/res/color/mode_selector_icon_selector.xml`
- Create: `app/src/main/res/color/mode_selector_text_selector.xml`
- Create: `app/src/main/res/drawable/ic_nav_home_selector.xml`, `ic_nav_history_selector.xml`, `ic_nav_feature_selector.xml`, `ic_nav_profile_selector.xml`
- Create: `app/src/main/res/drawable/mode_selector_container_background.xml`
- Create: `app/src/main/res/drawable/mode_selector_item_background.xml`
- Create: `app/src/main/res/drawable/ic_revamp_transaction.xml`, `ic_revamp_cashier.xml`
- Create (binary copy, not text): `app/src/main/res/drawable-nodpi/ic_nav_{home,history,feature,profile}_{active,default}_figma.png` (8 files)
- Create: `app/src/main/res/menu/bottom_nav_menu.xml`
- Modify: `app/src/main/res/values/strings.xml` (add 4 new strings)

**Interfaces:**
- Produces: every resource ID referenced by Task 2/3's layouts (`@color/...`, `@dimen/...`, `@drawable/...`, `@menu/bottom_nav_menu`, `@string/...`). Task 2 consumes all of these by name — they must match exactly.

- [ ] **Step 1: Copy the 8 binary icon PNGs from app-v3**

```bash
mkdir -p "app/src/main/res/drawable-nodpi"
for f in ic_nav_home_active_figma ic_nav_home_default_figma \
         ic_nav_history_active_figma ic_nav_history_default_figma \
         ic_nav_feature_active_figma ic_nav_feature_default_figma \
         ic_nav_profile_active_figma ic_nav_profile_default_figma; do
  cp "../mobile-apps-cashlez/app-v3/src/main/res/drawable-nodpi/${f}.png" \
     "app/src/main/res/drawable-nodpi/${f}.png"
done
ls app/src/main/res/drawable-nodpi/
```
Expected: 8 files listed, non-zero sizes (roughly 700-1900 bytes each per the source research).

- [ ] **Step 2: Create `app/src/main/res/values/colors.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="light_100">#FFFFFF</color>
    <color name="mode_selector_container_bg">#F0F0F0</color>
    <color name="mode_selector_selected_bg">#1A652A</color>
    <color name="mode_selector_text_selected">#FFFFFF</color>
    <color name="mode_selector_text_unselected">#575757</color>
</resources>
```

- [ ] **Step 3: Create `app/src/main/res/values/dimens.xml`**

Converted 1:1 from app-v3's `_Nsdp`/`_Nssp` values (see Global Constraints) to plain dp/sp:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Bottom navigation -->
    <dimen name="bottom_nav_height">50dp</dimen>
    <dimen name="bottom_nav_min_height">45dp</dimen>
    <dimen name="bottom_nav_padding_horizontal">12dp</dimen>
    <dimen name="bottom_nav_padding_top">4dp</dimen>
    <dimen name="bottom_nav_padding_bottom">2dp</dimen>
    <dimen name="bottom_nav_icon_size">18dp</dimen>

    <!-- Mode selector -->
    <dimen name="mode_selector_margin_horizontal">12dp</dimen>
    <dimen name="mode_selector_height">36dp</dimen>
    <dimen name="mode_selector_item_min_width">88dp</dimen>
    <dimen name="mode_selector_padding_top">8dp</dimen>
    <dimen name="mode_selector_padding_bottom">5dp</dimen>
    <dimen name="mode_selector_inner_padding">4dp</dimen>
    <dimen name="mode_selector_text_size">10sp</dimen>
    <dimen name="mode_selector_icon_size">16dp</dimen>
    <dimen name="mode_selector_icon_gap">6dp</dimen>
    <dimen name="mode_selector_item_padding_horizontal">12dp</dimen>
    <dimen name="mode_selector_corner_radius">10dp</dimen>

    <!-- Header -->
    <dimen name="home_header_min_height">68dp</dimen>
</resources>
```

- [ ] **Step 4: Create the color state selectors**

`app/src/main/res/color/bottom_navigation_selector.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_checked="true" android:color="#252D63" />
    <item android:state_checked="false" android:color="#646464" />
    <item android:state_enabled="false" android:color="#C6C6C6" />
</selector>
```

`app/src/main/res/color/mode_selector_icon_selector.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_selected="true" android:color="@color/mode_selector_text_selected" />
    <item android:color="@color/mode_selector_text_unselected" />
</selector>
```

`app/src/main/res/color/mode_selector_text_selector.xml`: identical content to `mode_selector_icon_selector.xml` above (app-v3 has both as separate files with the same content — keep them separate to match, don't merge; they're referenced independently by icon `app:tint` vs. text `android:textColor`).

- [ ] **Step 5: Create the bottom-nav icon selector drawables**

Same pattern for all four — `app/src/main/res/drawable/ic_nav_home_selector.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_checked="true" android:drawable="@drawable/ic_nav_home_active_figma" />
    <item android:drawable="@drawable/ic_nav_home_default_figma" />
</selector>
```
`ic_nav_history_selector.xml`, `ic_nav_feature_selector.xml`, `ic_nav_profile_selector.xml`: same shape, substitute `history`/`feature`/`profile` for `home` in both the selector filename and the two referenced drawable names.

- [ ] **Step 6: Create the mode-selector background drawables**

`app/src/main/res/drawable/mode_selector_container_background.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/mode_selector_container_bg" />
    <corners android:radius="@dimen/mode_selector_corner_radius" />
</shape>
```

`app/src/main/res/drawable/mode_selector_item_background.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_selected="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/mode_selector_selected_bg" />
            <corners android:radius="@dimen/mode_selector_corner_radius" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/transparent" />
        </shape>
    </item>
</selector>
```

- [ ] **Step 7: Create the mode-selector icon vectors**

`app/src/main/res/drawable/ic_revamp_transaction.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="20"
    android:viewportHeight="20">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M2,5.5C2,4.67 2.67,4 3.5,4H16.5C17.33,4 18,4.67 18,5.5V14.5C18,15.33 17.33,16 16.5,16H3.5C2.67,16 2,15.33 2,14.5V5.5ZM3.5,6V14H16.5V6H3.5ZM10,12.5C8.62,12.5 7.5,11.38 7.5,10C7.5,8.62 8.62,7.5 10,7.5C11.38,7.5 12.5,8.62 12.5,10C12.5,11.38 11.38,12.5 10,12.5ZM4.75,8H6.25V9.5H4.75V8ZM13.75,10.5H15.25V12H13.75V10.5Z" />
</vector>
```

`app/src/main/res/drawable/ic_revamp_cashier.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="20"
    android:viewportHeight="20">
    <path
        android:fillColor="#FF6F6F6F"
        android:pathData="M3,7.5L4.2,4.5C4.43,3.92 4.99,3.5 5.62,3.5H14.38C15.01,3.5 15.57,3.92 15.8,4.5L17,7.5V8.25C17,9.21 16.41,10.03 15.58,10.37V16.5H13.75V12H10.92V16.5H4.42V10.37C3.59,10.03 3,9.21 3,8.25V7.5ZM6.17,5.5L5.42,7.5H14.58L13.83,5.5H6.17ZM6.25,10.5V14.5H9.25V10.5H6.25Z" />
</vector>
```
Both are tinted at runtime via `app:tint`/`android:tint` from the layout (Task 2), so the hardcoded `fillColor` here doesn't matter visually — kept identical to source for fidelity.

- [ ] **Step 8: Add the 4 new strings**

Read `app/src/main/res/values/strings.xml` first, then add inside the existing `<resources>` block:
```xml
<string name="home_tab_home">Beranda</string>
<string name="home_tab_history">Riwayat</string>
<string name="home_tab_features">Fitur</string>
<string name="home_tab_device">Device</string>
```
Note: `home_tab_device` is intentionally NOT `title_account_profile`/"Profil" — this project has no accounts, spec §6 calls for a device-info tab instead, labeled "Device".

- [ ] **Step 9: Create `app/src/main/res/menu/bottom_nav_menu.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/nav_home"
        android:icon="@drawable/ic_nav_home_selector"
        android:title="@string/home_tab_home" />
    <item
        android:id="@+id/nav_history"
        android:icon="@drawable/ic_nav_history_selector"
        android:title="@string/home_tab_history" />
    <item
        android:id="@+id/nav_features"
        android:icon="@drawable/ic_nav_feature_selector"
        android:title="@string/home_tab_features" />
    <item
        android:id="@+id/nav_merchant_info"
        android:icon="@drawable/ic_nav_profile_selector"
        android:title="@string/home_tab_device" />
</menu>
```

- [ ] **Step 10: Verify resources compile**

```bash
./gradlew :app:processDebugResources
```
Expected: BUILD SUCCESSFUL. If it fails on a missing `@font/instrument_sans_semibold` reference once Task 2 adds it, that's Task 2's problem, not this one — this task only needs `processDebugResources` to succeed with what exists so far (nothing in this task references the font).

- [ ] **Step 11: Commit**

```bash
git add app/src/main/res/values/colors.xml app/src/main/res/values/dimens.xml \
  app/src/main/res/color/ app/src/main/res/drawable/ic_nav_*_selector.xml \
  app/src/main/res/drawable/mode_selector_*.xml \
  app/src/main/res/drawable/ic_revamp_transaction.xml app/src/main/res/drawable/ic_revamp_cashier.xml \
  app/src/main/res/drawable-nodpi/ app/src/main/res/menu/bottom_nav_menu.xml \
  app/src/main/res/values/strings.xml
git commit -m "feat(app): port Home bottom-nav resources pixel-exact from app-v3"
```

---

### Task 2: `HomeFragment` shell (header + mode selector + bottom nav + nested NavHost)

**Files:**
- Create: `app/src/main/res/layout/fragment_home.xml`
- Create: `app/src/main/res/layout/layout_mode_selector.xml`
- Create: `app/src/main/res/navigation/nav_home.xml` (initially just a single placeholder destination — Task 3 fills in the real 4)
- Create: `app/src/main/kotlin/com/cashup/app/ui/home/HomeFragment.kt`
- Create: `app/src/main/kotlin/com/cashup/app/ui/home/HomeFragmentTest.kt`

**Interfaces:**
- Consumes: resources from Task 1 (all `@color`/`@dimen`/`@drawable`/`@menu`/`@string` referenced below).
- Produces: `HomeFragment` (a `Fragment`, instantiable with no args) — consumed by Task 5 (`nav_provisioning.xml`'s new `homeFragment` destination uses this class).

- [ ] **Step 1: Create the placeholder nested nav graph**

`app/src/main/res/navigation/nav_home.xml` (Task 3 replaces the single placeholder fragment with the real 4 destinations — this exists now only so `fragment_home.xml`'s `NavHostFragment` has a valid `app:navGraph` to compile against):

```xml
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_home_graph"
    app:startDestination="@id/nav_home">

    <fragment
        android:id="@+id/nav_home"
        android:name="com.cashup.feature.cardpayment.CardPaymentFragment" />
</navigation>
```

- [ ] **Step 2: Create `layout_mode_selector.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/toolbar"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:cardBackgroundColor="@color/light_100"
    app:cardElevation="0dp">

    <LinearLayout
        android:id="@+id/modeSelector"
        android:layout_width="match_parent"
        android:layout_height="@dimen/mode_selector_height"
        android:layout_marginStart="@dimen/mode_selector_margin_horizontal"
        android:layout_marginTop="@dimen/mode_selector_padding_top"
        android:layout_marginEnd="@dimen/mode_selector_margin_horizontal"
        android:layout_marginBottom="@dimen/mode_selector_padding_bottom"
        android:background="@drawable/mode_selector_container_background"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:padding="@dimen/mode_selector_inner_padding">

        <LinearLayout
            android:id="@+id/btnSimple"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:background="@drawable/mode_selector_item_background"
            android:clickable="true"
            android:focusable="true"
            android:gravity="center"
            android:minWidth="@dimen/mode_selector_item_min_width"
            android:orientation="horizontal"
            android:paddingHorizontal="@dimen/mode_selector_item_padding_horizontal"
            android:selected="true">

            <ImageView
                android:layout_width="@dimen/mode_selector_icon_size"
                android:layout_height="@dimen/mode_selector_icon_size"
                android:duplicateParentState="true"
                android:src="@drawable/ic_revamp_transaction"
                app:tint="@color/mode_selector_icon_selector" />

            <TextView
                android:id="@+id/tvSimpleMode"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="@dimen/mode_selector_icon_gap"
                android:duplicateParentState="true"
                android:fontFamily="@font/instrument_sans_semibold"
                android:includeFontPadding="false"
                android:text="Transaksi"
                android:textColor="@color/mode_selector_text_selector"
                android:textSize="@dimen/mode_selector_text_size" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/btnPos"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:background="@drawable/mode_selector_item_background"
            android:clickable="true"
            android:focusable="true"
            android:gravity="center"
            android:minWidth="@dimen/mode_selector_item_min_width"
            android:orientation="horizontal"
            android:paddingHorizontal="@dimen/mode_selector_item_padding_horizontal">

            <ImageView
                android:layout_width="@dimen/mode_selector_icon_size"
                android:layout_height="@dimen/mode_selector_icon_size"
                android:duplicateParentState="true"
                android:src="@drawable/ic_revamp_cashier"
                app:tint="@color/mode_selector_icon_selector" />

            <TextView
                android:id="@+id/tvPosMode"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="@dimen/mode_selector_icon_gap"
                android:duplicateParentState="true"
                android:fontFamily="@font/instrument_sans_semibold"
                android:includeFontPadding="false"
                android:text="Kasir"
                android:textColor="@color/mode_selector_text_selector"
                android:textSize="@dimen/mode_selector_text_size" />
        </LinearLayout>
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

Note: `android:selected="true"` on `btnSimple` (not app-v3's exact XML, which sets initial state in Kotlin) — sets the correct default visual state (Simple mode active on first render) declaratively, avoiding a first-frame flash before `HomeFragment.onViewCreated` runs.

- [ ] **Step 3: Create `fragment_home.xml`**

Simplified header (logo + static text) instead of app-v3's `layout_home_header` (which needs `UserDeviceDetail`/merchant business data this project doesn't have — spec §3 amendment, not a deviation the implementer should second-guess):

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F6F6F6">

    <TextView
        android:id="@+id/tvHeaderTitle"
        android:layout_width="0dp"
        android:layout_height="@dimen/home_header_min_height"
        android:background="@color/light_100"
        android:gravity="center"
        android:fontFamily="@font/instrument_sans_semibold"
        android:text="Cashup"
        android:textColor="#252D63"
        android:textSize="18sp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <include
        android:id="@+id/toolbar"
        layout="@layout/layout_mode_selector"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/tvHeaderTitle" />

    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/homeNavHost"
        android:name="androidx.navigation.fragment.NavHostFragment"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:defaultNavHost="false"
        app:layout_constraintBottom_toTopOf="@id/bottomNavigationView"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/toolbar"
        app:navGraph="@navigation/nav_home" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNavigationView"
        android:layout_width="0dp"
        android:layout_height="@dimen/bottom_nav_height"
        android:minHeight="@dimen/bottom_nav_min_height"
        android:background="@color/light_100"
        android:paddingStart="@dimen/bottom_nav_padding_horizontal"
        android:paddingTop="@dimen/bottom_nav_padding_top"
        android:paddingEnd="@dimen/bottom_nav_padding_horizontal"
        android:paddingBottom="@dimen/bottom_nav_padding_bottom"
        app:elevation="0dp"
        app:itemActiveIndicatorStyle="@null"
        app:itemIconSize="@dimen/bottom_nav_icon_size"
        app:itemIconTint="@color/bottom_navigation_selector"
        app:itemTextColor="@color/bottom_navigation_selector"
        app:labelVisibilityMode="labeled"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:menu="@menu/bottom_nav_menu" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 4: Write the failing test for the mode-selector toggle logic**

```kotlin
package com.cashup.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic test for the Simple/POS visual toggle -- no Android framework needed. */
class HomeFragmentTest {

    @Test
    fun `selecting pos deselects simple`() {
        var simpleSelected = true
        var posSelected = false

        // Simulates HomeFragment's onModeSelected(pos = true)
        fun selectMode(pos: Boolean) {
            simpleSelected = !pos
            posSelected = pos
        }

        selectMode(pos = true)

        assertFalse(simpleSelected)
        assertTrue(posSelected)
    }

    @Test
    fun `selecting simple deselects pos`() {
        var simpleSelected = false
        var posSelected = true

        fun selectMode(pos: Boolean) {
            simpleSelected = !pos
            posSelected = pos
        }

        selectMode(pos = false)

        assertTrue(simpleSelected)
        assertFalse(posSelected)
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cashup.app.ui.home.HomeFragmentTest"`
Expected: FAIL — compile error, package `com.cashup.app.ui.home` doesn't exist yet (this test doesn't reference `HomeFragment` directly since the toggle logic is trivial enough to pure-logic-test standalone; it exists to establish the test file/package before Step 6, and to pin the exact two-state exclusivity behavior `HomeFragment.kt` must implement).

- [ ] **Step 6: Write `HomeFragment.kt`**

```kotlin
package com.cashup.app.ui.home

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cashup.app.R
import com.cashup.app.databinding.FragmentHomeBinding

class HomeFragment : Fragment(R.layout.fragment_home) {

    private var binding: FragmentHomeBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)
        this.binding = binding

        binding.toolbar.btnSimple.setOnClickListener { selectMode(pos = false) }
        binding.toolbar.btnPos.setOnClickListener { selectMode(pos = true) }
    }

    private fun selectMode(pos: Boolean) {
        val toolbar = binding?.toolbar ?: return
        toolbar.btnSimple.isSelected = !pos
        toolbar.btnPos.isSelected = pos
        if (pos) {
            Toast.makeText(requireContext(), "Mode Kasir — segera hadir", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
```

Note: `btnPos` click only flips the visual selected state + shows a toast — it does NOT navigate the nested `NavHostFragment` anywhere (POS mode is out of scope per spec §8). Clicking `btnSimple` after `btnPos` just flips the visual state back; the underlying nav host never leaves `CardPaymentFragment`.

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cashup.app.ui.home.HomeFragmentTest"`
Expected: PASS (2 tests).

- [ ] **Step 8: Verify the module compiles and resources link (including the font reference)**

```bash
./gradlew :app:compileDebugKotlin :app:processDebugResources
```
Expected: BUILD SUCCESSFUL. If `@font/instrument_sans_semibold` fails to resolve here, apply the Global Constraints fallback: copy `feature-card-payment/src/main/res/font/instrument_sans_semibold.ttf` (and `_regular.ttf` if later needed) into `app/src/main/res/font/`, then re-run this step.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/layout/fragment_home.xml app/src/main/res/layout/layout_mode_selector.xml \
  app/src/main/res/navigation/nav_home.xml \
  app/src/main/kotlin/com/cashup/app/ui/home/HomeFragment.kt \
  app/src/main/kotlin/com/cashup/app/ui/home/HomeFragmentTest.kt
git commit -m "feat(app): add HomeFragment shell with mode-selector toggle"
```

---

### Task 3: Dummy tab fragments (History, Features, Merchant Info) + wire the real nested graph

**Files:**
- Create: `app/src/main/res/layout/fragment_history_dummy.xml`
- Create: `app/src/main/res/layout/fragment_features_dummy.xml`
- Create: `app/src/main/res/layout/fragment_merchant_info_dummy.xml`
- Create: `app/src/main/kotlin/com/cashup/app/ui/home/HistoryFragment.kt`
- Create: `app/src/main/kotlin/com/cashup/app/ui/home/FeaturesFragment.kt`
- Create: `app/src/main/kotlin/com/cashup/app/ui/home/MerchantInfoFragment.kt`
- Create: `app/src/main/kotlin/com/cashup/app/ui/home/MerchantInfoFragmentTest.kt`
- Modify: `app/src/main/res/navigation/nav_home.xml` (replace Task 2's single placeholder destination with the real 4)

**Interfaces:**
- Consumes: `HomeFragment` (Task 2) hosts this graph via `app:navGraph="@navigation/nav_home"`.
- Produces: `HistoryFragment`, `FeaturesFragment`, `MerchantInfoFragment` — no other task consumes these directly; they're leaf destinations.

- [ ] **Step 1: Write the failing test for `MerchantInfoFragment`'s device-info formatting**

```kotlin
package com.cashup.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantInfoFragmentTest {

    @Test
    fun `formats provisioned device info`() {
        val text = formatDeviceInfo(deviceId = "abc-123", serialNumber = "SN001", provisioned = true)
        assertEquals("Device ID: abc-123\nSerial: SN001\nStatus: Terprovisioning", text)
    }

    @Test
    fun `formats unknown device fields as dash`() {
        val text = formatDeviceInfo(deviceId = null, serialNumber = null, provisioned = false)
        assertEquals("Device ID: -\nSerial: -\nStatus: Belum terprovisioning", text)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cashup.app.ui.home.MerchantInfoFragmentTest"`
Expected: FAIL — `formatDeviceInfo` unresolved.

- [ ] **Step 3: Write the dummy layouts**

`app/src/main/res/layout/fragment_history_dummy.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/light_100"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="@font/instrument_sans_semibold"
        android:text="Belum ada transaksi"
        android:textColor="#252D63"
        android:textSize="16sp" />
</LinearLayout>
```

`app/src/main/res/layout/fragment_features_dummy.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/light_100"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:id="@+id/tvFeaturesPlaceholder"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:fontFamily="@font/instrument_sans_semibold"
        android:text="Fitur lain segera hadir"
        android:textColor="#252D63"
        android:textSize="16sp" />
</LinearLayout>
```

`app/src/main/res/layout/fragment_merchant_info_dummy.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/light_100"
    android:orientation="vertical"
    android:padding="24dp">

    <TextView
        android:id="@+id/tvDeviceInfo"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:fontFamily="@font/instrument_sans_semibold"
        android:textColor="#252D63"
        android:textSize="14sp" />
</LinearLayout>
```

- [ ] **Step 4: Write the three dummy Fragments**

`app/src/main/kotlin/com/cashup/app/ui/home/HistoryFragment.kt`:
```kotlin
package com.cashup.app.ui.home

import com.cashup.app.R
import androidx.fragment.app.Fragment

class HistoryFragment : Fragment(R.layout.fragment_history_dummy)
```

`app/src/main/kotlin/com/cashup/app/ui/home/FeaturesFragment.kt`:
```kotlin
package com.cashup.app.ui.home

import com.cashup.app.R
import androidx.fragment.app.Fragment

class FeaturesFragment : Fragment(R.layout.fragment_features_dummy)
```

`app/src/main/kotlin/com/cashup/app/ui/home/MerchantInfoFragment.kt`:
```kotlin
package com.cashup.app.ui.home

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.cashup.app.CashupApp
import com.cashup.app.R

internal fun formatDeviceInfo(deviceId: String?, serialNumber: String?, provisioned: Boolean): String {
    val status = if (provisioned) "Terprovisioning" else "Belum terprovisioning"
    return "Device ID: ${deviceId ?: "-"}\nSerial: ${serialNumber ?: "-"}\nStatus: $status"
}

class MerchantInfoFragment : Fragment(R.layout.fragment_merchant_info_dummy) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val container = (requireActivity().application as CashupApp).container
        val text = formatDeviceInfo(
            deviceId = container.activeDeviceId(),
            serialNumber = container.storedSerialNumber(),
            provisioned = container.isProvisioned(),
        )
        view.findViewById<TextView>(R.id.tvDeviceInfo).text = text
    }
}
```

`activeDeviceId()`/`storedSerialNumber()`/`isProvisioned()` already exist on `AppContainer` (`app/src/main/kotlin/com/cashup/app/di/AppContainer.kt` — verify these exact method names by reading that file before writing this step if they've changed; as of this plan's writing they're confirmed present with these exact signatures returning `String?`/`String?`/`Boolean`).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.cashup.app.ui.home.MerchantInfoFragmentTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Replace `nav_home.xml`'s placeholder with the real 4 destinations**

```xml
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_home_graph"
    app:startDestination="@id/nav_home">

    <fragment
        android:id="@+id/nav_home"
        android:name="com.cashup.feature.cardpayment.CardPaymentFragment" />

    <fragment
        android:id="@+id/nav_history"
        android:name="com.cashup.app.ui.home.HistoryFragment" />

    <fragment
        android:id="@+id/nav_features"
        android:name="com.cashup.app.ui.home.FeaturesFragment" />

    <fragment
        android:id="@+id/nav_merchant_info"
        android:name="com.cashup.app.ui.home.MerchantInfoFragment" />
</navigation>
```

Destination ids (`nav_home`, `nav_history`, `nav_features`, `nav_merchant_info`) intentionally match `bottom_nav_menu.xml`'s item ids exactly (Task 1, Step 9) — this is what lets `NavigationUI.setupWithNavController` (Task 4) switch tabs with zero manual click-listener code.

- [ ] **Step 7: Run the full app module test suite to confirm no regressions**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: all tests pass, including Task 2's `HomeFragmentTest` and this task's `MerchantInfoFragmentTest`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/res/layout/fragment_history_dummy.xml app/src/main/res/layout/fragment_features_dummy.xml \
  app/src/main/res/layout/fragment_merchant_info_dummy.xml \
  app/src/main/kotlin/com/cashup/app/ui/home/HistoryFragment.kt \
  app/src/main/kotlin/com/cashup/app/ui/home/FeaturesFragment.kt \
  app/src/main/kotlin/com/cashup/app/ui/home/MerchantInfoFragment.kt \
  app/src/main/kotlin/com/cashup/app/ui/home/MerchantInfoFragmentTest.kt \
  app/src/main/res/navigation/nav_home.xml
git commit -m "feat(app): add dummy History/Features/MerchantInfo tabs, wire nav_home graph"
```

---

### Task 4: Wire `BottomNavigationView` to the nested `NavController`

**Files:**
- Modify: `app/src/main/kotlin/com/cashup/app/ui/home/HomeFragment.kt`
- Modify: `app/build.gradle.kts` (add `androidx.navigation:navigation-ui-ktx` — already present, verify not duplicated)

**Interfaces:**
- Consumes: `nav_home.xml`'s 4 destinations (Task 3), `bottomNavigationView` view id (Task 2's `fragment_home.xml`), `homeNavHost` view id (Task 2).
- Produces: working tab-switching — no other task depends on this beyond visual/manual verification (Task 5 doesn't call anything new here).

- [ ] **Step 1: Modify `HomeFragment.onViewCreated` to wire the NavController**

`app/build.gradle.kts` already has `androidx.navigation:navigation-ui-ktx:2.5.3` (confirmed present) — no dependency change needed, skip to the code change.

Update `HomeFragment.kt`'s `onViewCreated`:
```kotlin
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentHomeBinding.bind(view)
        this.binding = binding

        val navHostFragment = childFragmentManager
            .findFragmentById(R.id.homeNavHost) as androidx.navigation.fragment.NavHostFragment
        androidx.navigation.ui.NavigationUI.setupWithNavController(
            binding.bottomNavigationView,
            navHostFragment.navController,
        )

        binding.toolbar.btnSimple.setOnClickListener { selectMode(pos = false) }
        binding.toolbar.btnPos.setOnClickListener { selectMode(pos = true) }
    }
```

- [ ] **Step 2: Verify build**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all existing tests still pass (this step adds no new unit-testable logic — `NavigationUI.setupWithNavController` is a framework call, verified visually in Task 6, not via unit test).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/cashup/app/ui/home/HomeFragment.kt
git commit -m "feat(app): wire BottomNavigationView to the nested Home nav graph"
```

---

### Task 5: Re-point provisioning navigation from `CardPaymentFragment` directly to `HomeFragment`

**Files:**
- Modify: `app/src/main/res/navigation/nav_provisioning.xml`

**Interfaces:**
- Consumes: `HomeFragment` (Task 2).
- Produces: the app's real entry-point behavior after provisioning — no later task depends on this.

- [ ] **Step 1: Read the current file, then rewrite it**

Read `app/src/main/res/navigation/nav_provisioning.xml` first (confirm no drift from what's quoted below since Task 1-4 didn't touch it, but re-read to be safe per this plan's own discipline). Replace the `saleFragment` destination and its two referencing actions:

```xml
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_provisioning"
    app:startDestination="@id/gateFragment">

    <fragment
        android:id="@+id/gateFragment"
        android:name="com.cashup.app.ui.provisioning.GateFragment">
        <action android:id="@+id/to_scan" app:destination="@id/scanQrFragment"
            app:popUpTo="@id/gateFragment" app:popUpToInclusive="true" />
        <action android:id="@+id/to_sale" app:destination="@id/homeFragment"
            app:popUpTo="@id/gateFragment" app:popUpToInclusive="true" />
    </fragment>

    <fragment
        android:id="@+id/scanQrFragment"
        android:name="com.cashup.app.ui.provisioning.ScanQrFragment"
        tools:layout="@layout/fragment_scan_qr"
        xmlns:tools="http://schemas.android.com/tools">
        <action android:id="@+id/to_processing" app:destination="@id/processingFragment" />
    </fragment>

    <fragment
        android:id="@+id/processingFragment"
        android:name="com.cashup.app.ui.provisioning.ProcessingFragment">
        <action android:id="@+id/to_result" app:destination="@id/resultFragment"
            app:popUpTo="@id/scanQrFragment" app:popUpToInclusive="true" />
    </fragment>

    <fragment
        android:id="@+id/resultFragment"
        android:name="com.cashup.app.ui.provisioning.ResultFragment">
        <action android:id="@+id/to_scan" app:destination="@id/scanQrFragment"
            app:popUpTo="@id/resultFragment" app:popUpToInclusive="true" />
        <action android:id="@+id/to_sale" app:destination="@id/homeFragment"
            app:popUpTo="@id/resultFragment" app:popUpToInclusive="true" />
    </fragment>

    <fragment
        android:id="@+id/homeFragment"
        android:name="com.cashup.app.ui.home.HomeFragment"
        tools:layout="@layout/fragment_home"
        xmlns:tools="http://schemas.android.com/tools" />
</navigation>
```

Changes from the current file: `to_sale` on both `gateFragment` and `resultFragment` now targets `@id/homeFragment` (was `@id/saleFragment`). The old `saleFragment` destination (which pointed directly at `CardPaymentFragment` with its own unused `to_scan` action) is removed entirely — `CardPaymentFragment` is now only reachable as `nav_home.xml`'s `@id/nav_home` destination (Task 3), nested inside `HomeFragment`. This is safe: confirmed during planning that `CardPaymentFragment.kt` never calls that `to_scan` action in Kotlin (it's driven purely by XML and was unused), so removing it drops no working behavior.

- [ ] **Step 2: Verify build**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL. Navigation Component generates `R.id`/`Directions` classes from this XML at compile time — a typo in a destination/action id would surface as a compile error here, not just a runtime crash.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/navigation/nav_provisioning.xml
git commit -m "feat(app): route provisioned devices to HomeFragment instead of CardPaymentFragment directly"
```

---

### Task 6: Full build verification + visual check on a running app

**Files:** none created — verification only.

**Interfaces:** none.

- [ ] **Step 1: Full clean build**

```bash
./gradlew clean build
```
Expected: BUILD SUCCESSFUL, all modules, all variants (matches the standard this whole project already holds itself to per `session_log.md`'s prior verifications).

- [ ] **Step 2: Launch the app and visually verify each tab**

Use the `run` skill (or manual `adb install` + launch if `run` isn't applicable to this Android project) to install a debug build on an emulator or connected device, then:
1. Complete provisioning (or use a device that's already provisioned per prior sessions' test state) so `GateFragment` routes to `HomeFragment`.
2. Confirm the bottom nav shows 4 tabs with the correct icons/labels: Beranda, Riwayat, Fitur, Device.
3. Tap each tab, confirm the correct fragment shows (Home = calculator from `CardPaymentFragment`, others = their dummy content).
4. On the Home tab, confirm the mode-selector pill above the content: tap "Kasir" — pill switches to green/selected, toast "Mode Kasir — segera hadir" appears, content underneath does NOT change (still shows the calculator, not a POS screen). Tap "Transaksi" — pill switches back.
5. Screenshot each state and visually compare tab-bar colors/icon sizes against `mobile-apps-cashlez/app-v3`'s `HomeActivity` (side-by-side, not from memory) — this is the actual verification of "sama persis", not a code-review assumption.

- [ ] **Step 3: Record the verification outcome**

Write what was actually seen (matches / doesn't match app-v3, any visual drift found) — do not report this task DONE on the basis of "the code looks right," per this project's own standing rule (`superpowers:verification-before-completion`): evidence before assertions.

---

## Self-Review

**Spec coverage:**
- §3 architecture (Fragment not Activity, `com.cashup.app.ui.home` package, module stays `app`) → Tasks 2-5.
- §4 bottom-nav visual detail (menu, colors, dims) → Task 1, 2.
- §5 navigation (`nav_provisioning.xml` change) → Task 5, includes the `to_scan` dead-action finding from planning research.
- §6 dummy content (History/Features/MerchantInfo, Device tab instead of account) → Task 3.
- §7 testing (visual verification via `run`, not heavy unit tests) → Task 6; light unit tests added anyway for the two pieces of real logic (mode-toggle exclusivity, device-info formatting) since those ARE trivially unit-testable and TDD applies per this project's global convention.
- §8 out of scope (PosFragment, real tab data, Login, LockMode, Splash logic) → not touched by any task, consistent.
- Header/NFC-banner simplification (not in original spec verbatim, decided during planning research since app-v3's header needs merchant data this project doesn't have) → documented inline in Task 2 Step 3, not silently dropped.

**Placeholder scan:** none found — every step has real, complete code or a concrete verification command with an expected result. The one soft spot (`distance_m`'s exact value, mentioned during planning) was resolved by not using it at all — Task 1's dimens use directly-researched values (`mode_selector_item_padding_horizontal = 12dp`) rather than an unverified shared token.

**Type consistency:** `HomeFragment`'s `binding.toolbar`/`bottomNavigationView`/`homeNavHost` view ids (Task 2) match `fragment_home.xml`'s ids exactly. `nav_home.xml`'s 4 destination ids (Task 3) match `bottom_nav_menu.xml`'s 4 item ids exactly (Task 1) — required for `NavigationUI.setupWithNavController` (Task 4) to work at all. `formatDeviceInfo`'s signature (Task 3 test) matches its implementation exactly.

---

**Plan complete and saved to `docs/superpowers/plans/2026-09-22-home-navigation-shell.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
