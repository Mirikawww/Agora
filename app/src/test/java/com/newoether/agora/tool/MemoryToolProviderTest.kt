package com.newoether.agora.tool

import com.newoether.agora.data.MemoryManager
import com.newoether.agora.viewmodel.GenerationContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MemoryToolProviderTest {

    private val memoryManager = mockk<MemoryManager> {
        every { listFiles() } returns listOf(
            MemoryManager.MemoryFileInfo("notes.md", "My notes"),
            MemoryManager.MemoryFileInfo("data.json", "JSON data")
        )
        coEvery { readFile(any()) } returns "file content"
        coEvery { createFile(any(), any(), any()) } returns "Created"
        coEvery { editFile(any(), any(), any(), any(), any(), any()) } returns "Edited"
        coEvery { deleteFile(any()) } returns "Deleted"
        coEvery { updateActiveMemory(any(), any(), any(), any()) } returns "Updated"
    }

    private val provider = MemoryToolProvider(memoryManager)

    private val ctx = GenerationContext(
        accessSavedMemories = true,
        accessActiveMemory = true
    )

    @Test
    fun definitions_whenEnabled_advertisesOneToolCoveringEveryAction() {
        val defs = provider.definitions(ctx)
        assertEquals("six sibling tools were merged behind an action selector", 1, defs.size)
        assertEquals("memory", defs[0].function.name)

        val description = defs[0].function.description
        for (action in listOf("list", "read", "create", "edit", "delete", "update_active")) {
            assertTrue("action '$action' must stay discoverable", description.contains(action))
        }
        assertEquals(listOf("action"), defs[0].function.parameters?.required)
    }

    /** Access flags gate which actions exist, not merely which are mentioned. */
    @Test
    fun definitions_whenSavedMemoriesDisabled_advertisesOnlyActiveMemoryAction() {
        val disabledCtx = ctx.copy(accessSavedMemories = false, accessActiveMemory = true)
        val defs = provider.definitions(disabledCtx)
        assertEquals(1, defs.size)

        val description = defs[0].function.description
        assertTrue(description.contains("update_active"))
        assertFalse("file access must be genuinely withdrawn", description.contains("- delete —"))
        assertFalse(description.contains("- create —"))
        assertFalse(defs[0].function.parameters?.properties.orEmpty().containsKey("new_name"))
    }

    @Test
    fun execute_mergedTool_dispatchesOnAction() = runTest {
        assertEquals("Created", provider.execute("memory", """{"action":"create","name":"a.md","content":"x"}""", ctx))
        assertEquals("file content", provider.execute("memory", """{"action":"read","name":"a.md"}""", ctx))
        assertTrue(provider.execute("memory", """{"action":"list"}""", ctx).contains("notes.md"))
    }

    @Test
    fun execute_mergedTool_rejectsMissingOrUnknownAction() = runTest {
        assertTrue(provider.execute("memory", "{}", ctx).contains("'action' is required"))
        assertTrue(provider.execute("memory", """{"action":"frobnicate"}""", ctx).contains("unknown action"))
    }

    @Test
    fun definitions_whenAllDisabled_returnsEmpty() {
        val disabledCtx = ctx.copy(accessSavedMemories = false, accessActiveMemory = false)
        val defs = provider.definitions(disabledCtx)
        assertTrue(defs.isEmpty())
    }

    /** Legacy names stay executable so tool calls recorded before the merge still replay. */
    @Test
    fun handles_acceptsMergedNameAndLegacyNames() {
        assertTrue(provider.handles("memory"))
        assertTrue(provider.handles("list_memory_files"))
        assertTrue(provider.handles("read_memory_file"))
        assertTrue(provider.handles("update_active_memory"))
        assertFalse(provider.handles("web_search"))
        assertFalse(provider.handles("unknown_tool"))
    }

    @Test
    fun execute_listMemoryFiles_returnsJson() = runTest {
        val result = provider.execute("list_memory_files", "{}", ctx)
        assertTrue(result.contains("list_memory_files"))
        assertTrue(result.contains("notes.md"))
        assertTrue(result.contains("data.json"))
    }

    @Test
    fun execute_readMemoryFile_singleName() = runTest {
        val result = provider.execute("read_memory_file", """{"name":"notes.md"}""", ctx)
        assertEquals("file content", result)
    }

    @Test
    fun execute_createMemoryFile() = runTest {
        val result = provider.execute("create_memory_file", """{"name":"new.md","content":"hello"}""", ctx)
        assertEquals("Created", result)
    }

    @Test
    fun execute_updateActiveMemory_patch() = runTest {
        val result = provider.execute(
            "update_active_memory",
            """{"content":"placeholder","mode":"patch","old_string":"foo","new_string":"bar"}""",
            ctx
        )
        assertEquals("Updated", result)
        verify { memoryManager.updateActiveMemory("placeholder", "patch", "foo", "bar") }
    }

    @Test
    fun execute_unknownTool() = runTest {
        val result = provider.execute("unknown", "{}", ctx)
        assertTrue(result.contains("Unknown tool"))
    }
}
