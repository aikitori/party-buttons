# Umsetzungsplan

## Idee in einem Satz

Die Android-App startet einen kleinen Webserver im WLAN, zeigt dessen Adresse als QR-Code, und die Mitspielenden
öffnen darüber im Browser eine Seite mit einem Buzzer, der per WebSocket an den Host meldet.

```
 Handy Mitspieler (Browser)            Host-Handy (Android-App)
 ┌───────────────────────┐            ┌─────────────────────────────────┐
 │ http://192.168.1.23:  │  HTTP      │ Webserver (Ktor)                │
 │   8080/j/K7QX         │ ─────────► │  liefert Client-Seite aus       │
 │                       │            │                                 │
 │   [  BUZZER  ]        │  WebSocket │ Spiel-Logik                     │
 │                       │ ◄────────► │  Spieler, Drücke, Reihenfolge   │
 └───────────────────────┘            │                                 │
                                      │ Host-Oberfläche (Compose)       │
                                      │  QR-Code, Spielerliste, Reset   │
                                      └─────────────────────────────────┘
```

## Technik

| Teil | Wahl | Warum |
|---|---|---|
| Host-App | Kotlin, Jetpack Compose | Ein Webserver im Hintergrund braucht ohnehin nativen Code; mit Capacitor wäre das ein eigenes Plugin. |
| Server | Ktor (CIO-Engine) mit WebSockets | läuft in der App, reines Kotlin, auch auf dem PC startbar (für Tests). |
| Spiel-Logik | eigenes Gradle-Modul `core`, reines Kotlin/JVM | ohne Android testbar, auch im Container und in der CI. |
| QR-Code | ZXing `core` | nur Erzeugung, kein Kamerazugriff nötig. |
| Client-Seite | ein HTML-Dokument mit TypeScript, gebaut mit Vite, als Asset in die App gepackt | klein, kein Framework nötig. |
| Build | GitHub Actions wie bei hinterhof-abenteuer: Debug-APK bei PRs, signiertes APK als Release auf `main` | im Container gibt es kein Android-SDK. |

## Ablauf eines Spiels

1. Host öffnet die App, tippt „Neues Spiel“. Die App startet den Server und einen Vordergrund-Dienst, damit er
   bei ausgeschaltetem Bildschirm weiterläuft.
2. Die App ermittelt ihre WLAN-Adresse (auch im Hotspot-Betrieb) und zeigt einen QR-Code mit
   `http://<IP>:<Port>/j/<Raumcode>`. Der Raumcode verhindert, dass Fremde im WLAN zufällig mitspielen.
3. Mitspielende scannen, geben einen Namen ein, bekommen eine Farbe und sehen den Buzzer.
4. Drückt jemand, schickt der Browser sofort eine Nachricht. Der Host sortiert die Drücke, zeigt Platz 1 groß
   (mit Ton) und die weiteren mit Abstand in Millisekunden.
5. Nach dem ersten Druck sind alle Buzzer gesperrt (einstellbar). Der Host gibt mit „Freigeben“ die nächste
   Runde frei.

## Fairness

Die Reihenfolge bestimmt der Zeitpunkt, an dem der Host die Nachricht empfängt, abzüglich der gemessenen
Laufzeit pro Gerät. Jeder Client misst laufend per Ping die Hin- und Rückzeit; der Host zieht die halbe
Laufzeit (gleitender Mittelwert) ab. Im WLAN liegen die Unterschiede meist bei wenigen Millisekunden. Haben
zwei Drücke weniger als ca. 20 ms Abstand, zeigt der Host „gleichzeitig“ statt eines klaren Siegers.

## Protokoll (WebSocket, JSON)

Client → Host: `join {token, name}`, `press {seq}`, `pong {t}`
Host → Client: `welcome {id, name, color, state}`, `state {locked, myRank}`, `ping {t}`, `kicked`, `replaced`

## Wiederverbinden: gleicher Spieler nach Abbruch

Pflicht ab M1, nicht erst Feinschliff: Wer die Verbindung verliert (WLAN weg, Bildschirm aus, Seite neu geladen,
Browser geschlossen), muss beim Wiederkommen derselbe Spieler sein, mit Name, Farbe und Punkten.

- **Spieler-Token im Browser:** Beim ersten Besuch erzeugt die Seite ein zufälliges Token und speichert es im
  `localStorage`, zusätzlich als Cookie (Ersatz, falls `localStorage` gelöscht oder gesperrt ist; Cookies gelten
  unabhängig vom Port). Erzeugt mit `crypto.getRandomValues`, denn `crypto.randomUUID` gibt es ohne HTTPS nicht.
- **Anmelden mit Token:** `join {token, name}`. Kennt der Host das Token, bekommt der Browser den alten Spieler
  zurück und überspringt die Namenseingabe. Unbekanntes Token: neuer Spieler.
