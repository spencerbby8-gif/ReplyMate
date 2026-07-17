package com.replymate.core.persistence

import com.replymate.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class PersonalizationRepository(private val dao: PersonalizationDao) {
    val personalization: Flow<Personalization> = combine(dao.observeProfile(), dao.observeStyle()) { profile, style ->
        Personalization(profile = profile?.toModel() ?: MyProfile(), globalStyle = style?.toModel() ?: WritingStyle(), customPrompt = profile?.customPrompt.orEmpty())
    }
    suspend fun save(value: Personalization) {
        dao.saveProfile(value.profile.toEntity(value.customPrompt))
        dao.saveStyle(value.globalStyle.toEntity())
    }
    suspend fun saveContactRule(value: ContactStyleRule) = dao.saveContactRule(value.toEntity())
    suspend fun reset() = dao.resetAll()
}
private fun PersonalizationProfileEntity.toModel() = MyProfile(nameOrNickname, personality, interests, habits, backgroundStory, relationshipStyle, additionalContext)
private fun MyProfile.toEntity(prompt: String) = PersonalizationProfileEntity(nameOrNickname = nameOrNickname, personality = personality, interests = interests, habits = habits, backgroundStory = backgroundStory, relationshipStyle = relationshipStyle, additionalContext = additionalContext, customPrompt = prompt)
private fun GlobalWritingStyleEntity.toModel() = WritingStyle(Formality.valueOf(formality), DetailLevel.valueOf(detailLevel), HumorLevel.valueOf(humorLevel), Directness.valueOf(directness), Flirtiness.valueOf(flirtiness), UsageLevel.valueOf(emojiUsage), UsageLevel.valueOf(slangUsage), greetingStyle, closingStyle)
private fun WritingStyle.toEntity() = GlobalWritingStyleEntity(formality = formality.name, detailLevel = detailLevel.name, humorLevel = humorLevel.name, directness = directness.name, flirtiness = flirtiness.name, emojiUsage = emojiUsage.name, slangUsage = slangUsage.name, greetingStyle = greetingStyle, closingStyle = closingStyle)
private fun ContactStyleRule.toEntity() = ContactStyleRuleEntity(contactId, relationshipContext, styleOverride?.formality?.name, styleOverride?.detailLevel?.name, styleOverride?.humorLevel?.name, styleOverride?.directness?.name, styleOverride?.flirtiness?.name, styleOverride?.emojiUsage?.name, styleOverride?.slangUsage?.name, styleOverride?.greetingStyle, styleOverride?.closingStyle, customInstructions, enabled)
