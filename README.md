# Party Buttons

Virtuelle Buzzer für Quizspiele und Partyspiele. Ein Android-Handy ist der **Host**: Es zeigt einen QR-Code,
alle anderen scannen ihn und bekommen im Browser einen großen Knopf. Wer zuerst drückt, sieht der Host sofort,
mit Reihenfolge und Zeitabstand.

- Keine App auf den Handys der Mitspielenden nötig, nur ein Browser (Android und iPhone).
- Kein Internet nötig: Host und Mitspielende müssen nur im selben WLAN sein (oder am Hotspot des Hosts).
- Kein Server, keine Anmeldung: Das Spiel läuft komplett auf dem Host-Handy.

**Installieren:** [neuestes APK](https://github.com/aikitori/party-buttons/releases/latest/download/party-buttons.apk)
aufs Host-Handy laden und öffnen (einmalig „Installation aus unbekannten Quellen“ erlauben).

Stand: alle Meilensteine aus [PLAN.md](PLAN.md) umgesetzt.

## Entwickeln

| Teil | Ordner | Bauen / testen |
|---|---|---|
| Spiel-Logik (Kotlin/JVM, ohne Android) | `core/` | `./gradlew :core:test` |
| Webserver (Ktor, JVM) | `server/` | `./gradlew :server:test` |
| Host-App (Kotlin, Compose) | `app/` | `./gradlew :app:assembleDebug` (braucht das Android-SDK) |
| Seite der Mitspielenden (TypeScript, Vite) | `web/` | `cd web && npm ci && npm run build`, landet in `app/src/main/assets/web/` |

Ohne Android-SDK (z. B. in claude-box) überspringt Gradle das Modul `app`; `core`, `server` und die Tests laufen trotzdem.

Ausprobieren ohne Handy: erst `cd web && npm run build`, dann `./gradlew :server:run`. Der Server druckt die
Adresse (`http://localhost:8080/j/<Code>`) und jede Änderung von Spielern und Runde. Steuern per Eingabe:
`o` Runde starten, `l` sperren, `f` Sperre nach dem ersten Druck an/aus, `m` Buzzer/Antworten,
`n4` vier Knöpfe, `rB` auflösen mit B richtig, `r-` ohne richtige Antwort.
Die CI (`.github/workflows/android.yml`) baut bei jedem Push die Seite, führt die Tests aus und legt ein
Debug-APK als Artefakt ab.

Texte gibt es auf Englisch und Deutsch: `app/src/main/res/values*/strings.xml` und `web/src/i18n/*.json`.
`TranslationsTest` prüft, dass in beiden Sprachen dieselben Schlüssel stehen.

## Release

Jeder Push auf `main` baut ein signiertes APK und legt es als GitHub-Release `v0.<Laufnummer>` ab. Alle APKs
sind mit demselben Schlüssel signiert, deshalb lassen sich Updates über die installierte App spielen. Der
Schlüssel liegt als Repo-Secret vor (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`) und wurde einmalig mit `bash scripts/keystore-einrichten.sh` erzeugt. Das Original
liegt außerhalb des Repos und muss sicher aufgehoben werden; geht es verloren, muss die App einmal neu
installiert werden.

Debug-APKs aus anderen Branches haben einen anderen Schlüssel: Vor dem ersten Release-APK die Debug-Version
deinstallieren.
