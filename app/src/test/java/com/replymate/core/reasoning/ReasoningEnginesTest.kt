package com.replymate.core.reasoning
import com.replymate.core.conversation.*
import org.junit.Assert.*
import org.junit.Test
class ReasoningEnginesTest {
 private val memory=MemoryContext(Contact("c",MessagingPlatform.PLAYGROUND,"p","Test"),null,null, emptyList(),emptyList(),emptyList(),null,emptyList())
 @Test fun `question chooses informative answer strategy`() { val plan=ResponsePlanningService().plan("Can you help me with this?",memory); assertEquals(ResponseStrategy.INFORMATIVE,plan.strategy); assertEquals(ConversationGoal.ANSWER_QUESTION,plan.goal) }
 @Test fun `relationship remains unknown without approved memory`() { assertNull(ResponsePlanningService().plan("hey",memory).relationship.label) }
}
