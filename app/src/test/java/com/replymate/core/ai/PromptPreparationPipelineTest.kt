package com.replymate.core.ai

import com.replymate.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PromptPreparationPipelineTest {
    private val pipeline = PromptPreparationPipeline(PromptAssembler(), ConservativeTokenCounter())
    @Test fun `optimizer removes memory before history and preserves latest message`() {
        val result = pipeline.prepare(PromptRequest("base", Personalization(), recentHistory = listOf(ConversationTurn(Speaker.CONTACT, "history ".repeat(20))), contactMemory = listOf("memory ".repeat(50)), latestIncomingMessage = "latest"), PromptBudget(maxInputTokens = 70, reservedOutputTokens = 10))
        assertEquals(1, result.omittedMemoryItems)
        assertTrue(result.text.contains("latest"))
    }
    @Test fun `estimator never returns negative`() { assertEquals(0, ConservativeTokenCounter().estimate("")) }
}
