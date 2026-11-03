package ru.kadrograd

internal object ZonaSearch {
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(title: String): String =
        title.lowercase().replace('ё', 'е').replace(separators, " ").trim()

    fun probes(input: String): List<String> {
        val normalized = normalize(input)
        if (normalized.length !in 4..100) return emptyList()
        val words = normalized.split(' ')
        val longest = words.maxBy { it.length }
        val prefixLength = (longest.length / 2 + 1).coerceIn(3, 6)
        return listOf(normalized, words.dropLast(1).joinToString(" "), longest.take(prefixLength),
            longest, longest.take((prefixLength + 1).coerceAtMost(7)), longest.takeLast(prefixLength)).distinct()
            .filter { it.length >= 3 && !it.equals(input, ignoreCase = true) }
            .take(3)
    }

    fun distance(query: String, title: String): Int? {
        if (query.length !in 4..100 || title.length !in 1..104) return null
        val limit = (query.length / 6 + 1).coerceAtMost(3)
        if (kotlin.math.abs(query.length - title.length) > limit) return null
        val distances = Array(query.length + 1) { row -> IntArray(title.length + 1).also { it[0] = row } }
        for (column in title.indices) distances[0][column + 1] = column + 1
        for (row in 1..query.length) {
            for (column in 1..title.length) {
                val substitution = if (query[row - 1] == title[column - 1]) 0 else 1
                distances[row][column] = minOf(
                    distances[row - 1][column] + 1,
                    distances[row][column - 1] + 1,
                    distances[row - 1][column - 1] + substitution,
                )
                if (row > 1 && column > 1 && query[row - 1] == title[column - 2] && query[row - 2] == title[column - 1]) {
                    distances[row][column] = minOf(distances[row][column], distances[row - 2][column - 2] + 1)
                }
            }
        }
        return distances[query.length][title.length].takeIf { it <= limit }
    }
}
