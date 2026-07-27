package com.newoether.agora.util

import kotlin.math.ceil

/**
 * Token count estimator ported from the `tokenx` npm package (MIT).
 * https://github.com/johannschopplich/tokenx
 *
 * Calibrated against OpenAI's o200k_base / cl100k encoding, ~95% accuracy.
 *
 * Algorithm (per segment after splitting on whitespace/punctuation boundaries):
 *   - Pure whitespace: 0 (1 if structural: indentation / blank line)
 *   - CJK / Korean / Japanese: 1 char = 1 token
 *   - Numeric-only: always 1 token
 *   - Short (≤3 chars): 1 token
 *   - Pure punctuation run: ceil(len / 2)
 *   - Extended Latin (German/French/Russian/etc.): ceil(len / rate)
 *   - Default: ceil(len / 6)
 */
object TokenEstimator {

    // Splits on whitespace runs and punctuation runs; the regex is used with
    // findAll so delimiters are kept as explicit segments (mirrors JS split
    // with a capturing group).
    private val SPLIT_PATTERN = Regex("""(\s+|[.,!?;(){}\[\]<>:/\\|@#${'$'}%^&*+=`~_"'\-]+)""")

    private val WHITESPACE       = Regex("""^\s+$""")
    private val STRUCTURED_WS    = Regex("""\n\s""")  // indentation / blank lines cost a token
    private val CJK              = Regex(
        "[\u4E00-\u9FFF\u3400-\u4DBF\u3000-\u303F\uFF00-\uFFEF" +
        "\u30A0-\u30FF\u2E80-\u2EFF\u31C0-\u31EF\u3200-\u32FF\u3300-\u33FF" +
        "\uAC00-\uD7AF\u1100-\u11FF\u3130-\u318F\uA960-\uA97F\uD7B0-\uD7FF]"
    )
    private val NUMERIC          = Regex("""^\d+$""")
    private val ALL_PUNCTUATION  = Regex("""^[.,!?;(){}\[\]<>:/\\|@#${'$'}%^&*+=`~_"'\-]+$""")

    // Language-specific chars-per-token rates (tokenx defaults, calibrated on o200k_base)
    private val LANGUAGE_RATES = listOf(
        Regex("[äöüßẞ]",     RegexOption.IGNORE_CASE) to 3.0,   // German
        Regex("[éèêëàâîïôûùüÿçœæáíóúñ]", RegexOption.IGNORE_CASE) to 3.0,  // French/Spanish
        Regex("[ąćęłńóśźżěščřžýůúďťň]",  RegexOption.IGNORE_CASE) to 3.5,  // Czech/Polish
        Regex("[\u0430-\u044F\u0451]",    RegexOption.IGNORE_CASE) to 3.5,  // Russian
        Regex("[\u03AC-\u03CE]",          RegexOption.IGNORE_CASE) to 2.75, // Greek
    )

    private const val DEFAULT_CHARS_PER_TOKEN = 6.0
    private const val SHORT_THRESHOLD         = 3

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        // Tokenize keeping delimiters as separate segments — mirrors JS split with
        // a capturing group, which returns the delimiters interleaved with the parts.
        val segments = mutableListOf<String>()
        var cursor = 0
        for (match in SPLIT_PATTERN.findAll(text)) {
            if (match.range.first > cursor) segments += text.substring(cursor, match.range.first)
            segments += match.value
            cursor = match.range.last + 1
        }
        if (cursor < text.length) segments += text.substring(cursor)
        return segments.filter { it.isNotEmpty() }.sumOf { score(it) }
    }

    private fun score(seg: String): Int {
        // Whitespace
        if (WHITESPACE.matches(seg)) return if (STRUCTURED_WS.containsMatchIn(seg)) 1 else 0
        // Language-specific override (checked before CJK/numeric)
        for ((pattern, rate) in LANGUAGE_RATES) {
            if (pattern.containsMatchIn(seg)) return ceil(seg.length / rate).toInt()
        }
        // CJK: each code point is its own token
        if (CJK.containsMatchIn(seg)) return seg.codePointCount(0, seg.length)
        // All digits: 1 token regardless of length
        if (NUMERIC.matches(seg)) return 1
        // Very short word
        if (seg.length <= SHORT_THRESHOLD) return 1
        // Pure punctuation run
        if (ALL_PUNCTUATION.matches(seg)) return ceil(seg.length / 2.0).toInt()
        // Default English/Latin
        return ceil(seg.length / DEFAULT_CHARS_PER_TOKEN).toInt()
    }
}
