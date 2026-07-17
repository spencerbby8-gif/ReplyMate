package com.replymate.core.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryInspectorModelTest {
    @Test fun `approved memory preserves source trace fields`() {
        val memory = MemoryRecord("memory", "contact", MemoryCategory.PREFERENCE, "Prefers short replies", sourceMessageId = "message", sourceConversationId = "conversation", confidence = .92f, explanation = "Explicitly stated")
        assertEquals("message", memory.sourceMessageId)
        assertEquals("conversation", memory.sourceConversationId)
        assertEquals(.92f, memory.confidence)
    }
}
