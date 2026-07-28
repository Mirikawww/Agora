package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.util.TokenEstimator
import java.util.Locale

/**
 * Cheap, local intent router used before a request is sent to an LLM.
 *
 * It never decides whether a capability exists. The complete registry remains reachable through
 * the capability broker; this class only chooses the few schemas worth placing directly in the
 * current request. That distinction is what lets a greeting avoid a large schema upload without
 * turning tools off.
 */
object CapabilityRouter {
    private val trivialNoToolTurns = setOf(
        "hi", "hithere", "hello", "hey", "你好", "您好", "嗨", "哈喽", "早上好", "上午好",
        "下午好", "晚上好", "早", "晚安", "thanks", "thankyou", "ok", "okay", "gotit",
        "谢谢", "谢了", "好的", "好", "收到", "明白", "知道了", "在吗", "辛苦了",
    )

    /**
     * Intent vocabulary is intentionally bilingual and conservative. Tool names/descriptions
     * still participate in lexical scoring, so connectors added later work without changing this
     * table; these aliases primarily cover short Chinese requests where word tokenisation is weak.
     */
    private val intentAliases: Map<String, Set<String>> = linkedMapOf(
        "web" to setOf(
            "web", "internet", "online", "search", "fetch", "look up", "verify", "url",
            "website", "latest", "current", "today", "news", "weather", "网页", "网站",
            "联网", "上网", "网上", "搜索", "搜一下", "查一下", "查查", "核实", "验证",
            "今天", "现在", "实时", "最新", "新闻", "天气", "网址",
        ),
        "github" to setOf(
            "github", "repository", "repo", "pull request", "issue", "commit", "branch",
            "仓库", "代码库", "拉取请求", "议题", "代码提交", "提交代码", "分支",
        ),
        "todo" to setOf(
            "todo", "todoist", "task", "reminder", "project", "checklist", "due",
            "待办", "任务", "提醒", "清单", "截止", "项目",
        ),
        "notion" to setOf(
            "notion", "workspace", "page", "database", "block", "笔记", "页面", "数据库",
            "工作区",
        ),
        "memory" to setOf(
            "memory", "remember", "recall", "forget", "profile", "preference",
            "记忆", "记住", "想起", "忘记", "偏好", "个人资料",
        ),
        "conversation" to setOf(
            "conversation", "chat history", "earlier chat", "past chat", "previous chat",
            "对话", "聊天记录", "历史聊天", "以前聊", "之前聊",
        ),
        "files" to setOf(
            "file", "folder", "directory", "path", "shell", "terminal", "command", "code",
            "文件", "文件夹", "目录", "路径", "终端", "命令", "代码",
        ),
        "image" to setOf(
            "image", "picture", "photo", "draw", "illustration", "generate image",
            "图片", "图像", "照片", "画一", "生成图",
        ),
        "skills" to setOf("skill", "skills", "技能"),
        "balance" to setOf(
            "balance", "quota", "credits", "billing", "usage", "余额", "额度", "账单", "用量",
        ),
        "ask" to setOf("ask user", "clarify", "question", "询问用户", "澄清", "提问"),
    )

