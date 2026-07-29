package com.newoether.agora.tool

import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.viewmodel.GenerationContext

/**
 * Interface for tool providers that supply tool definitions and execution
 * logic to the LLM generation pipeline. Each implementation manages a
 * specific category of tools (memory, web search, RAG, shell, etc.).
 */
interface ToolProvider {
    /** The tool definitions this provider exposes for the given context.
     *  Returns empty list when the provider is disabled. */
    fun definitions(ctx: GenerationContext): List<ToolDefinition>

    /** Execute a named tool with the given JSON arguments string.
     *  Returns the result string (usually JSON). */
    suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String

    /** Whether this provider can execute the given tool name. */
    fun handles(name: String): Boolean

    /**
     * Request-aware dispatch seam. Most providers have stable names and use [handles]; providers
     * backed by request-scoped registries can resolve against [ctx] instead of mutable global
     * definition state.
     */
    fun handles(name: String, ctx: GenerationContext): Boolean = handles(name)
}
