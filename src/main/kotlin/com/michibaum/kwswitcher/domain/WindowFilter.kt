package com.michibaum.kwswitcher.domain

/**
 * Multi-token ranked fuzzy matcher that scores open windows against a user query
 * and computes character-index highlight ranges for UI rendering (`FR-5`).
 */
object WindowFilter {

    private val WHITESPACE_REGEX = Regex("\\s+")

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

            when {
                titleMatch != null && processMatch != null -> {
                    if (titleMatch.score >= processMatch.score) {
                        totalScore += titleMatch.score + 40
                        titleRanges.addAll(titleMatch.ranges)
                        if (processMatch.score >= 400) {
                            processRanges.addAll(processMatch.ranges)
                        }
                    } else {
                        totalScore += processMatch.score + 40
                        processRanges.addAll(processMatch.ranges)
                        if (titleMatch.score >= 400) {
                            titleRanges.addAll(titleMatch.ranges)
                        }
                    }
                }

                titleMatch != null -> {
                    totalScore += titleMatch.score
                    titleRanges.addAll(titleMatch.ranges)
                }

                processMatch != null -> {
                    totalScore += processMatch.score
                    processRanges.addAll(processMatch.ranges)
                }
            }
        }

        return WindowMatch(
            window = window,
            score = totalScore,
            titleHighlights = WindowMatch.mergeRanges(titleRanges),
            processHighlights = WindowMatch.mergeRanges(processRanges),
        )
    }

    internal fun matchTokenInText(text: String, tokenLower: String): TokenMatch? {
        if (text.isEmpty() || tokenLower.isEmpty() || tokenLower.length > text.length) {
            return null
        }
        val textLower = text.lowercase()

        // Tier 1: Exact prefix match
        if (textLower.startsWith(tokenLower)) {
            val lengthBonus = (100 - minOf(text.length, 100)).coerceAtLeast(0)
            return TokenMatch(
                score = 1_000 + lengthBonus,
                ranges = listOf(0 until tokenLower.length),
            )
        }

        // Tier 2a: Word-boundary / camelCase prefix match
        val boundaryPrefixIndex = findWordBoundaryPrefix(text = text, textLower = textLower, tokenLower = tokenLower)
        if (boundaryPrefixIndex >= 0) {
            val posBonus = (80 - minOf(boundaryPrefixIndex, 80)).coerceAtLeast(0)
            return TokenMatch(
                score = 750 + posBonus,
                ranges = listOf(boundaryPrefixIndex until (boundaryPrefixIndex + tokenLower.length)),
            )
        }

        // Tier 2b: Acronym / word-boundary sequence match (e.g. "ij" -> "IntelliJ", "ps" -> "PowerShell")
        val acronymIndices = findAcronymMatch(text = text, textLower = textLower, tokenLower = tokenLower)
        if (acronymIndices != null) {
            return TokenMatch(
                score = 650,
                ranges = indicesToRanges(acronymIndices),
            )
        }

        // Tier 3: Contiguous substring match
        val substringIndex = textLower.indexOf(tokenLower)
        if (substringIndex >= 0) {
            val posBonus = (80 - minOf(substringIndex, 80)).coerceAtLeast(0)
            return TokenMatch(
                score = 400 + posBonus,
                ranges = listOf(substringIndex until (substringIndex + tokenLower.length)),
            )
        }

        // Tier 4: Fuzzy character subsequence match
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

        val spanLength = matched.last() - matched.first() + 1
        // Reject overly scattered single/two-char subsequence noise on long strings
        val maxAllowedSpan = tokenLower.length * 4 + 4
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

        val compactnessBonus = (40 - (spanLength - tokenLower.length) * 3).coerceAtLeast(0)
        val score = (120 + consecutiveCount * 25 + boundaryCount * 20 + compactnessBonus).coerceAtLeast(25)
        return TokenMatch(
            score = score,
            ranges = indicesToRanges(matched),
        )
    }

    private fun isWordBoundary(text: String, index: Int): Boolean {
        if (index == 0) return true
        val prev = text[index - 1]
        val curr = text[index]
        if (!prev.isLetterOrDigit() && curr.isLetterOrDigit()) return true
        if (prev.isLowerCase() && curr.isUpperCase()) return true
        if (prev.isLetter() && curr.isDigit()) return true
        return false
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

    internal data class TokenMatch(
        val score: Int,
        val ranges: List<IntRange>,
    )
}
