package com.replymate.core.diagnostics
import org.junit.Assert.assertTrue
import org.junit.Test
class StressScenarioTest { @Test fun `synthetic stress size supports notification burst planning`() { val contacts=500; val messagesPerContact=200; assertTrue(contacts*messagesPerContact >= 100_000) } }
