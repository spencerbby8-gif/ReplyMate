package com.replymate.core.reasoning

enum class DetectedIntent { QUESTION, HELP_REQUEST, CASUAL_CONVERSATION, MAKING_PLANS, APOLOGY, THANKS, CONGRATULATIONS, FLIRTING, EMOTIONAL_SUPPORT, SHARING_NEWS, GREETING, FAREWELL }
enum class DetectedEmotion { HAPPY, EXCITED, NEUTRAL, SAD, FRUSTRATED, ANGRY, WORRIED, SURPRISED, PLAYFUL }
enum class ConversationGoal { ANSWER_QUESTION, CONTINUE_CONVERSATION, COMFORT, MAKE_PLANS, KEEP_BRIEF, ASK_FOLLOW_UP, END_POLITELY }
enum class ResponseStrategy { INFORMATIVE, CONVERSATIONAL, HUMOROUS, SUPPORTIVE, EMPATHETIC, PLAYFUL, DIRECT, PROFESSIONAL, FLIRTY }
data class LabelConfidence<T>(val label: T, val confidence: Float)
data class ConversationAnalysis(val topic: String, val urgency: Int, val complexity: Int, val hasQuestion: Boolean, val hasRequest: Boolean, val hasGreeting: Boolean, val hasFarewell: Boolean, val hasFollowUp: Boolean, val humorIndicator: Boolean, val sarcasmIndicator: Boolean, val continuity: Boolean)
data class RelationshipAssessment(val label: String?, val explanation: String)
data class ResponsePlan(val analysis: ConversationAnalysis, val intents: List<LabelConfidence<DetectedIntent>>, val emotion: LabelConfidence<DetectedEmotion>, val relationship: RelationshipAssessment, val goal: ConversationGoal, val strategy: ResponseStrategy, val strategyExplanation: String)
data class ReplyQualityReport(val answersMessage: Float, val styleMatch: Float, val memoryConsistency: Float, val relevance: Float, val naturalness: Float, val completeness: Float, val estimatedConfidence: Float, val concerns: List<String>, val passed: Boolean)
data class ReasonedDraft(val reply: String, val plan: ResponsePlan, val preparedPrompt: com.replymate.core.ai.PreparedPrompt, val quality: ReplyQualityReport, val regenerated: Boolean, val generationDurationMs: Long, val provider: String, val model: String)
