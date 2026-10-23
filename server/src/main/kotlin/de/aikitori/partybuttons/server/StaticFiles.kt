package de.aikitori.partybuttons.server

import java.io.File

/** Woher die gebaute Seite kommt: in der App aus den Assets, auf dem PC aus einem Ordner. */
fun interface StaticFiles {
    /** Inhalt der Datei [path] relativ zur Wurzel der Seite (z. B. "index.html", "assets/index-x.js"), sonst null. */
    fun read(path: String): ByteArray?
}

class DirectoryStaticFiles(private val root: File) : StaticFiles {
    private val canonicalRoot = root.canonicalFile

    override fun read(path: String): ByteArray? {
        val file = File(canonicalRoot, path).canonicalFile
        // kein Ausbruch aus dem Ordner per ../
        if (!file.path.startsWith(canonicalRoot.path + File.separator) || !file.isFile) return null
        return file.readBytes()
    }
}
