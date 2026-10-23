// Spiel-Logik ohne Android: Spieler, Runden, Protokoll. Läuft auf der JVM und wird in der App benutzt.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // für TranslationsTest: Wurzel des Repos, dort liegen app/ und web/
    systemProperty("repoRoot", rootDir.absolutePath)
}
