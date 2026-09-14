package com.cashup.devicesdk

/**
 * The one place that maps a terminal's `Build.MODEL` string to the vendor
 * [DeviceSdk] factory that knows how to drive it. Each vendor adapter module
 * registers itself here; nothing else in the app resolves hardware by model.
 *
 * Two invariants:
 * - **Longest-prefix-wins**: when more than one registered prefix matches a
 *   `buildModel` (e.g. "A920" and "A920Pro" both match "A920Pro"), the
 *   longest (most specific) prefix is used, not insertion order.
 * - **Resolution is memoized**: [resolve] constructs a [DeviceSdk] instance
 *   for a given `buildModel` at most once per process lifetime. Real EDC
 *   vendor SDKs hold exclusive hardware handles with one-shot init
 *   semantics — handing out a fresh instance per call risks resource
 *   conflicts or one caller silently stealing the peripheral handle from
 *   another.
 */
object DeviceSdkRegistry {

    private val factories = mutableMapOf<String, () -> DeviceSdk>()
    private var resolved: Pair<String, DeviceSdk>? = null

    fun register(modelPrefix: String, factory: () -> DeviceSdk) {
        require(modelPrefix !in factories) { "Model prefix '$modelPrefix' is already registered" }
        factories[modelPrefix] = factory
    }

    fun resolve(buildModel: String): DeviceSdk? {
        resolved?.let { (model, sdk) -> if (model == buildModel) return sdk }
        val match = factories.entries
            .filter { buildModel.startsWith(it.key, ignoreCase = true) }
            .maxByOrNull { it.key.length }
            ?.value?.invoke()
            ?: return null
        resolved = buildModel to match
        return match
    }

    /** Test-only: clears registrations and memoized state so tests don't leak into each other. */
    fun clearForTest() {
        factories.clear()
        resolved = null
    }
}
