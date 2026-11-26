package com.music.aria.data.sources

import com.music.aria.data.TrackLog
import com.music.aria.observability.SessionTraceReferenceEncoder
import com.music.aria.observability.StructuredTracePoint
import com.music.aria.observability.StructuredTraceSink
import com.music.aria.observability.StructuredTracer
import com.music.aria.observability.TraceFeature
import com.music.aria.observability.TraceOutcome
import com.music.aria.observability.TracePointKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolutionCorrelationD2Test {
    @Test fun providerKeepsExplicitPlaybackFlowAcrossThreadHop() = runBlocking {
        val recorded = mutableListOf<StructuredTracePoint>()
        val tracer = StructuredTracer(
            sink = StructuredTraceSink { recorded += it },
            referenceEncoder = SessionTraceReferenceEncoder("ephemeral-session-salt"),
        )
        val command = tracer.startTrace(TraceFeature.PLAYBACK, "command")
        val reporter = StructuredSourceResolutionTraceReporter(tracer)
        val source = withContext(Dispatchers.Default) {
            reporter.startCorrelated("raw-account-credential", "private-media-id", command.context())
        }
        val result = withContext(Dispatchers.Default) {
            source.lookup {
                val correlation = TrackLog.currentCorrelation()
                assertEquals(command.context().flowId, correlation?.flowId)
                assertTrue(correlation?.parentSpanId?.startsWith("ref_") == true)
                "stream-ready"
            }
        }
        assertEquals(null, TrackLog.currentCorrelation())
        source.complete(found = true)
        command.complete(TraceOutcome.SUCCEEDED)
        assertEquals("stream-ready", result)
        val start = recorded.first {
            it.feature == TraceFeature.PROVIDER && it.kind == TracePointKind.TRACE_START
        }
        assertEquals(command.context().flowId, start.flowId)
        assertEquals(command.context().spanId, start.parentSpanId)
        assertFalse(start.attributes.toString().contains("private-media-id"))
        assertFalse(start.attributes.toString().contains("raw-account-credential"))
        assertTrue(start.attributes["trackRef"]?.startsWith("ref_") == true)
    }
}
