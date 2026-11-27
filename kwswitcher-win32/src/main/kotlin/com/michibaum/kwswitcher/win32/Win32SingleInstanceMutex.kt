package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.port.SingleInstancePort
import com.michibaum.kwswitcher.win32.bindings.MutexNative
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.allocateWideString
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import com.michibaum.kwswitcher.win32.diagnostics.Win32ErrorFormatter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

private val logger = KotlinLogging.logger {}

/**
 * Single-instance guard backed by a Win32 named mutex (`CreateMutexW`) (`FR-9`, `DX-2`).
 */
class Win32SingleInstanceMutex(val mutexName: String = DEFAULT_MUTEX_NAME) : SingleInstancePort {

    private var mutexHandle: MemorySegment = MemorySegment.NULL

    override fun tryAcquire(): Boolean {
        if (mutexHandle != MemorySegment.NULL) {
            return true
        }
        Arena.ofConfined().use { arena ->
            val nameSegment = arena.allocateWideString(mutexName)
            val handle = MutexNative.CreateMutexW.invokeExact(
                MemorySegment.NULL,
                1,
                nameSegment,
            ) as MemorySegment

            if (handle == MemorySegment.NULL) {
                val err = Win32ErrorFormatter.describeLastError("CreateMutexW($mutexName)")
                logger.warn { "Failed to create single-instance mutex '$mutexName': ${err.detail}" }
                return false
            }

            val lastError = MutexNative.GetLastError.invokeExact() as Int
            if (lastError == Win32Constants.ERROR_ALREADY_EXISTS) {
                MutexNative.CloseHandle.invokeExact(handle) as Int
                logger.info { "Another instance already holds mutex '$mutexName'" }
                return false
            }

            mutexHandle = handle
            logger.debug { "Acquired single-instance mutex '$mutexName'" }
            return true
        }
    }

    override fun close() {
        val handle = mutexHandle
        if (handle != MemorySegment.NULL) {
            mutexHandle = MemorySegment.NULL
            MutexNative.ReleaseMutex.invokeExact(handle) as Int
            MutexNative.CloseHandle.invokeExact(handle) as Int
            logger.debug { "Released single-instance mutex '$mutexName'" }
        }
    }

    companion object {
        const val DEFAULT_MUTEX_NAME: String = "Local\\KotlinSwitcherooSingleInstanceMutex"
        const val DEV_MUTEX_NAME: String = "Local\\KotlinSwitcherooDevMutex"
    }
}
