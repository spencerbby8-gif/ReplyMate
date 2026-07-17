package com.replymate.core.draft
import org.junit.Assert.assertEquals
import org.junit.Test
class DraftModelTest { @Test fun `draft defaults to reviewable generated state`() { assertEquals(DraftStatus.GENERATED.name,"GENERATED") } }
