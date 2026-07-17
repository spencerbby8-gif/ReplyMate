package com.replymate.core.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTest {
    @Test fun `memory context labels only its own supplied records`() {
        val contact = Contact("contact-a", MessagingPlatform.PLAYGROUND, "a", "Alex")
        val context = MemoryContext(contact, null, null, listOf(MemoryRecord("f", "contact-a", MemoryCategory.IMPORTANT_FACT, "Likes tea")), emptyList(), emptyList(), null, emptyList())
        assertEquals(listOf("Important fact: Likes tea"), context.promptMemory())
        assertTrue(context.asTurns().isEmpty())
    }
    @Test fun `planner retains latest turn as running context`() {
        val plan = MemoryUpdatePlanner().plan("c", listOf(ConversationMessage("m", "c", MessageDirection.INCOMING, "Latest test message", 1)))
        assertEquals("Latest test message", plan.updatedRunningContext)
    }
}
