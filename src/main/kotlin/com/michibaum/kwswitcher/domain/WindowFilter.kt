package com.michibaum.kwswitcher.domain

object WindowFilter {

    fun filter(windows: List<WindowInfo>, query: String): List<WindowInfo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return windows
        }
        val tokens = trimmed.lowercase().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return windows
        }

        return windows
            .mapNotNull { window ->
                val score = scoreWindow(window = window, fullQuery = trimmed.lowercase(), tokens = tokens)
                if (score > 0) ScoredWindow(window = window, score = score) else null
            }
            .sortedWith(
                compareByDescending<ScoredWindow> { it.score }
                    .thenBy { it.window.zOrder },
            )
            .map { it.window }
    }

    private fun scoreWindow(
        window: WindowInfo,
        fullQuery: String,
        tokens: List<String>,
    ): Int {
        val titleLower = window.title.lowercase()
        val processLower = window.processName.lowercase()
        val combined = "$processLower $titleLower"

        val allTokensMatch = tokens.all { token -> combined.contains(token) }
        if (!allTokensMatch) {
            return 0
        }

        var score = 100
        if (titleLower.startsWith(fullQuery) || processLower.startsWith(fullQuery)) {
            score += 300
        } else if (titleLower.contains(fullQuery)) {
            score += 200
        } else if (processLower.contains(fullQuery)) {
            score += 150
        }
        return score
    }

    private data class ScoredWindow(
        val window: WindowInfo,
        val score: Int,
    )

    private val WHITESPACE_REGEX = Regex("\\s+")
}