    fun route(
        currentText: String,
        recentTexts: List<String>,
        tools: List<ToolDefinition>,
        topK: Int = 6,
    ): CapabilityRoute {
        if (tools.isEmpty()) {
            return CapabilityRoute(
                mode = CapabilityRouteMode.NO_TOOL,
                selectedToolNames = emptyList(),
                confidence = 1f,
                reason = "No enabled tools.",
                schemaTokenEstimate = 0,
                requiresBroker = false,
            )
        }

        if (isTrivialNoToolTurn(currentText)) {
            return CapabilityRoute(
                mode = CapabilityRouteMode.NO_TOOL,
                selectedToolNames = emptyList(),
                confidence = 1f,
                reason = "Trivial chat turn; keep only the compact capability broker.",
                schemaTokenEstimate = 0,
                requiresBroker = true,
            )
        }

        val ranked = rank(currentText, recentTexts, tools)
        val selected = ranked.filter { it.score >= MIN_DIRECT_SCORE }.take(topK.coerceAtLeast(1))
        val currentLower = currentText.lowercase(Locale.ROOT)
        val matchedIntents = intentAliases.count { (_, aliases) ->
            aliases.any(currentLower::contains)
        }
        val mode = when {
            selected.isEmpty() -> CapabilityRouteMode.BROKER
            matchedIntents > 1 -> CapabilityRouteMode.MIXED
            else -> CapabilityRouteMode.DIRECT
        }
        val best = selected.firstOrNull()?.score ?: 0
        val confidence = when {
            best >= 20 -> 1f
            best <= 0 -> 0f
            else -> (best / 20f).coerceIn(0f, 1f)
        }
        val selectedTools = selected.map { it.tool }
        return CapabilityRoute(
            mode = mode,
            selectedToolNames = selectedTools.map { it.function.name },
            confidence = confidence,
            reason = if (selected.isEmpty()) {
                "No safe direct match; use the complete capability broker."
            } else {
                "Selected ${selected.size} relevant schema(s); all others remain broker-reachable."
            },
            schemaTokenEstimate = estimateSchemaTokens(selectedTools),
            requiresBroker = selectedTools.size < tools.size,
        )
    }

    /**
     * Ranks tools for both initial Top-K routing and broker search results.
     * A stable name tie-break makes requests cache-friendly across runs.
     */
    internal fun rank(
        query: String,
        recentTexts: List<String>,
        tools: List<ToolDefinition>,
    ): List<RankedCapability> {
        val lowerQuery = query.lowercase(Locale.ROOT)
        val lowerRecent = recentTexts.takeLast(3).joinToString(" ").lowercase(Locale.ROOT)
        val queryWords = words(lowerQuery)
        val recentWords = words(lowerRecent)

        return tools.map { tool ->
            val name = tool.function.name.lowercase(Locale.ROOT)
            val description = tool.function.description.lowercase(Locale.ROOT)
            val haystack = "$name $description"
            var score = 0

            if (lowerQuery.contains(name)) score += 30
            name.split('_', '-', '.', ':')
                .filter { it.length >= 3 }
                .forEach { term -> if (lowerQuery.contains(term)) score += 4 }

            for ((_, aliases) in intentAliases) {
                val queryHit = aliases.any(lowerQuery::contains)
                val toolHit = aliases.any(haystack::contains)
                if (queryHit && toolHit) score += 12
                else if (!queryHit && aliases.any(lowerRecent::contains) && toolHit) score += 4
            }

            score += queryWords.count { it.length >= 3 && haystack.contains(it) } * 2
            score += recentWords.count { it.length >= 4 && haystack.contains(it) }
            RankedCapability(tool, score)
        }.sortedWith(
            compareByDescending<RankedCapability> { it.score }
                .thenBy { it.tool.function.name },
        )
    }

    internal fun isTrivialNoToolTurn(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{P}\\p{S}\\p{M}\\p{Cf}]+"), "")
        return normalized in trivialNoToolTurns ||
            (text.isNotBlank() && normalized.isEmpty() && text.codePointCount(0, text.length) <= 16)
    }

    private fun words(value: String): Set<String> =
        Regex("[a-z0-9]+").findAll(value).map { it.value }.toSet()

    private fun estimateSchemaTokens(tools: List<ToolDefinition>): Int = tools.sumOf { tool ->
        TokenEstimator.estimate(tool.function.name) +
            TokenEstimator.estimate(tool.function.description) +
            TokenEstimator.estimate(tool.function.parameters.asJsonObject().toString())
    }

    private const val MIN_DIRECT_SCORE = 4
}

internal data class RankedCapability(
    val tool: ToolDefinition,
    val score: Int,
)

enum class CapabilityRouteMode {
    NO_TOOL,
    DIRECT,
    MIXED,
    BROKER,
}

data class CapabilityRoute(
    val mode: CapabilityRouteMode,
    val selectedToolNames: List<String>,
    val confidence: Float,
    val reason: String,
    val schemaTokenEstimate: Int,
    val requiresBroker: Boolean,
) {
    fun selectedToolsFrom(tools: List<ToolDefinition>): List<ToolDefinition> {
        val byName = tools.associateBy { it.function.name }
        return selectedToolNames.mapNotNull(byName::get)
    }
}
