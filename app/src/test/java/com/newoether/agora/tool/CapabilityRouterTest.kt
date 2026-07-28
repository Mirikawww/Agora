package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRouterTest {

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty> = mapOf(
            "query" to ToolProperty("string", "The user request."),
        ),
    ) = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties),
        ),
    )

    @Test
    fun `pure greetings take the zero tool path`() {
        val tools = listOf(
            tool("github_request", "Call GitHub."),
            tool("web_search", "Search the web."),
        )

        for (greeting in listOf("你好", "你好！", "嗨", "Hello", "hi there", "早上好")) {
            val route = CapabilityRouter.route(greeting, emptyList(), tools)

            assertEquals(greeting, CapabilityRouteMode.NO_TOOL, route.mode)
            assertEquals(greeting, emptyList<String>(), route.selectedToolNames)
            assertEquals(greeting, 0, route.schemaTokenEstimate)
            assertTrue(greeting, route.requiresBroker)
        }
    }

    @Test
    fun `Chinese task intent selects Todoist schemas directly`() {
        val tools = listOf(
            tool("todoist_add_tasks", "Create one or more Todoist tasks."),
            tool("todoist_find_tasks", "Find Todoist tasks."),
            tool("notion_create_page", "Create a Notion page."),
            tool("web_search", "Search the web."),
        )

        val route = CapabilityRouter.route("明天下午三点提醒我提交报告", emptyList(), tools)

        assertEquals(CapabilityRouteMode.DIRECT, route.mode)
        assertTrue(route.selectedToolNames.contains("todoist_add_tasks"))
        assertTrue(route.selectedToolNames.all { it.startsWith("todoist_") })
        assertTrue("unselected tools must remain reachable", route.requiresBroker)
    }

    @Test
    fun `mixed request selects each relevant capability family`() {
        val tools = listOf(
            tool("web_search", "Search current information on the web."),
            tool("notion_create_page", "Create a page in Notion."),
            tool("todoist_add_tasks", "Create a Todoist task."),
        )

        val route = CapabilityRouter.route(
            "Search the latest release and save it to a Notion page",
            emptyList(),
            tools,
        )

        assertEquals(CapabilityRouteMode.MIXED, route.mode)
        assertTrue(route.selectedToolNames.contains("web_search"))
        assertTrue(route.selectedToolNames.contains("notion_create_page"))
        assertFalse(route.selectedToolNames.contains("todoist_add_tasks"))
    }

    @Test
    fun `common Chinese lookup phrasing selects web without broker discovery`() {
        val tools = listOf(
            tool("web_search", "Search current information on the web."),
            tool("notion_create_page", "Create a page in Notion."),
        )

        for (request in listOf("请去网上查查 Android 16", "请核实这个消息", "今天发生了什么")) {
            val route = CapabilityRouter.route(request, emptyList(), tools)

            assertTrue(request, route.selectedToolNames.contains("web_search"))
        }
    }

    @Test
    fun `follow-up can inherit a recent explicit web intent`() {
        val tools = listOf(
            tool("web_search", "Search current information on the web."),
            tool("notion_create_page", "Create a page in Notion."),
        )

        val route = CapabilityRouter.route(
            currentText = "继续详细一点",
            recentTexts = listOf("搜索一下 Android 16 的最新发布信息"),
            tools = tools,
        )

        assertTrue(route.selectedToolNames.contains("web_search"))
    }

    @Test
    fun `acknowledgements and short emoji turns stay broker only`() {
        val tools = listOf(
            tool("web_search", "Search current information on the web."),
            tool("ask_user", "Ask the user a clarifying question."),
        )

        for (request in listOf("谢谢", "好的", "在吗", "👍", "❤️", "👨‍👩‍👧‍👦")) {
            val route = CapabilityRouter.route(request, emptyList(), tools)

            assertEquals(request, CapabilityRouteMode.NO_TOOL, route.mode)
            assertTrue(request, route.selectedToolNames.isEmpty())
        }
    }

    @Test
    fun `unknown intent uses broker instead of guessing or dropping tools`() {
        val tools = listOf(
            tool("mcp_custom_analyze", "Perform a domain-specific operation."),
            tool("web_search", "Search the web."),
        )

        val route = CapabilityRouter.route("帮我处理这件事", emptyList(), tools)

        assertEquals(CapabilityRouteMode.BROKER, route.mode)
        assertTrue(route.selectedToolNames.isEmpty())
        assertTrue(route.requiresBroker)
    }

    @Test
    fun `greeting schema cost stays constant as registry grows`() {
        fun routedCost(count: Int): Int {
            val tools = (1..count).map { index ->
                tool("connector_tool_$index", "Connector capability $index.")
            }
            return CapabilityRouter.route("你好", emptyList(), tools).schemaTokenEstimate
        }

        assertEquals(0, routedCost(5))
        assertEquals(0, routedCost(500))
    }
}
