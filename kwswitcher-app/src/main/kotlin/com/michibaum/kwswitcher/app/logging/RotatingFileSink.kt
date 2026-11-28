package com.michibaum.kwswitcher.app.logging

import java.io.BufferedWriter
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Size-capped UTF-8 file sink rotating `<name>.log` to `<name>.log.1` (`2 x 1 MB` default) (`DX-5`).
 * Keeps a persistent [BufferedWriter] open to avoid per-line NTFS handle churn and closes cleanly via [AutoCloseable].
 * Uses only `java.base` NIO APIs so `jpackage` preserves `--add-modules java.base,java.desktop`.
 */
class RotatingFileSink(val logFile: Path, private val maxBytes: Long = DEFAULT_MAX_FILE_BYTES) : AutoCloseable {
    private val lock = Any()
    val backupFile: Path = logFile.resolveSibling("${logFile.fileName}.1")
    private val effectiveMaxBytes: Long = maxBytes.coerceAtLeast(1L)
    private var writer: BufferedWriter? = null
    private var currentBytes: Long = 0L
    private var closed: Boolean = false

    fun writeLine(line: String) {
        val textWithNewline = line + System.lineSeparator()
        val incomingBytes = textWithNewline.toByteArray(StandardCharsets.UTF_8).size.toLong()
        synchronized(lock) {
            if (closed) {
                return
            }
            try {
                rotateIfNeededLocked(incomingBytes = incomingBytes)
                val activeWriter = ensureWriterLocked()
                activeWriter.write(textWithNewline)
                activeWriter.flush()
                currentBytes += incomingBytes
            } catch (_: IOException) {
                closeWriterQuietlyLocked()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            closeWriterQuietlyLocked()
        }
    }

    private fun ensureWriterLocked(): BufferedWriter {
        writer?.let { return it }
        logFile.parent?.let { Files.createDirectories(it) }
        currentBytes = if (Files.exists(logFile)) Files.size(logFile) else 0L
        val opened = Files.newBufferedWriter(
            logFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
        writer = opened
        return opened
    }

    private fun rotateIfNeededLocked(incomingBytes: Long) {
        val existingSize = when {
            writer != null -> currentBytes
            Files.exists(logFile) -> Files.size(logFile).also { currentBytes = it }
            else -> 0L
        }
        if (existingSize == 0L || existingSize + incomingBytes <= effectiveMaxBytes) {
            return
        }
        closeWriterQuietlyLocked()
        if (Files.exists(logFile)) {
            Files.move(logFile, backupFile, StandardCopyOption.REPLACE_EXISTING)
        }
        currentBytes = 0L
    }

    private fun closeWriterQuietlyLocked() {
        val existing = writer ?: return
        writer = null
        try {
            existing.close()
        } catch (_: IOException) {
            // Ignore close errors during teardown or rotation recovery
        }
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES: Long = 1024L * 1024L
    }
}
