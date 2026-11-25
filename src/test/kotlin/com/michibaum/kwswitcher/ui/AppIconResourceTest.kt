package com.michibaum.kwswitcher.ui

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppIconResourceTest {

    private data class IconDirEntry(
        val width: Int,
        val height: Int,
        val planes: Int,
        val bitCount: Int,
        val bytesInRes: Int,
        val imageOffset: Int,
    )

    @Test
    fun `kwswitcher ico exists on classpath with valid header and multi-resolution PNG frames`() {
        val resourceBytes = Thread.currentThread()
            .contextClassLoader
            .getResourceAsStream("icons/kwswitcher.ico")
            ?.use { it.readAllBytes() }

        val bytes = assertNotNull(resourceBytes, "icons/kwswitcher.ico must be packaged on the classpath")
        val expectedSizes = listOf(16, 24, 32, 48, 64, 256)
        assertTrue(bytes.size > HEADER_BYTES + expectedSizes.size * DIR_ENTRY_BYTES)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, buffer.short.toInt() and USHORT_MASK, "ICO header reserved field must be 0")
        assertEquals(1, buffer.short.toInt() and USHORT_MASK, "ICO header type field must be 1 (icon)")
        val count = buffer.short.toInt() and USHORT_MASK
        assertEquals(expectedSizes.size, count, "ICO must embed all expected multi-resolution frames")

        val entries = (0 until count).map { readDirEntry(buffer) }
        assertEquals(expectedSizes, entries.map { it.width }, "ICO frame widths must match expected sizes")
        assertEquals(expectedSizes, entries.map { it.height }, "ICO frame heights must match expected sizes")

        val minPayloadOffset = HEADER_BYTES + count * DIR_ENTRY_BYTES
        for (entry in entries) {
            verifyFrame(bytes, entry, minPayloadOffset)
        }
    }

    private fun readDirEntry(buffer: ByteBuffer): IconDirEntry {
        val rawWidth = buffer.get().toInt() and BYTE_MASK
        val rawHeight = buffer.get().toInt() and BYTE_MASK
        buffer.get() // colorCount
        val entryReserved = buffer.get().toInt() and BYTE_MASK
        assertEquals(0, entryReserved, "Directory entry reserved byte must be 0")
        return IconDirEntry(
            width = if (rawWidth == 0) MAX_ICO_DIM else rawWidth,
            height = if (rawHeight == 0) MAX_ICO_DIM else rawHeight,
            planes = buffer.short.toInt() and USHORT_MASK,
            bitCount = buffer.short.toInt() and USHORT_MASK,
            bytesInRes = buffer.int,
            imageOffset = buffer.int,
        )
    }

    private fun verifyFrame(bytes: ByteArray, entry: IconDirEntry, minPayloadOffset: Int) {
        assertEquals(1, entry.planes, "Planes must be 1 for ${entry.width}x${entry.height}")
        assertEquals(ARGB_BIT_COUNT, entry.bitCount, "Bit depth must be 32-bit ARGB for ${entry.width}x${entry.height}")
        assertTrue(entry.bytesInRes > PNG_SIGNATURE.size, "Frame payload must be non-empty")
        assertTrue(
            entry.imageOffset >= minPayloadOffset && entry.imageOffset + entry.bytesInRes <= bytes.size,
            "Frame byte range must lie within ICO file bounds",
        )

        val frameHeader = bytes.copyOfRange(entry.imageOffset, entry.imageOffset + PNG_SIGNATURE.size)
        assertTrue(frameHeader.contentEquals(PNG_SIGNATURE), "Frame ${entry.width}x${entry.height} must be PNG")

        val decoded = ImageIO.read(ByteArrayInputStream(bytes, entry.imageOffset, entry.bytesInRes))
        val image = assertNotNull(decoded, "ImageIO must decode PNG frame ${entry.width}x${entry.height}")
        assertEquals(entry.width, image.width)
        assertEquals(entry.height, image.height)
        assertContainsBrandMotif(image)
    }

    private fun assertContainsBrandMotif(image: BufferedImage) {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val hasBrandBlue = pixels.any { argb -> isOpaqueColor(argb, BRAND_BLUE) }
        val hasWhiteGlyph = pixels.any { argb -> isOpaqueWhite(argb) }
        assertTrue(hasBrandBlue, "Frame ${image.width}x${image.height} must render #2563EB rounded tile")
        assertTrue(hasWhiteGlyph, "Frame ${image.width}x${image.height} must render white switch arrows")
    }

    private fun isOpaqueColor(argb: Int, target: Color): Boolean {
        val alpha = (argb ushr ALPHA_SHIFT) and BYTE_MASK
        val r = (argb ushr RED_SHIFT) and BYTE_MASK
        val g = (argb ushr GREEN_SHIFT) and BYTE_MASK
        val b = argb and BYTE_MASK
        return alpha > OPAQUE_ALPHA_THRESHOLD && r == target.red && g == target.green && b == target.blue
    }

    private fun isOpaqueWhite(argb: Int): Boolean {
        val alpha = (argb ushr ALPHA_SHIFT) and BYTE_MASK
        val r = (argb ushr RED_SHIFT) and BYTE_MASK
        val g = (argb ushr GREEN_SHIFT) and BYTE_MASK
        val b = argb and BYTE_MASK
        return alpha > OPAQUE_ALPHA_THRESHOLD && r > WHITE_CHANNEL_MIN && g > WHITE_CHANNEL_MIN && b > WHITE_CHANNEL_MIN
    }

    private companion object {
        const val HEADER_BYTES = 6
        const val DIR_ENTRY_BYTES = 16
        const val MAX_ICO_DIM = 256
        const val ARGB_BIT_COUNT = 32
        const val BYTE_MASK = 0xFF
        const val USHORT_MASK = 0xFFFF
        const val ALPHA_SHIFT = 24
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val OPAQUE_ALPHA_THRESHOLD = 200
        const val WHITE_CHANNEL_MIN = 240
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val BRAND_BLUE = Color(0x25, 0x63, 0xEB)
    }
}
