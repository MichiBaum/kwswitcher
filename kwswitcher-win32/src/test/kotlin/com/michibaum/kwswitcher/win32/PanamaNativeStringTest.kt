package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.win32.bindings.PanamaNative.allocateWideString
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.readWideString
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class PanamaNativeStringTest {

    @Test
    fun `allocateWideString and readWideString round-trip UTF-16 strings with trailing NUL termination`() {
        Arena.ofConfined().use { arena ->
            val samples = listOf(
                "",
                "Local\\KotlinSwitcherooDevMutex",
                "C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe",
                "Café München — 日本語 Window",
            )
            for (sample in samples) {
                val segment = arena.allocateWideString(sample)
                assertEquals((sample.length + 1) * 2L, segment.byteSize())
                assertEquals(0.toChar(), segment.getAtIndex(ValueLayout.JAVA_CHAR, sample.length.toLong()))
                assertEquals(sample, segment.readWideString(sample.length + 1))
                assertEquals(sample, segment.readWideString(sample.length))
            }
            val emptyBuf = arena.allocate(ValueLayout.JAVA_CHAR, 4L)
            assertEquals("", emptyBuf.readWideString(0))
            assertEquals("", emptyBuf.readWideString(4))
        }
    }
}
