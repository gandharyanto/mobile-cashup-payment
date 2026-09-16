package com.cashup.devicesdk.fake

import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial

/**
 * [vendorSlotPurpose] meniru batas nyata di spec §4.3: hanya satu purpose yang
 * bisa masuk modul aman vendor, sisanya jatuh ke vault.
 * [failOnPurpose] memicu kegagalan pemasangan untuk menguji jalur rollback.
 */
class FakeTerminalKeyInstaller(
    private val vendorSlotPurpose: String? = "PIN",
    private val failOnPurpose: String? = null,
) : TerminalKeyInstaller {

    var wipeCount: Int = 0
        private set

    private val installed = mutableListOf<String>()
    val installedPurposes: List<String> get() = installed.toList()

    override suspend fun install(materials: List<TerminalKeyMaterial>): TerminalKeyInstallResult {
        val outcomes = mutableListOf<KeyInstallOutcome>()
        for (material in materials) {
            if (material.purpose == failOnPurpose) {
                return TerminalKeyInstallResult.Failed(material.purpose, "fake failure")
            }
            installed += material.purpose
            outcomes += KeyInstallOutcome(
                purpose = material.purpose,
                backing = if (material.purpose == vendorSlotPurpose) {
                    KeyBacking.VENDOR_SECURE_MODULE
                } else {
                    KeyBacking.TEE_VAULT_ONLY
                },
            )
        }
        return TerminalKeyInstallResult.Installed(outcomes)
    }

    override suspend fun wipe() {
        wipeCount++
        installed.clear()
    }
}