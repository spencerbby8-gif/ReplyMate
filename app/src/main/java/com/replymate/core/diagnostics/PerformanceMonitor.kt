package com.replymate.core.diagnostics
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
/** In-memory, content-free timing monitor. Metrics reset on process restart by design. */
class PerformanceMonitor(private val startedAt:Long=SystemClock.elapsedRealtime()){private val values=ConcurrentHashMap<String,MutableList<Long>>();fun record(metric:String,durationMs:Long){values.getOrPut(metric){mutableListOf()}.add(durationMs)};fun average(metric:String):Double?=values[metric]?.takeIf{it.isNotEmpty()}?.average();fun startupMs()=SystemClock.elapsedRealtime()-startedAt}
