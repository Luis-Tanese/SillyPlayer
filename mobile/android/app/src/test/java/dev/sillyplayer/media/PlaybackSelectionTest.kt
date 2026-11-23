package dev.sillyplayer.media

import dev.sillyplayer.data.Track
import dev.sillyplayer.data.QueueEntry
import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTest {
    private val a = Track(id="a", uri="file:///a")
    private val b = Track(id="b", uri="file:///b")
    @Test fun selectionsRespectOccurrencesAndFilteredIndices() {
        val rows=listOf(a.copy(available=false),a,b,a)
        assertEquals(1,playableSelection(rows,b,2)!!.second)
        assertEquals(2,playableSelection(rows,a,3)!!.second)
        assertEquals("b",playableSelection(rows,b,3)!!.first[playableSelection(rows,b,3)!!.second].id)
        assertNull(playableSelection(rows,a,0))
        assertNull(playableSelection(rows,b.copy(uri=""),2))
        assertNull(playableSelection(rows,b.copy(deleted=true),2))
        assertNull(playableSelection(listOf(a),b,0))
    }
    @Test fun rapidSelectionLargePlaylistAndTransportModesKeepRequestedItem() {
        val rows=List(10000) { if(it%2==0) a else b }
        for(index in listOf(1,9998,0,5001,5001,9999)) {
            val selection=playableSelection(rows,rows[index],index)!!
            assertEquals(index,selection.second)
            val entries=selection.first.mapIndexed { i,t -> QueueEntry(id="occurrence-$i",track=t) }
            for(mode in 0..2) {
                val order=upcomingPlaybackQueue(entries,index,mode,entries.map { it.id }.reversed())
                assertEquals("occurrence-$index",order.first().id)
                assertEquals(10000,order.map { it.id }.distinct().size)
            }
        }
    }
}
