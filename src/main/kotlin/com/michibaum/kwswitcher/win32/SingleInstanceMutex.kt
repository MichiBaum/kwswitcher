package com.michibaum.kwswitcher.win32

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Single-instance guard backed by a Win32 named mutex (`CreateMutexW`) (`FR-9`).
 */
class SingleInstanceMutex(
    private val mutexName: String = DEFAULT_MUTEX_NAME,
) : AutoCloseable {

    private var mutexHandle: MemorySegment = MemorySegment.NULL

    /**
     * Attempts to acquire the named mutex. Returns `true` if this process is the primary instance,
     * or `false` if another instance already holds the mutex.
     */
    fun tryAcquire(): Boolean {
        if (mutexHandle != MemorySegment.NULL) {
            return true
        }
        Arena.ofConfined().use { arena ->
            val chars = mutexName.toCharArray()
            val nameSegment = arena.allocate(ValueLayout.JAVA_CHAR, (chars.size + 1).toLong())
            for (i in chars.indices) {
                nameSegment.setAtIndex(ValueLayout.JAVA_CHAR, i.toLong(), chars[i])
            }
            nameSegment.setAtIndex(ValueLayout.JAVA_CHAR, chars.size.toLong(), 0.toChar())

            val handle = Win32Bindings.CreateMutexW.invokeExact(
                MemorySegment.NULL,
                1,
                nameSegment,
            ) as MemorySegment

            if (handle == MemorySegment.NULL) {
                return false
            }

            val lastError = Win32Bindings.GetLastError.invokeExact() as Int
            if (lastError == Win32Bindings.ERROR_ALREADY_EXISTS) {
                Win32Bindings.CloseHandle.invokeExact(handle) as Int
                return false
            }

            mutexHandle = handle
            return true
        }
    }

    override fun close() {
        val handle = mutexHandle
        if (handle != MemorySegment.NULL) {
            mutexHandle = MemorySegment.NULL
            Win32Bindings.ReleaseMutex.invokeExact(handle) as Int
            Win32Bindings.CloseHandle.invokeExact(handle) as Int
        }
    }

    companion object {
        const val DEFAULT_MUTEX_NAME: String = "Local\\KotlinSwitcherooSingleInstanceMutex"
    }
}
