package com.replymate.core.platform
import org.junit.Assert.assertEquals
import org.junit.Test
class EventValidatorTest { @Test fun `event status values retain explicit duplicate state`() { assertEquals(EventQueueStatus.DUPLICATE.name,"DUPLICATE") } }
