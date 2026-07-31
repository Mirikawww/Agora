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
 *   - Numeric-only: conservatively about 3 digits per token
 *   - Short (≤3 chars): 1 token
 *   - Pure punctuation run: ceil(len / 2)
 *   - Extended Latin (German/French/Russian/etc.): ceil(len / rate)
 *   - Default: ceil(len / 6)
 *
 * Implemented as a single-pass character scanner rather than with regexes. Tool-schema budgeting
 * tokenizes hundreds of kilobytes of JSON on a generation's critical path, where the regex version
 * ran at roughly 2 MB/s — enough to add seconds of latency before the request was dispatched.
 * [com.newoether.agora.util.TokenEstimatorEquivalenceTest] pins this against the regex original.
 */
object TokenEstimator {

    private const val DEFAULT_CHARS_PER_TOKEN = 6.0
    private const val NUMERIC_CHARS_PER_TOKEN = 3.0
    private const val SHORT_THRESHOLD = 3

    // Rates and ordering mirror the original LANGUAGE_RATES list: the first class that appears
    // anywhere in a segment wins, regardless of where its character sits.
    private const val RATE_GERMAN = 3.0
    private const val RATE_FRENCH = 3.0
    private const val RATE_CZECH_POLISH = 3.5
    private const val RATE_RUSSIAN = 3.5
    private const val RATE_GREEK = 2.75

    // The original classes were regexes with RegexOption.IGNORE_CASE. Java applies ASCII-only case
    // folding unless UNICODE_CASE is also set, so the accented characters below never matched their
    // uppercase forms and are reproduced here as literal sets.
    private const val GERMAN = "äöüßẞ"
    private const val FRENCH =
        "éèêëàâîïôûùüÿ" +
            "çœæáíóúñ"
    private const val CZECH_POLISH =
        "ąćęłńóśźżěščř" +
            "žýůúďťň"

    /**
     * `\s` in java.util.regex, i.e. space, tab, newline, vertical tab, form feed, carriage return
     * — deliberately not Unicode whitespace, so NBSP and friends stay word characters exactly as
     * the original regex treated them. Vertical tab and form feed are vanishingly rare in JSON but
     * must still be classified, or they would score as one-token words instead of free whitespace.
     */
    private const val WHITESPACE_CHARS = " \t\n\u000B\u000C\r"
    private const val PUNCTUATION_CHARS = ".,!?;(){}[]<>:/\\|@#\$%^&*+=`~_\"'-"

    /** ASCII dispatch table; every whitespace and punctuation character is ASCII. */
    private const val CLASS_WORD: Byte = 0
    private const val CLASS_WHITESPACE: Byte = 1
    private const val CLASS_PUNCTUATION: Byte = 2

    private val asciiClass = ByteArray(128).also { table ->
        WHITESPACE_CHARS.forEach { table[it.code] = CLASS_WHITESPACE }
        PUNCTUATION_CHARS.forEach { table[it.code] = CLASS_PUNCTUATION }
    }

    private fun classOf(c: Char): Byte =
        if (c.code < 128) asciiClass[c.code] else CLASS_WORD

    private fun isRussian(c: Char): Boolean = (c in 'а'..'я') || c == 'ё'

    private fun isGreek(c: Char): Boolean = c in 'ά'..'ώ'

    private fun isCjk(c: Char): Boolean = when (c) {
        in '一'..'鿿' -> true // CJK Unified Ideographs
        in '㐀'..'䶿' -> true // CJK Extension A
        in '　'..'〿' -> true // CJK Symbols and Punctuation
        in '＀'..'￯' -> true // Halfwidth and Fullwidth Forms
        in '゠'..'ヿ' -> true // Katakana
        in '⺀'..'⻿' -> true // CJK Radicals Supplement
        in '㇀'..'㇯' -> true // CJK Strokes
        in '㈀'..'㋿' -> true // Enclosed CJK Letters and Months
        in '㌀'..'㏿' -> true // CJK Compatibility
        in '가'..'힯' -> true // Hangul Syllables
        in 'ᄀ'..'ᇿ' -> true // Hangul Jamo
        in '㄰'..'㆏' -> true // Hangul Compatibility Jamo
        in 'ꥠ'..'꥿' -> true // Hangul Jamo Extended-A
        in 'ힰ'..'퟿' -> true // Hangul Jamo Extended-B
        else -> false
    }

