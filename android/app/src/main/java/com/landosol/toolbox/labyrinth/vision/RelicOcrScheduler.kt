package com.landosol.toolbox.labyrinth.vision

/** Two independent reads per unchanged slot, then reuse. Invalidating a page does not release
 * the physical request: a slow engine must not accumulate abandoned crops across transitions. */
internal class RelicOcrScheduler(private val clock: () -> Long, private val spacingMillis: Long = 350,
    private val maximumAttempts: Int = 2) {
    data class Read(val text: String?, val id: Long)
    data class Ticket(val slot: String, val fingerprint: Long, val epoch: Long, val id: Long)
    private data class Slot(val fingerprint: Long, var attempts: Int = 0, var read: Read? = null)
    private val slots = mutableMapOf<String, Slot>()
    private var epoch = 0L
    private var sequence = 0L
    private var pending: Ticket? = null
    private var nextStart = Long.MIN_VALUE

    @Synchronized fun clear() { slots.clear(); epoch++ }

    @Synchronized fun cached(slot: String, fingerprint: Long): Read? {
        val state = slots[slot]
        if (state?.fingerprint == fingerprint) return state.read
        slots[slot] = Slot(fingerprint)
        return null
    }

    @Synchronized fun request(slot: String, fingerprint: Long): Ticket? {
        cached(slot, fingerprint)
        val state = slots.getValue(slot)
        if (pending != null || state.attempts >= maximumAttempts || clock() < nextStart) return null
        if (state.attempts > 0 && slots.values.any { it.attempts == 0 }) return null
        state.attempts++
        nextStart = clock() + spacingMillis
        return Ticket(slot, fingerprint, epoch, ++sequence).also { pending = it }
    }

    @Synchronized fun complete(ticket: Ticket, text: String?) {
        if (pending != ticket) return
        pending = null
        if (ticket.epoch != epoch) return
        slots[ticket.slot]?.takeIf { it.fingerprint == ticket.fingerprint }?.read = Read(text?.trim(), ticket.id)
    }
}
