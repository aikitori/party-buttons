# Party Buttons

Virtuelle Buzzer für Quizspiele und Partyspiele. Ein Android-Handy ist der **Host**: Es zeigt einen QR-Code,
alle anderen scannen ihn und bekommen im Browser einen großen Knopf. Wer zuerst drückt, sieht der Host sofort,
mit Reihenfolge und Zeitabstand.

- Keine App auf den Handys der Mitspielenden nötig, nur ein Browser (Android und iPhone).
- Kein Internet nötig: Host und Mitspielende müssen nur im selben WLAN sein (oder am Hotspot des Hosts).
- Kein Server, keine Anmeldung: Das Spiel läuft komplett auf dem Host-Handy.

Stand: Projektgerüst (M0), Plan in [PLAN.md](PLAN.md).

## Entwickeln

| Teil | Ordner | Bauen / testen |
|---|---|---|
| Spiel-Logik (Kotlin/JVM, ohne Android) | `core/` | `./gradlew :core:test` |
| Host-App (Kotlin, Compose) | `app/` | `./gradlew :app:assembleDebug` (braucht das Android-SDK) |
| Seite der Mitspielenden (TypeScript, Vite) | `web/` | `cd web && npm ci && npm run build`, landet in `app/src/main/assets/web/` |

Ohne Android-SDK (z. B. in claude-box) überspringt Gradle das Modul `app`; `core` und die Tests laufen trotzdem.
Die CI (`.github/workflows/android.yml`) baut bei jedem Push die Seite, führt die Tests aus und legt ein
Debug-APK als Artefakt ab.

Texte gibt es auf Englisch und Deutsch: `app/src/main/res/values*/strings.xml` und `web/src/i18n/*.json`.
`TranslationsTest` prüft, dass in beiden Sprachen dieselben Schlüssel stehen.
