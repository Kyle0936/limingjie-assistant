package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.*
import org.junit.Test

class RelicOcrSchedulerTest {
    private var now = 0L
    private val scheduler = RelicOcrScheduler({ now })
    @Test fun `stable pixels have two independent reads then stop scanning`() {
        val first = requireNotNull(scheduler.request("a", 1))
        scheduler.complete(first, "4")
        assertEquals(first.id, scheduler.cached("a", 1)?.id)
        assertNull(scheduler.request("a", 1))
        now = 400
        val second = requireNotNull(scheduler.request("a", 1))
        assertNotEquals(first.id, second.id)
        scheduler.complete(second, "4")
        now = 100_000
        repeat(100) { assertNull(scheduler.request("a", 1)) }
        assertEquals(second.id, scheduler.cached("a", 1)?.id)
    }
    @Test fun `pixel change invalidates cache and allows fresh work`() {
        scheduler.complete(requireNotNull(scheduler.request("a", 1)), "1")
        now = 500
        assertNull(scheduler.cached("a", 2))
        assertNotNull(scheduler.request("a", 2))
    }
    @Test fun `late callback cannot overwrite changed viewport`() {
        val ticket = requireNotNull(scheduler.request("a", 1))
        scheduler.cached("a", 2)
        scheduler.complete(ticket, "old")
        assertNull(scheduler.cached("a", 2))
    }
    @Test fun `page reset cannot queue more bitmaps behind a stalled request`() {
        val ticket = requireNotNull(scheduler.request("a", 1))
        repeat(100) {
            scheduler.clear()
            now += 500
            assertNull(scheduler.request("a", 1))
        }
        scheduler.complete(ticket, "stale")
        assertNull(scheduler.cached("a", 1))
        assertNotNull(scheduler.request("a", 1))
    }
    @Test fun `other slots get first reading before a repeat verification`() {
        scheduler.complete(requireNotNull(scheduler.request("a", 1)), "1")
        scheduler.cached("b", 1)
        now = 500
        assertNull(scheduler.request("a", 1))
        assertNotNull(scheduler.request("b", 1))
    }
    @Test fun `failures use bounded retry budget too`() {
        repeat(2) {
            scheduler.complete(requireNotNull(scheduler.request("a", 1)), null)
            now += 500
        }
        assertNull(scheduler.request("a", 1))
    }
}
