package com.tdarby.comet.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-safe small-file persistence: write a temp file, sync it, then swap it in. */
internal object AtomicFiles {

    /** Replace [target] with [text] so a failed or interrupted write never leaves a half file. */
    fun writeText(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Parse [file] with [parse]. A missing file gives [empty]; a file that can't be read or
     * parsed is moved to "<name>.corrupt" (replacing an older one) so the next save can't
     * destroy the only copy, and [empty] is returned.
     */
    fun <T> readOrSetAside(file: File, empty: T, parse: (String) -> T): T {
        if (!file.exists()) return empty
        return try {
            parse(file.readText())
        } catch (_: Exception) {
            try {
                Files.move(
                    file.toPath(), File(file.parentFile, file.name + ".corrupt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
            }
            empty
        }
    }
}
