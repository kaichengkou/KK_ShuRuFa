package com.kk.shurufa.rime

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The bundled Rime data (雾凇 and KK's own files) installed into the shared directory. The build
 * writes a manifest of every file with a hash of its content; an update copies only the files
 * whose hash differs and removes the ones no longer bundled, so the rest keep their dates and Rime
 * has nothing to rebuild for them. With the same data, starting after an update takes as long as
 * any other start.
 */
object RimeData {
    const val MANIFEST = "kk_manifest.txt"
    /** The manifest of what is installed, kept next to it. */
    private const val INSTALLED = ".kk_manifest"

    /** path → hash */
    fun parse(text: String): Map<String, String> = text.lineSequence().mapNotNull { l ->
        val t = l.indexOf('\t'); if (t <= 0) null else l.substring(t + 1) to l.substring(0, t)
    }.toMap()

    fun format(m: Map<String, String>) = m.entries.sortedBy { it.key }.joinToString("") { "${it.value}\t${it.key}\n" }

    fun hash(f: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Brings [shared] to [bundled] (path → hash), reading a file through [open]. Returns whether
     * anything changed. Without a record of what is installed (first start, or an older version
     * that copied everything each time) the files there are hashed once to find out.
     */
    fun sync(shared: File, bundled: Map<String, String>, open: (String) -> InputStream): Boolean {
        shared.mkdirs()
        val record = File(shared, INSTALLED)
        val installed = if (record.isFile) parse(record.readText()) else
            bundled.keys.mapNotNull { p -> File(shared, p).takeIf { it.isFile }?.let { p to hash(it) } }.toMap()
        var changed = false
        for ((path, h) in bundled) {
            val dest = File(shared, path)
            if (installed[path] == h && dest.isFile) continue
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            open(path).use { i -> tmp.outputStream().use { i.copyTo(it) } }
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
            changed = true
        }
        // what an older version left behind or a newer one no longer bundles
        for (path in installed.keys - bundled.keys) if (File(shared, path).delete()) changed = true
        File(shared, ".kk_version").delete()
        if (changed || !record.isFile) record.writeText(format(bundled))
        return changed
    }
}
