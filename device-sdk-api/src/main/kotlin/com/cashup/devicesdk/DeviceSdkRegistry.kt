package com.cashup.devicesdk

/**
 * The one place that maps a terminal's `Build.MODEL` string to the vendor
 * [DeviceSdk] factory that knows how to drive it. Each vendor adapter module
 * registers itself here; nothing else in the app resolves hardware by model.
 */
object DeviceSdkRegistry {

    private val factories = mutableMapOf<String, () -> DeviceSdk>()

    fun register(modelPrefix: String, factory: () -> DeviceSdk) {
        factories[modelPrefix] = factory
    }

    fun resolve(buildModel: String): DeviceSdk? {
        val match = factories.entries.firstOrNull { (prefix, _) -> buildModel.startsWith(prefix, ignoreCase = true) }
        return match?.value?.invoke()
    }

    /** Test-only: clears registrations so tests don't leak state into each other. */
    fun clearForTest() {
        factories.clear()
    }
}
