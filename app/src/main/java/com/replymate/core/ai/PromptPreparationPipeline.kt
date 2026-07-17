package com.replymate.core.ai

import com.replymate.core.model.PromptRequest

/** Approximation is intentionally local: exact provider counting can be added behind TokenCounter later. */
interface TokenCounter { fun estimate(text: String): Int }
class ConservativeTokenCounter : TokenCounter {
    override fun estimate(text: String): Int = if (text.isBlank()) 0 else (text.length + 3) / 4
}
data class PromptBudget(val maxInputTokens: Int = 6_000, val reservedOutputTokens: Int = 500)
data class PreparedPrompt(val text: String, val estimatedTokens: Int, val omittedHistoryTurns: Int, val omittedMemoryItems: Int, val fitsBudget: Boolean)

/** Builds a transparent prompt but never submits it to a provider in this slice. */
class PromptPreparationPipeline(private val assembler: PromptAssembler, private val counter: TokenCounter, private val diagnostics: AiDiagnostics = NoOpAiDiagnostics) {
    fun prepare(request: PromptRequest, budget: PromptBudget = PromptBudget()): PreparedPrompt {
        val limit = (budget.maxInputTokens - budget.reservedOutputTokens).coerceAtLeast(1)
        var history = request.recentHistory
        var memory = request.contactMemory
        var prompt = assembler.assemble(request)
        var tokens = counter.estimate(prompt)
        var droppedMemory = 0
        var droppedHistory = 0
        // Lowest-priority context is removed first; latest message/profile/style are never silently cut.
        while (tokens > limit && memory.isNotEmpty()) {
            memory = memory.drop(1); droppedMemory++
            prompt = assembler.assemble(request.copy(contactMemory = memory, recentHistory = history)); tokens = counter.estimate(prompt)
        }
        while (tokens > limit && history.isNotEmpty()) {
            history = history.drop(1); droppedHistory++
            prompt = assembler.assemble(request.copy(contactMemory = memory, recentHistory = history)); tokens = counter.estimate(prompt)
        }
        val prepared = PreparedPrompt(prompt, tokens, droppedHistory, droppedMemory, tokens <= limit)
        diagnostics.event("prompt_prepared", mapOf("estimatedTokens" to prepared.estimatedTokens, "omittedHistoryTurns" to prepared.omittedHistoryTurns, "omittedMemoryItems" to prepared.omittedMemoryItems, "fitsBudget" to prepared.fitsBudget))
        return prepared
    }
}
