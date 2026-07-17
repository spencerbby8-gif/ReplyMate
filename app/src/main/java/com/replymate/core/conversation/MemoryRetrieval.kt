package com.replymate.core.conversation

/** Pure policy: only records belonging to the requested contact/conversation can enter a context. */
data class MemoryRetrievalPolicy(val recentMessageLimit: Int = 12, val factLimit: Int = 8, val preferenceLimit: Int = 5, val longTermLimit: Int = 5)
class MemoryUpdatePlanner {
    fun plan(conversationId: String, recent: List<ConversationMessage>): MemoryUpdatePlan {
        val newest = recent.lastOrNull()?.body.orEmpty()
        val candidate = if (recent.size >= 8) "Conversation has ${recent.size} retained recent turns. Latest topic: ${newest.take(280)}" else null
        return MemoryUpdatePlan(conversationId, newest.take(500), candidate, emptyList())
    }
}
