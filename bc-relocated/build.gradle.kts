// BouncyCastle dipindah ke package com.cashup.shaded.bouncycastle.
//
// Android menyertakan org.bouncycastle.* versi terpotong di framework.jar
// (boot classpath). Classloader aplikasi parent-first, jadi kelas bawaan itu
// MENANG atas bcprov yang kita bundel: BouncyCastleProvider gagal dimuat dengan
// NoSuchMethodError di org.bouncycastle.util.Strings. Aplikasi lama menghindarinya
// lewat R8; di sini R8 mati, jadi nama package-nya dipindah supaya tidak
// bertabrakan.
plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

val bcVersion = "1.78.1"

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:$bcVersion")
    implementation("org.bouncycastle:bcpkix-jdk18on:$bcVersion")
    implementation("org.bouncycastle:bcutil-jdk18on:$bcVersion")
}

tasks.shadowJar {
    archiveClassifier.set("relocated")
    relocate("org.bouncycastle", "com.cashup.shaded.bouncycastle")
    // Android tidak memakai varian multi-release maupun module-info, dan tanda
    // tangan jar asli menjadi tidak valid setelah relokasi.
    exclude("META-INF/versions/**", "module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Konsumen (modul Android) memilih configuration ini secara eksplisit; tidak
// bergantung pada pencocokan atribut Gradle/AGP.
val relocatedElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(tasks.shadowJar)
}
