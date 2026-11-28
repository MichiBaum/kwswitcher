package com.michibaum.kwswitcher.core.domain

/**
 * Multi-token ranked fuzzy matcher that scores open windows against a user query
 * and computes character-index highlight ranges for UI rendering (`FR-5`).
 */
object WindowFilter {

    private val WHITESPACE_REGEX = Regex("\\s+")

    private const val DUAL_MATCH_BONUS = 40
    private const val SECONDARY_HIGHLIGHT_MIN_SCORE = 400
    private const val EXACT_PREFIX_BASE_SCORE = 1_000
    private const val MAX_PREFIX_LENGTH_BONUS = 100
    private const val BOUNDARY_PREFIX_BASE_SCORE = 750
    private const val MAX_POSITION_BONUS = 80
    private const val ACRONYM_SCORE = 650
    private const val SUBSTRING_BASE_SCORE = 400
    private const val FUZZY_SPAN_MULTIPLIER = 4
    private const val FUZZY_SPAN_PADDING = 4
    private const val FUZZY_BASE_SCORE = 120
    private const val FUZZY_CONSECUTIVE_BONUS = 25
    private const val FUZZY_BOUNDARY_BONUS = 20
    private const val FUZZY_MAX_COMPACTNESS_BONUS = 40
    private const val FUZZY_GAP_PENALTY = 3
    private const val MIN_FUZZY_SCORE = 25

    /**
     * Filters and ranks [windows] against [query], returning only the ordered [WindowInfo] items.
     */
    fun filter(windows: List<WindowInfo>, query: String): List<WindowInfo> =
        match(windows = windows, query = query).map { it.window }

