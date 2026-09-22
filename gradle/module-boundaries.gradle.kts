// Menegakkan aturan dependency module dari spec 2026-09-22 (§3).
//
// Diperiksa lewat build, bukan review, karena setiap pelanggaran tidak
// terlihat di diff: menambah `implementation(project(":device-sdk-mpos"))`
// ke device-sdk-edcsdk terlihat wajar sendirian, cuma keseluruhan graph yang
// menunjukkan itu salah.
//
// Run: ./gradlew checkModuleBoundaries

val allowedProjectDeps: Map<String, Set<String>> = mapOf(
    // Kontrak murni -- tanpa dependency internal apa pun.
    ":common-core" to emptySet(),
    ":device-sdk-api" to emptySet(),
    ":signing-core" to emptySet(),

    // Adapter vendor -- masing-masing lihat SATU port (device-sdk-api).
    ":device-sdk-edcsdk" to setOf(":device-sdk-api"),
    ":device-sdk-mpos" to setOf(":device-sdk-api"),

    // Satu-satunya titik fan-out. SATU-SATUNYA module yang boleh lihat kedua
    // adapter vendor sekaligus.
    ":device-sdk-factory" to setOf(":device-sdk-api", ":device-sdk-edcsdk", ":device-sdk-mpos"),

    ":provisioning-core" to setOf(":common-core", ":device-sdk-api", ":signing-core"),
    ":cdcp-core" to setOf(":common-core", ":device-sdk-api", ":signing-core"),
    ":feature-card-payment" to setOf(":common-core", ":device-sdk-api", ":cdcp-core"),

    // Shell -- wiring saja. device-sdk-edcsdk tetap ada di sini untuk
    // TerminalKeyInstaller/DukptKeyProvider/Scanner/SerialNumberProvider, yang
    // TIDAK lewat device-sdk-factory (spec §5/§8 -- di luar scope factory ini).
    ":app" to setOf(
        ":provisioning-core", ":cdcp-core", ":device-sdk-edcsdk", ":device-sdk-factory",
        ":feature-card-payment",
    ),
)

// Vendor SDK tidak boleh bocor lewat batas modulnya: dependency AAR vendor
// selalu implementation, tidak pernah api.
val noApiScope: List<String> = listOf(":device-sdk-edcsdk", ":device-sdk-mpos")

// Hanya periksa configuration yang benar-benar ditulis manusia di
// `dependencies {}`. Classpath hasil resolusi (…CompileClasspath,
// …RuntimeClasspath) turunan, bukan deklarasi.
val declarableSuffixes = listOf("implementation", "api", "compileOnly", "runtimeOnly")

fun isDeclarable(name: String): Boolean = declarableSuffixes.any { suffix ->
    name == suffix || name.endsWith(suffix.replaceFirstChar { it.uppercaseChar() })
}

// Boundary verification is part of the normal verification lifecycle.
gradle.projectsEvaluated {
    rootProject.subprojects.forEach { project ->
        project.tasks.matching { it.name == "check" }.configureEach {
            dependsOn(rootProject.tasks.named("checkModuleBoundaries"))
        }
    }
}

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Fails if any module depends on something the design forbids (spec 2026-09-22 §3)."
    doLast {
        val violations = mutableListOf<String>()

        rootProject.subprojects.forEach { p ->
            val allowed = allowedProjectDeps[p.path]
            if (allowed == null) {
                violations += "${p.path}: not listed in module-boundaries. Add it deliberately, with its allowed dependencies."
                return@forEach
            }

            p.configurations.filter { isDeclarable(it.name) }.forEach { cfg ->
                cfg.dependencies.forEach { d ->
                    if (d is org.gradle.api.artifacts.ProjectDependency) {
                        val target = d.dependencyProject.path
                        if (target != p.path && target !in allowed) {
                            violations += "${p.path} --> $target  (via '${cfg.name}') is not an allowed dependency"
                        }
                    }
                }
            }

            if (p.path in noApiScope) {
                val api = p.configurations.findByName("api")
                // Project dependency pada device-sdk-api itu SAH dan wajib --
                // EdcSdkCardReader/MposCardReader publicly implement
                // CardReader/PairableCardReader dari device-sdk-api, jadi
                // konsumen butuh tipe itu di compile classpath mereka.
                // Yang dilarang cuma AAR vendor mentah (bukan ProjectDependency)
                // yang bocor lewat api -- itu satu-satunya yang boleh gagal di sini.
                val leaked = api?.dependencies
                    ?.filterNot { it.group == "org.jetbrains.kotlin" }
                    ?.filterNot { it is org.gradle.api.artifacts.ProjectDependency }
                    ?.map { "${it.group ?: ""}:${it.name}" }
                    .orEmpty()
                if (leaked.isNotEmpty()) {
                    violations += "${p.path}: uses 'api' scope for ${leaked.joinToString()}. Vendor AAR dependencies must stay implementation-scoped so they cannot leak to consumers."
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Module boundary violations (spec 2026-09-22 §3):\n  - " + violations.joinToString("\n  - ")
            )
        }
        logger.lifecycle("Module boundaries OK: ${rootProject.subprojects.size} modules checked.")
    }
}