- **Host vergisst niemanden:** Getrennte Spieler bleiben in der Liste, ausgegraut mit „getrennt“, bis der Host sie
  entfernt. Drücke während der Trennung gibt es nicht, der Buzzer zeigt „Verbinde neu …“.
- **Automatisch neu verbinden:** Der Client versucht es nach Abbruch sofort, dann mit wachsendem Abstand
  (bis 5 s), und sofort wieder, wenn die Seite sichtbar wird (`visibilitychange`) oder das Netz zurück ist (`online`).
- **Doppelt offen:** Meldet sich dasselbe Token ein zweites Mal (zweiter Tab), gewinnt die neue Verbindung, die
  alte bekommt `replaced` und zeigt einen Hinweis.
- **Token verloren** (privater Modus, Browser-Daten gelöscht, anderes Gerät): Der Host kann einen neuen Spieler
  per „Übernehmen“ mit einem getrennten zusammenlegen, Punkte und Farbe gehen mit.
- **Gleiche Adresse:** Der `localStorage` hängt an `http://IP:Port`. Der Port ist deshalb fest (8080, nur bei
  Belegung ein anderer), und die IP bleibt während eines Spiels gleich. Ändert sich die IP doch, hilft das Cookie.
- **Tests in `core`:** Abbruch und Rückkehr mit Token, zwei Verbindungen mit demselben Token, Übernehmen.

## Sprache: Deutsch und Englisch, umschaltbar

- **Host-App:** Texte als Android-Ressourcen (`values/strings.xml` Englisch, `values-de/strings.xml` Deutsch).
  Startet in der Systemsprache, umschaltbar in den Einstellungen der App über
  `AppCompatDelegate.setApplicationLocales` (merkt sich Android selbst, ab Android 13 auch in den
  System-Einstellungen unter „App-Sprache“).
- **Client-Seite:** Texte in zwei Wörterbüchern (`en.json`, `de.json`) im Client. Startet in der Sprache des
  Browsers (`navigator.language`), Umschalter DE/EN oben auf der Seite, Wahl im `localStorage`. Jedes Gerät
  wählt selbst; der Host gibt nichts vor.
- **Keine Texte im Protokoll:** Der Host schickt nur Zustände und Codes (`locked`, `replaced`, `rank: 2`), die
  Seite übersetzt selbst. Spielernamen bleiben, wie sie eingegeben wurden.
- **Prüfung:** Ein Test vergleicht die Schlüssel beider Wörterbücher bzw. beider `strings.xml`, damit keine
  Übersetzung fehlt.

## Meilensteine

| # | Inhalt | Ergebnis |
|---|---|---|
| M0 | Gradle-Projekt (`app`, `core`), Vite-Client, CI baut Debug-APK, Gerüst für Deutsch/Englisch in App und Client | leere App lässt sich installieren |
| M1 | Ktor-Server in der App, liefert Client-Seite, WebSocket-Verbindung, Spielerliste beim Host, Spieler-Token und Wiederverbinden | Mitspielende tauchen beim Host auf und bleiben nach Abbruch dieselben |
| M2 | Buzzer-Logik in `core` mit Tests: Sperren, Reihenfolge, Freigeben | erstes spielbares Quiz |
| M3 | QR-Code, IP-Ermittlung (WLAN und Hotspot), Vordergrund-Dienst, WifiLock | Alltagstauglich |
| M4 | Laufzeitausgleich, Töne beim Host, Vibration beim Client, „Übernehmen“ beim Host | fair und rund |
| M5 | Release-Signierung, App-Icon, Hinweise bei Problemen (siehe unten) | APK zum Weitergeben |

Spätere Ideen: Teams mit gemeinsamer Farbe, Antwort-Knöpfe A/B/C/D, Punktestand, mehrere Knöpfe pro Gerät,
Sperre für Spieler, die zu früh drücken.

## Bekannte Stolpersteine

- **Gäste-WLAN / Client-Isolation:** Viele Router lassen Geräte im Gäste-WLAN nicht miteinander reden. Abhilfe:
  Host macht einen Hotspot auf. Die App sollte darauf hinweisen, wenn sich niemand verbinden kann.
- **Mobile Daten:** Manche Handys schicken Anfragen an eine lokale Adresse über mobile Daten, wenn das WLAN
  „kein Internet“ hat. Hinweis in der Client-Seite nötig.
- **Akku-Optimierung:** Ohne Vordergrund-Dienst beendet Android den Server nach kurzer Zeit.
- **Nur HTTP:** Im lokalen Netz gibt es kein HTTPS. Vibration geht trotzdem, Bildschirm-wach-halten
  (Wake Lock API) im Browser nicht. Reicht für einen Buzzer.

## Offene Fragen

- Wie viele Mitspielende sollen mindestens gehen (Annahme: 20)?
- Erste Version nur ein Buzzer pro Person, oder gleich Antwort-Knöpfe A/B/C/D?
- Name „Party Buttons“ und Paketname `de.aikitori.partybuttons` in Ordnung?
