plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.retrofit2:retrofit:2.11.0")
    api("com.squareup.retrofit2:converter-gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
