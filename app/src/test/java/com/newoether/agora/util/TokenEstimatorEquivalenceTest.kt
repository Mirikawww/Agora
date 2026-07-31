package com.newoether.agora.util

import kotlin.math.ceil
import kotlin.random.Random
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the scanner-based [TokenEstimator] to the regex implementation it replaced.
 *
 * The reference below is a verbatim copy of the original object. Schema budgets in
 * [com.newoether.agora.tool.McpDeferredToolProvider] are enforced in estimated tokens, so a drift
 * of even a few tokens would silently change which tool schemas are placed on the wire.
 */
class TokenEstimatorEquivalenceTest {

    /** The pre-optimization implementation, unchanged. */
    private object ReferenceEstimator {
        private val SPLIT_PATTERN =
            Regex("""(\s+|[.,!?;(){}\[\]<>:/\\|@#${'$'}%^&*+=`~_"'\-]+)""")

        private val WHITESPACE = Regex("""^\s+$""")
        private val STRUCTURED_WS = Regex("""\n\s""")
        private val CJK = Regex(
            "[一-鿿㐀-䶿　-〿＀-￯" +
                "゠-ヿ⺀-⻿㇀-㇯㈀-㋿㌀-㏿" +
                "가-힯ᄀ-ᇿ㄰-㆏ꥠ-꥿ힰ-퟿]"
        )
        private val NUMERIC = Regex("""^\d+$""")
        private val ALL_PUNCTUATION =
            Regex("""^[.,!?;(){}\[\]<>:/\\|@#${'$'}%^&*+=`~_"'\-]+$""")

        private val LANGUAGE_RATES = listOf(
            Regex("[äöüßẞ]", RegexOption.IGNORE_CASE) to 3.0,
            Regex("[éèêëàâîïôûùüÿçœæáíóúñ]", RegexOption.IGNORE_CASE) to 3.0,
            Regex("[ąćęłńóśźżěščřžýůúďťň]", RegexOption.IGNORE_CASE) to 3.5,
            Regex("[а-яё]", RegexOption.IGNORE_CASE) to 3.5,
            Regex("[ά-ώ]", RegexOption.IGNORE_CASE) to 2.75,
        )

        private const val DEFAULT_CHARS_PER_TOKEN = 6.0
        private const val NUMERIC_CHARS_PER_TOKEN = 3.0
        private const val SHORT_THRESHOLD = 3

        fun estimate(text: String): Int {
            if (text.isEmpty()) return 0
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
            if (WHITESPACE.matches(seg)) return if (STRUCTURED_WS.containsMatchIn(seg)) 1 else 0
            for ((pattern, rate) in LANGUAGE_RATES) {
                if (pattern.containsMatchIn(seg)) return ceil(seg.length / rate).toInt()
            }
            if (CJK.containsMatchIn(seg)) return seg.codePointCount(0, seg.length)
            if (NUMERIC.matches(seg)) return ceil(seg.length / NUMERIC_CHARS_PER_TOKEN).toInt()
            if (seg.length <= SHORT_THRESHOLD) return 1
            if (ALL_PUNCTUATION.matches(seg)) return ceil(seg.length / 2.0).toInt()
            return ceil(seg.length / DEFAULT_CHARS_PER_TOKEN).toInt()
        }
    }

    private fun assertSame(text: String) {
        assertEquals(
            "mismatch for ${text.take(120)}",
            ReferenceEstimator.estimate(text),
            TokenEstimator.estimate(text),
        )
    }

    @Test
    fun `matches the reference on representative fixtures`() {
        listOf(
            "",
            " ",
            "\n",
            "\n  ",
            "  \n\n  ",
            "\t\t",
            "a",
            "abc",
            "abcd",
            "hello world",
            "12",
            "1234567890",
            "007",
            "...",
            "....",
            "!!!!!!!!",
            "-----",
            "___",
            "a_b_c",
            "snake_case_tool_name",
            "mcp_todoist_ab12cd_add_tasks_9f1e2b",
            "Straße größer Fußball",
            "élève déjà çà œuvre",
            "zażółć gęślą jaźń",
            "Привет мир ёжик",
            "καλημέρα κόσμε",
            "你好世界",
            "こんにちは世界",
            "안녕하세요 세계",
            "混合 mixed 文本 text 123",
            "Ünïcödé ünd Straße",
            "emoji 🎉 outside the classes",
            "surrogate 𝔘𝔫𝔦𝔠𝔬𝔡𝔢 pair",
            "trailing whitespace   ",
            "   leading whitespace",
            "tab\tseparated\tvalues",
            "crlf\r\nline\r\nends",
            "averticaltab",
            "{\"type\":\"object\",\"properties\":{}}",
            "value_1_2_3",
            "$%^&*",
            "punct.then123numbers",
            "ß",
            "ẞ",
            "ё",
            "ά",
            "ώ",
        ).forEach(::assertSame)
    }

    @Test
    fun `matches the reference on a realistic tool schema`() {
        val schema = buildJsonObject {
            put("type", "object")
            put("description", "Créer une tâche — Straße, 日本語, and ASCII all in one description.")
            put("properties", buildJsonObject {
                repeat(30) { p ->
                    put("property_$p", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Field $p accepts values 1234567890, symbols !@#$%^&*(), " +
                                "accented naïve café, Cyrillic значение, and 中文说明.",
                        )
                        put("enum", buildJsonArray {
                            repeat(25) { e -> add(JsonPrimitive("value-$p-$e")) }
                        })
                    })
                }
            })
        }.toString()

        assertSame(schema)
        assertEquals(
            ReferenceEstimator.estimate(schema),
            TokenEstimator.estimate(schema),
        )
    }

    @Test
    fun `matches the reference on randomized input`() {
        // Draws from every branch: whitespace runs, punctuation runs, digits, ASCII words, each
        // accented language class, CJK/Hangul blocks, and code points outside all of them.
        val alphabet = buildList {
            // Every character java.util.regex counts as \s, including the vertical tab and form
            // feed an earlier revision of the scanner omitted from its whitespace class.
            addAll(" \t\n\r".toList())
            addAll(".,!?;(){}[]<>:/\\|@#$%^&*+=`~_\"'-".toList())
            addAll(('0'..'9'))
            addAll(('a'..'z'))
            addAll(('A'..'Z'))
            addAll("äöüßẞ".toList())
            addAll("éèêëàâîïôûùüÿçœæáíóúñ".toList())
            addAll("ąćęłńóśźżěščřžýůúďťň".toList())
            addAll(('а'..'я'))
            add('ё')
            addAll(('ά'..'ώ'))
            addAll(listOf('一', '鿿', '㐀', '　', '＀', '゠', '가', 'ᄀ'))
            // Outside every class: Latin-1 letters, an emoji surrogate pair, Hebrew, Thai.
            addAll(listOf('þ', 'Ā', 'א', 'ก', '\uD83C', '\uDF89'))
        }

        val random = Random(20260801)
        repeat(4_000) {
            val length = random.nextInt(0, 60)
            val text = buildString(length) {
                repeat(length) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            assertSame(text)
        }
    }

    @Test
    fun `matches the reference on long repeated structures`() {
        assertSame("word ".repeat(5_000))
        assertSame("你好".repeat(5_000))
        assertSame("\n    indented\n".repeat(2_000))
        assertSame("1234567890".repeat(2_000))
    }
}
