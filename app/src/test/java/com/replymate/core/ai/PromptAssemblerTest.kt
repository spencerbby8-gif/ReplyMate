package com.replymate.core.ai

import com.replymate.core.model.*
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {
    @Test fun `assembles documented layers in required order without invented profile`() {
        val prompt = PromptAssembler().assemble(PromptRequest(
            baseSystemPrompt = "BASE", personalization = Personalization(
                profile = MyProfile(nameOrNickname = "Sam"), customPrompt = "CUSTOM"
            ), contactRule = ContactStyleRule("c1", customInstructions = "CONTACT"),
            recentHistory = listOf(ConversationTurn(Speaker.CONTACT, "HISTORY")),
            contactMemory = listOf("MEMORY"), latestIncomingMessage = "LATEST"
        ))
        listOf("BASE", "MY PROFILE", "GLOBAL WRITING STYLE", "MY CUSTOM INSTRUCTIONS", "PER-CONTACT STYLE RULES", "RECENT CONVERSATION HISTORY", "CONTACT MEMORY", "LATEST INCOMING MESSAGE").zipWithNext().forEach { (a,b) -> assertTrue(prompt.indexOf(a) < prompt.indexOf(b)) }
        assertTrue(prompt.contains("Name or nickname: Sam"))
        assertTrue(!prompt.contains("Background story:"))
    }
}