    /**
     * Filters and ranks [windows] against [query], returning [WindowMatch] entries with highlight spans.
     */
    fun match(windows: List<WindowInfo>, query: String): List<WindowMatch> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return windows.map { WindowMatch(window = it, score = 0) }
        }
        val tokens = trimmed.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return windows.map { WindowMatch(window = it, score = 0) }
        }

        return windows
            .mapNotNull { window -> matchWindow(window = window, tokens = tokens) }
            .sortedWith(
                compareByDescending<WindowMatch> { it.score }
                    .thenBy { it.window.zOrder },
            )
    }

    private fun matchWindow(window: WindowInfo, tokens: List<String>): WindowMatch? {
        var totalScore = 0
        val titleRanges = mutableListOf<IntRange>()
        val processRanges = mutableListOf<IntRange>()

        for (rawToken in tokens) {
            val tokenLower = rawToken.lowercase()
            val titleMatch = matchTokenInText(text = window.title, tokenLower = tokenLower)
            val processMatch = matchTokenInText(text = window.processName, tokenLower = tokenLower)

            if (titleMatch == null && processMatch == null) {
                return null
            }

            totalScore += accumulateTokenMatch(
                titleMatch = titleMatch,
                processMatch = processMatch,
                titleRanges = titleRanges,
                processRanges = processRanges,
            )
        }

        return WindowMatch(
            window = window,
            score = totalScore,
            titleHighlights = WindowMatch.mergeRanges(titleRanges),
            processHighlights = WindowMatch.mergeRanges(processRanges),
        )
    }

    private fun accumulateTokenMatch(
        titleMatch: TokenMatch?,
        processMatch: TokenMatch?,
        titleRanges: MutableList<IntRange>,
        processRanges: MutableList<IntRange>,
    ): Int = when {
        titleMatch != null && processMatch != null -> {
            if (titleMatch.score >= processMatch.score) {
                titleRanges.addAll(titleMatch.ranges)
                if (processMatch.score >= SECONDARY_HIGHLIGHT_MIN_SCORE) {
                    processRanges.addAll(processMatch.ranges)
                }
                titleMatch.score + DUAL_MATCH_BONUS
            } else {
                processRanges.addAll(processMatch.ranges)
                if (titleMatch.score >= SECONDARY_HIGHLIGHT_MIN_SCORE) {
                    titleRanges.addAll(titleMatch.ranges)
                }
                processMatch.score + DUAL_MATCH_BONUS
            }
        }

        titleMatch != null -> {
            titleRanges.addAll(titleMatch.ranges)
            titleMatch.score
        }

        processMatch != null -> {
            processRanges.addAll(processMatch.ranges)
            processMatch.score
        }

        else -> 0
    }

    internal fun matchTokenInText(text: String, tokenLower: String): TokenMatch? {
        if (text.isEmpty() || tokenLower.isEmpty() || tokenLower.length > text.length) {
            return null
        }
        val textLower = text.lowercase()

        if (textLower.startsWith(tokenLower)) {
            val lengthBonus = (MAX_PREFIX_LENGTH_BONUS - minOf(text.length, MAX_PREFIX_LENGTH_BONUS)).coerceAtLeast(0)
            return TokenMatch(
                score = EXACT_PREFIX_BASE_SCORE + lengthBonus,
                ranges = listOf(0 until tokenLower.length),
            )
        }

        val boundaryPrefixIndex = findWordBoundaryPrefix(text = text, textLower = textLower, tokenLower = tokenLower)
        if (boundaryPrefixIndex >= 0) {
            val posBonus = (MAX_POSITION_BONUS - minOf(boundaryPrefixIndex, MAX_POSITION_BONUS)).coerceAtLeast(0)
            return TokenMatch(
                score = BOUNDARY_PREFIX_BASE_SCORE + posBonus,
                ranges = listOf(boundaryPrefixIndex until (boundaryPrefixIndex + tokenLower.length)),
            )
        }

        val acronymIndices = findAcronymMatch(text = text, textLower = textLower, tokenLower = tokenLower)
        if (acronymIndices != null) {
            return TokenMatch(
                score = ACRONYM_SCORE,
                ranges = indicesToRanges(acronymIndices),
            )
        }

        val substringIndex = textLower.indexOf(tokenLower)
        if (substringIndex >= 0) {
            val posBonus = (MAX_POSITION_BONUS - minOf(substringIndex, MAX_POSITION_BONUS)).coerceAtLeast(0)
            return TokenMatch(
                score = SUBSTRING_BASE_SCORE + posBonus,
                ranges = listOf(substringIndex until (substringIndex + tokenLower.length)),
            )
        }

        return findFuzzySubsequence(text = text, textLower = textLower, tokenLower = tokenLower)
    }

    private fun findWordBoundaryPrefix(text: String, textLower: String, tokenLower: String): Int {
        for (i in 1..(text.length - tokenLower.length)) {
            if (isWordBoundary(text, i) && textLower.regionMatches(i, tokenLower, 0, tokenLower.length)) {
                return i
            }
        }
        return -1
    }

    private fun findAcronymMatch(text: String, textLower: String, tokenLower: String): IntArray? {
        if (tokenLower.length < 2) return null
        val boundaries = IntArray(text.length)
        var boundaryCount = 0
        for (i in text.indices) {
            if (isWordBoundary(text, i)) {
                boundaries[boundaryCount++] = i
            }
        }
        if (boundaryCount < tokenLower.length) return null

        val matched = IntArray(tokenLower.length)
        var bIdx = 0
        for (tIdx in tokenLower.indices) {
            val targetChar = tokenLower[tIdx]
            var found = false
            while (bIdx < boundaryCount) {
                val textIdx = boundaries[bIdx++]
                if (textLower[textIdx] == targetChar) {
                    matched[tIdx] = textIdx
                    found = true
                    break
                }
            }
            if (!found) return null
        }
        return matched
    }

    private fun findFuzzySubsequence(text: String, textLower: String, tokenLower: String): TokenMatch? {
        val matched = matchSubsequencePositions(text = text, textLower = textLower, tokenLower = tokenLower)
            ?: return null

        val spanLength = matched.last() - matched.first() + 1
        val maxAllowedSpan = tokenLower.length * FUZZY_SPAN_MULTIPLIER + FUZZY_SPAN_PADDING
        if (spanLength > maxAllowedSpan) {
            return null
        }

        var consecutiveCount = 0
        var boundaryCount = 0
        for (i in matched.indices) {
            if (i > 0 && matched[i] == matched[i - 1] + 1) {
                consecutiveCount++
            }
            if (isWordBoundary(text, matched[i])) {
                boundaryCount++
            }
        }

        val compactnessBonus =
            (FUZZY_MAX_COMPACTNESS_BONUS - (spanLength - tokenLower.length) * FUZZY_GAP_PENALTY).coerceAtLeast(0)
        val score = (
            FUZZY_BASE_SCORE +
                consecutiveCount * FUZZY_CONSECUTIVE_BONUS +
                boundaryCount * FUZZY_BOUNDARY_BONUS +
                compactnessBonus
            ).coerceAtLeast(MIN_FUZZY_SCORE)
        return TokenMatch(
            score = score,
            ranges = indicesToRanges(matched),
        )
    }

    private fun matchSubsequencePositions(text: String, textLower: String, tokenLower: String): IntArray? {
        val matched = IntArray(tokenLower.length)
        var searchFrom = 0
        for (tIdx in tokenLower.indices) {
            val ch = tokenLower[tIdx]
            var chosenIndex = -1
            var firstIndex = -1
            for (i in searchFrom until textLower.length) {
                if (textLower[i] == ch) {
                    if (firstIndex == -1) {
                        firstIndex = i
                    }
                    if (isWordBoundary(text, i) || (tIdx > 0 && i == matched[tIdx - 1] + 1)) {
                        chosenIndex = i
                        break
                    }
                }
            }
            val finalIdx = if (chosenIndex != -1) chosenIndex else firstIndex
            if (finalIdx == -1) return null
            matched[tIdx] = finalIdx
            searchFrom = finalIdx + 1
        }
        return matched
    }

    private fun isWordBoundary(text: String, index: Int): Boolean {
        if (index == 0) return true
        val prev = text[index - 1]
        val curr = text[index]
        return (!prev.isLetterOrDigit() && curr.isLetterOrDigit()) ||
            (prev.isLowerCase() && curr.isUpperCase()) ||
            (prev.isLetter() && curr.isDigit())
    }

    private fun indicesToRanges(indices: IntArray): List<IntRange> {
        if (indices.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = indices[0]
        var end = indices[0]
        for (i in 1 until indices.size) {
            if (indices[i] == end + 1) {
                end = indices[i]
            } else {
                ranges.add(start..end)
                start = indices[i]
                end = indices[i]
            }
        }
        ranges.add(start..end)
        return ranges
    }

    internal data class TokenMatch(val score: Int, val ranges: List<IntRange>)
}
