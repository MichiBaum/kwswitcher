package com.michibaum.kwswitcher.domain

/**
 * Ranked fuzzy match result pairing a [WindowInfo] with its cumulative relevance [score]
 * and matched character index ranges in [titleHighlights] and [processHighlights].
 */
data class WindowMatch(
    val window: WindowInfo,
    val score: Int,
    val titleHighlights: List<IntRange> = emptyList(),
    val processHighlights: List<IntRange> = emptyList(),
) {
    companion object {
        /**
         * Merges overlapping or adjacent [IntRange] spans into a sorted, non-overlapping list.
         */
        fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
            val nonEmpty = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
            if (nonEmpty.size <= 1) return nonEmpty

            val merged = mutableListOf<IntRange>()
            var current = nonEmpty[0]
            for (i in 1 until nonEmpty.size) {
                val next = nonEmpty[i]
                current = if (next.first <= current.last + 1) {
                    current.first..maxOf(current.last, next.last)
                } else {
                    merged.add(current)
                    next
                }
            }
            merged.add(current)
            return merged
        }
    }
}
