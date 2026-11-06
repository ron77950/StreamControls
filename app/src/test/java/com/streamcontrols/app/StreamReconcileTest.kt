package com.streamcontrols.app

import com.streamcontrols.app.youtube.LiveStream
import com.streamcontrols.app.youtube.StreamsState
import com.streamcontrols.app.youtube.reconcileStreams
import org.junit.Assert.*
import org.junit.Test

class StreamReconcileTest {
    private fun stream(id: String, title: String) = LiveStream(id, title, null, null)

    @Test fun editedDraftAndOriginalBaselineSurviveRemoteChange() {
        val old = StreamsState(streams = listOf(stream("a", "original")), selectedId = "a", draft = "my edit", editingBaseTitle = "original")
        val refreshed = reconcileStreams(old, listOf(stream("a", "changed elsewhere")))
        assertEquals("my edit", refreshed.draft)
        assertEquals("original", refreshed.editingBaseTitle)
        assertEquals("changed elsewhere", refreshed.streams.single().title)
    }

    @Test fun uneditedDraftTracksRefresh() {
        val old = StreamsState(streams = listOf(stream("a", "original")), selectedId = "a", draft = "original", editingBaseTitle = "original")
        val refreshed = reconcileStreams(old, listOf(stream("a", "new")))
        assertEquals("new", refreshed.draft)
        assertEquals("new", refreshed.editingBaseTitle)
    }

    @Test fun selectionIsNotArbitraryForMultipleStreams() {
        val refreshed = reconcileStreams(StreamsState(), listOf(stream("a", "one"), stream("b", "two")))
        assertNull(refreshed.selectedId)
    }

    @Test fun endedStreamClearsSelectionAndDraft() {
        val old = StreamsState(streams = listOf(stream("a", "original")), selectedId = "a", draft = "edit", editingBaseTitle = "original")
        val refreshed = reconcileStreams(old, emptyList())
        assertNull(refreshed.selectedId)
        assertEquals("", refreshed.draft)
        assertNull(refreshed.editingBaseTitle)
    }
}
