package com.replymate.core.diagnostics

import android.content.Context
import com.replymate.core.persistence.ReplyMateDatabase
import com.replymate.core.platform.PlatformEventQueue
import com.replymate.core.draft.DraftRepository
import com.replymate.core.ai.AiProviderSettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File

data class DiagnosticsSnapshot(
 val appVersion:String,val databaseVersion:Int,val startupMs:Long,val contacts:Int,val conversations:Int,val memories:Int,val candidates:Int,val drafts:Int,val failures:Int,val averageGenerationMs:Double?,val averageTokens:Double?,val regenerationRate:Double,val queue:Map<String,Int>,val averageNotificationMs:Double?,val platforms:Map<String,Int>,val orphanContacts:Int,val orphanMemories:Int,val storageBytes:Long,val notes:List<String>
)
/** Local-only, sanitized diagnostics: no content, prompt, secret, or personal profile fields. */
class DiagnosticsService(private val context:Context,private val db:ReplyMateDatabase,private val queue:PlatformEventQueue,private val drafts:DraftRepository,private val settings:AiProviderSettingsRepository,private val performance:PerformanceMonitor){
 suspend fun snapshot():DiagnosticsSnapshot { val c=db.conversationDao(); val i=db.memoryInspectorDao(); val d=db.draftDao(); val allDrafts=d.count(); val failed=d.failureCount(); return DiagnosticsSnapshot("0.1.0",6,performance.startupMs(),c.contactCount(),c.conversationCount(),c.memoryCount(),i.pendingCountAll(),allDrafts,failed,d.averageGenerationDuration(),d.averageTokenEstimate(),if(allDrafts==0)0.0 else d.regenerationCount().toDouble()/allDrafts,queue.statistics(),db.platformEventDao().averageProcessingDuration(),db.platformEventDao().platformDistribution().associate{it.platform to it.count},c.orphanContactCount(),c.orphanMemoryCount(),context.getDatabasePath("replymate.db").length(),listOf("Battery impact is evaluated through Android system battery settings; per-feature energy attribution is not available without profiling tools.","Thread utilization is intentionally not persisted to avoid diagnostic overhead.")) }
 suspend fun exportSanitized():File { val s=snapshot(); return File(context.cacheDir,"replymate-diagnostics.txt").apply { writeText("ReplyMate diagnostics\nversion=${s.appVersion}\ndatabase=${s.databaseVersion}\ncontacts=${s.contacts}\nconversations=${s.conversations}\nmemories=${s.memories}\ncandidates=${s.candidates}\ndrafts=${s.drafts}\nfailures=${s.failures}\navgGenerationMs=${s.averageGenerationMs}\navgTokens=${s.averageTokens}\nqueue=${s.queue}\nplatforms=${s.platforms}\norphanContacts=${s.orphanContacts}\norphanMemories=${s.orphanMemories}\nstorageBytes=${s.storageBytes}\n") } }
}
