package com.streamcloud.app.ui.viewmodel

private data class SearchTitleMatch(
    val title: String,
    val distance: Int,
    val lengthGap: Int,
)

internal fun findClosestSearchTitle(
    query: String,
    candidates: List<String>,
): String? {
    val queryTokens = searchTokens(query)
    if (queryTokens.isEmpty() || queryTokens.any { it.length < 3 }) return null

    return candidates
        .asSequence()
        .mapNotNull { candidate ->
            val splitCandidateTokens = searchTokens(candidate)
            val candidateTokens = if (splitCandidateTokens.size > 1) {
                splitCandidateTokens + splitCandidateTokens.joinToString("")
            } else {
                splitCandidateTokens
            }
            if (candidateTokens.isEmpty()) return@mapNotNull null

            val used = BooleanArray(candidateTokens.size)
            var totalDistance = 0
            var totalLengthGap = 0
            for (queryToken in queryTokens) {
                val best = candidateTokens.indices
                    .asSequence()
                    .filterNot { used[it] }
                    .map { index ->
                        val token = candidateTokens[index]
                        index to editDistance(queryToken, token)
                    }
                    .minByOrNull { (_, distance) -> distance }
                    ?: return@mapNotNull null
                val tokenLimit = when {
                    queryToken.length <= 5 -> 1
                    queryToken.length <= 8 -> 2
                    else -> 3
                }
                if (best.second > tokenLimit) return@mapNotNull null
                used[best.first] = true
                totalDistance += best.second
                totalLengthGap += kotlin.math.abs(
                    queryToken.length - candidateTokens[best.first].length,
                )
            }

            if (totalDistance == 0) return@mapNotNull null
            SearchTitleMatch(candidate, totalDistance, totalLengthGap)
        }
        .sortedWith(
            compareBy<SearchTitleMatch> { it.distance }
                .thenBy { it.lengthGap }
                .thenBy { it.title.length },
        )
        .firstOrNull()
        ?.title
}

private fun searchTokens(value: String): List<String> =
    value
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }

private fun editDistance(first: String, second: String): Int {
    if (first == second) return 0
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length

    var previous = IntArray(second.length + 1) { it }
    for (row in first.indices) {
        val current = IntArray(second.length + 1)
        current[0] = row + 1
        for (column in second.indices) {
            val substitution = if (first[row] == second[column]) 0 else 1
            current[column + 1] = minOf(
                current[column] + 1,
                previous[column + 1] + 1,
                previous[column] + substitution,
            )
        }
        previous = current
    }
    return previous[second.length]
}