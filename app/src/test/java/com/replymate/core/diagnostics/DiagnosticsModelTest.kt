package com.replymate.core.diagnostics
import org.junit.Assert.assertEquals
import org.junit.Test
class DiagnosticsModelTest { @Test fun `regeneration rate is represented as bounded fraction`() { val rate=1.0/4.0; assertEquals(.25,rate,.001) } }