    private fun isLanguageChar(c: Char): Boolean =
        GERMAN.indexOf(c) >= 0 ||
            FRENCH.indexOf(c) >= 0 ||
            CZECH_POLISH.indexOf(c) >= 0 ||
            isRussian(c) ||
            isGreek(c)

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var total = 0
        var index = 0
        val length = text.length
        while (index < length) {
            val start = index
            when (classOf(text[index])) {
                CLASS_WHITESPACE -> {
                    while (index < length && classOf(text[index]) == CLASS_WHITESPACE) index++
                    total += scoreWhitespace(text, start, index)
                }
                CLASS_PUNCTUATION -> {
                    while (index < length && classOf(text[index]) == CLASS_PUNCTUATION) index++
                    total += scorePunctuation(index - start)
                }
                else -> {
                    while (index < length && classOf(text[index]) == CLASS_WORD) index++
                    total += scoreWord(text, start, index)
                }
            }
        }
        return total
    }

    /** Indentation and blank lines cost a token; a plain separating space costs nothing. */
    private fun scoreWhitespace(text: String, start: Int, end: Int): Int {
        for (i in start until end - 1) {
            if (text[i] == '\n') return 1
        }
        return 0
    }

    /**
     * A punctuation run holds no letters or digits, so only the length rules can apply — and the
     * original checked the short-segment rule before the pure-punctuation rule.
     */
    private fun scorePunctuation(length: Int): Int =
        if (length <= SHORT_THRESHOLD) 1 else ceil(length / 2.0).toInt()

    private fun scoreWord(text: String, start: Int, end: Int): Int {
        val length = end - start
        var hasLanguageChar = false
        var hasCjk = false
        var allDigits = true
        for (i in start until end) {
            val c = text[i]
            if (c.code < 128) {
                // ASCII word characters belong to no language or CJK class; only digits matter.
                if (c < '0' || c > '9') allDigits = false
                continue
            }
            allDigits = false
            if (!hasLanguageChar && isLanguageChar(c)) hasLanguageChar = true
            if (!hasCjk && isCjk(c)) hasCjk = true
        }
        // Language classes are tested against the whole segment in declaration order, so the
        // winning class is not necessarily the one whose character appears first.
        if (hasLanguageChar) return ceil(length / languageRate(text, start, end)).toInt()
        // CJK: each code point is its own token.
        if (hasCjk) return text.codePointCount(start, end)
        // Modern BPE vocabularies group only a few digits at a time. Treating an arbitrary-length
        // number as one token lets numeric enum values bypass every schema budget.
        if (allDigits) return ceil(length / NUMERIC_CHARS_PER_TOKEN).toInt()
        if (length <= SHORT_THRESHOLD) return 1
        return ceil(length / DEFAULT_CHARS_PER_TOKEN).toInt()
    }

    private fun languageRate(text: String, start: Int, end: Int): Double {
        for (i in start until end) if (GERMAN.indexOf(text[i]) >= 0) return RATE_GERMAN
        for (i in start until end) if (FRENCH.indexOf(text[i]) >= 0) return RATE_FRENCH
        for (i in start until end) if (CZECH_POLISH.indexOf(text[i]) >= 0) return RATE_CZECH_POLISH
        for (i in start until end) if (isRussian(text[i])) return RATE_RUSSIAN
        for (i in start until end) if (isGreek(text[i])) return RATE_GREEK
        return DEFAULT_CHARS_PER_TOKEN
    }
}
