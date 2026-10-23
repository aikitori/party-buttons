package de.aikitori.partybuttons.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Jeder Text muss auf Deutsch und Englisch da sein, in der App und auf der Seite der Mitspielenden. */
class TranslationsTest {
    private val root = File(System.getProperty("repoRoot"))

    @Test
    fun `App hat dieselben Texte auf Englisch und Deutsch`() {
        val en = stringKeys(root.resolve("app/src/main/res/values/strings.xml"))
        val de = stringKeys(root.resolve("app/src/main/res/values-de/strings.xml"))
        assertEquals(en, de, "strings.xml: Schlüssel unterscheiden sich")
    }

    @Test
    fun `Seite hat dieselben Texte auf Englisch und Deutsch`() {
        val en = jsonKeys(root.resolve("web/src/i18n/en.json"))
        val de = jsonKeys(root.resolve("web/src/i18n/de.json"))
        assertEquals(en, de, "en.json und de.json: Schlüssel unterscheiden sich")
    }

    private fun stringKeys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it).attributes.getNamedItem("name").nodeValue }
            .toSortedSet()
    }

    // flache JSON-Datei {"schluessel": "Text", ...}, ohne JSON-Bibliothek gelesen
    private fun jsonKeys(file: File): Set<String> =
        Regex(""""([^"\\]+)"\s*:""").findAll(file.readText()).map { it.groupValues[1] }.toSortedSet()
}
