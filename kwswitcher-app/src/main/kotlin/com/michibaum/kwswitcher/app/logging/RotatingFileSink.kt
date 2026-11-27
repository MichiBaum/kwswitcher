package com.michibaum.kwswitcher.app.logging

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Size-capped UTF-8 file sink rotating `<name>.log` to `<name>.log.1` (`2 x 1 MB` default) (`DX-5`).
 * Uses only `java.base` NIO APIs so `jpackage` preserves `--add-modules java.base,java.desktop`.
 */
class RotatingFileSink(val logFile: Path, private val maxBytes: Long = DEFAULT_MAX_FILE_BYTES) {
    private val lock = Any()
    val backupFile: Path = logFile.resolveSibling("${logFile.fileName}.1")

    fun writeLine(line: String) {
        val payload = (line + System.lineSeparator()).toByteArray(StandardCharsets.UTF_8)
        synchronized(lock) {
            try {
                logFile.parent?.let { Files.createDirectories(it) }
                rotateIfNeeded(incomingBytes = payload.size.toLong())
                Files.write(
                    logFile,
                    payload,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
            } catch (_: IOException) {
                // Never crash the application if the log directory becomes unwritable
            }
        }
    }

    private fun rotateIfNeeded(incomingBytes: Long) {
        if (!Files.exists(logFile)) {
            return
        }
        val currentSize = Files.size(logFile)
        if (currentSize + incomingBytes <= maxBytes) {
            return
        }
        Files.move(logFile, backupFile, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 1024L * 1024L
    }
}
