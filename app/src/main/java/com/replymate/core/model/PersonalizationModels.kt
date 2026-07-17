package com.replymate.core.model

/** User-authored voice data only. Empty values mean "do not provide this context". */
data class MyProfile(
    val nameOrNickname: String = "",
    val personality: String = "",
    val interests: String = "",
    val habits: String = "",
    val backgroundStory: String = "",
    val relationshipStyle: String = "",
    val additionalContext: String = ""
)

enum class Formality { VERY_CASUAL, CASUAL, BALANCED, FORMAL, VERY_FORMAL }
enum class DetailLevel { VERY_SHORT, SHORT, BALANCED, DETAILED, VERY_DETAILED }
enum class HumorLevel { SERIOUS, LIGHT, BALANCED, PLAYFUL, VERY_FUNNY }
enum class Directness { SOFT, GENTLE, BALANCED, DIRECT, VERY_DIRECT }
enum class Flirtiness { NEUTRAL, SLIGHTLY_PLAYFUL, FLIRTY }
enum class UsageLevel { NEVER, LIGHT, BALANCED, FREQUENT }

data class WritingStyle(
    val formality: Formality = Formality.BALANCED,
    val detailLevel: DetailLevel = DetailLevel.BALANCED,
    val humorLevel: HumorLevel = HumorLevel.BALANCED,
    val directness: Directness = Directness.BALANCED,
    val flirtiness: Flirtiness = Flirtiness.NEUTRAL,
    val emojiUsage: UsageLevel = UsageLevel.LIGHT,
    val slangUsage: UsageLevel = UsageLevel.LIGHT,
    val greetingStyle: String = "",
    val closingStyle: String = ""
)

data class Personalization(
    val profile: MyProfile = MyProfile(),
    val globalStyle: WritingStyle = WritingStyle(),
    val customPrompt: String = ""
)

data class ContactStyleRule(
    val contactId: String,
    val relationshipContext: String = "",
    val styleOverride: WritingStyle? = null,
    val customInstructions: String = "",
    val enabled: Boolean = true
)

data class PromptRequest(
    val baseSystemPrompt: String,
    val personalization: Personalization,
    val contactRule: ContactStyleRule? = null,
    val recentHistory: List<ConversationTurn> = emptyList(),
    val contactMemory: List<String> = emptyList(),
    val latestIncomingMessage: String
)

data class ConversationTurn(val speaker: Speaker, val text: String)
enum class Speaker { ME, CONTACT }

/** Isolated development-only data. It is never treated as a real messaging contact. */
data class PlaygroundContact(
    val id: String,
    val name: String = "",
    val relationship: String = "",
    val nickname: String = "",
    val personality: String = "",
    val communicationStyle: String = "",
    val conversationSummary: String = "",
    val importantFacts: String = "",
    val preferences: String = "",
    val longTermMemory: String = "",
    val recentHistory: String = ""
)
