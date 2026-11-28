package com.michibaum.kwswitcher.app.logging

import com.michibaum.kwswitcher.app.cli.LogLevel
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.LegacyAbstractLogger
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Central runtime configuration and sink coordinator for [KwSlf4jProvider] (`DX-5`).
 */
object KwLoggingHub {

    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    @Volatile
    var minLevel: LogLevel = LogLevel.INFO
        private set

    @Volatile
    var consoleEnabled: Boolean = true
        private set

    @Volatile
    var fileSink: RotatingFileSink? = null
        private set

    @Volatile
    var ringBuffer: DiagnosticsRingBuffer = DiagnosticsRingBuffer()
        private set

    val logDirectory: Path?
        get() = fileSink?.logFile?.parent

    fun configure(
        minLevel: LogLevel = LogLevel.INFO,
        logFile: Path? = null,
        consoleEnabled: Boolean = true,
        maxFileBytes: Long = RotatingFileSink.DEFAULT_MAX_FILE_BYTES,
        ringCapacity: Int = DiagnosticsRingBuffer.DEFAULT_CAPACITY,
    ) {
        this.minLevel = minLevel
        this.consoleEnabled = consoleEnabled
        this.ringBuffer = DiagnosticsRingBuffer(maxEntries = ringCapacity)
        this.fileSink = logFile?.let { RotatingFileSink(logFile = it, maxBytes = maxFileBytes) }
    }

    internal fun isEnabled(level: LogLevel): Boolean = level.severity >= minLevel.severity

    internal fun publish(level: LogLevel, loggerName: String, message: String, throwable: Throwable?) {
        if (!isEnabled(level)) {
            return
        }
        val shortLogger = loggerName.substringAfterLast('.')
        val timestamp = timestampFormatter.format(Instant.now())
        val threadName = Thread.currentThread().name
        val baseLine = "$timestamp [${level.name.padEnd(LEVEL_PAD_WIDTH)}] [$threadName] $shortLogger - $message"
        val fullEntry = if (throwable != null) {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "$baseLine${System.lineSeparator()}${sw.toString().trimEnd()}"
        } else {
            baseLine
        }

        ringBuffer.append(fullEntry)
        fileSink?.writeLine(fullEntry)
        if (consoleEnabled) {
            val stream = if (level.severity >= LogLevel.WARN.severity) System.err else System.out
            stream.println(fullEntry)
        }
    }

    private const val LEVEL_PAD_WIDTH = 5
}

/**
 * Lightweight `java.base`-only [SLF4JServiceProvider] discovered via `ServiceLoader` (`DX-5`).
 */
class KwSlf4jProvider : SLF4JServiceProvider {

    private val loggerFactory: ILoggerFactory = KwLoggerFactory()
    private val markerFactory: IMarkerFactory = BasicMarkerFactory()
    private val mdcAdapter: MDCAdapter = NOPMDCAdapter()

    override fun getLoggerFactory(): ILoggerFactory = loggerFactory

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    override fun getRequestedApiVersion(): String = REQUESTED_SLF4J_API_VERSION

    override fun initialize() = Unit

    companion object {
        const val REQUESTED_SLF4J_API_VERSION: String = "2.0.99"
    }
}

private class KwLoggerFactory : ILoggerFactory {
    private val loggers = ConcurrentHashMap<String, Logger>()

    override fun getLogger(name: String): Logger = loggers.computeIfAbsent(name, ::KwLogger)
}

private class KwLogger(loggerName: String) : LegacyAbstractLogger() {

    init {
        this.name = loggerName
    }

    override fun isTraceEnabled(): Boolean = KwLoggingHub.isEnabled(LogLevel.TRACE)

    override fun isDebugEnabled(): Boolean = KwLoggingHub.isEnabled(LogLevel.DEBUG)

    override fun isInfoEnabled(): Boolean = KwLoggingHub.isEnabled(LogLevel.INFO)

    override fun isWarnEnabled(): Boolean = KwLoggingHub.isEnabled(LogLevel.WARN)

    override fun isErrorEnabled(): Boolean = KwLoggingHub.isEnabled(LogLevel.ERROR)

    override fun getFullyQualifiedCallerName(): String? = null

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any?>?,
        throwable: Throwable?,
    ) {
        val mappedLevel = when (level) {
            Level.TRACE -> LogLevel.TRACE
            Level.DEBUG -> LogLevel.DEBUG
            Level.INFO -> LogLevel.INFO
            Level.WARN -> LogLevel.WARN
            Level.ERROR -> LogLevel.ERROR
        }
        val formatted = MessageFormatter.basicArrayFormat(messagePattern, arguments) ?: ""
        KwLoggingHub.publish(
            level = mappedLevel,
            loggerName = name,
            message = formatted,
            throwable = throwable,
        )
    }
}
