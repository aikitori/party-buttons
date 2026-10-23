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

Client → Host: `join {name, token}`, `press {seq}`, `pong {t}`
Host → Client: `welcome {id, color, state}`, `state {locked, myRank}`, `ping {t}`, `kicked`

Das `token` liegt im `localStorage` des Browsers. Lädt jemand die Seite neu oder verliert kurz das WLAN,
bekommt er denselben Platz wieder.

## Meilensteine

| # | Inhalt | Ergebnis |
|---|---|---|
| M0 | Gradle-Projekt (`app`, `core`), Vite-Client, CI baut Debug-APK | leere App lässt sich installieren |
| M1 | Ktor-Server in der App, liefert Client-Seite, WebSocket-Verbindung, Spielerliste beim Host | Mitspielende tauchen beim Host auf |
| M2 | Buzzer-Logik in `core` mit Tests: Sperren, Reihenfolge, Freigeben | erstes spielbares Quiz |
| M3 | QR-Code, IP-Ermittlung (WLAN und Hotspot), Vordergrund-Dienst, WifiLock | Alltagstauglich |
| M4 | Laufzeitausgleich, Töne beim Host, Vibration beim Client, Wiederverbinden | fair und rund |
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

- Sprache der Oberfläche: nur Deutsch oder gleich Deutsch und Englisch?
- Wie viele Mitspielende sollen mindestens gehen (Annahme: 20)?
- Erste Version nur ein Buzzer pro Person, oder gleich Antwort-Knöpfe A/B/C/D?
- Name „Party Buttons“ und Paketname `de.aikitori.partybuttons` in Ordnung?
