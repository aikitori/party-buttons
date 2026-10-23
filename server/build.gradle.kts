// Webserver im Host-Handy (Ktor): liefert die Seite der Mitspielenden aus und spricht per WebSocket mit ihnen.
// Reines JVM-Modul, damit es auch auf dem PC läuft: ./gradlew :server:run zum Ausprobieren mit dem Browser.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("de.aikitori.partybuttons.server.PartyServerKt")
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.zxing.core)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    // gebaute Seite aus web/ (npm run build), Port 8080
    args(rootDir.resolve("app/src/main/assets/web").absolutePath)
}
