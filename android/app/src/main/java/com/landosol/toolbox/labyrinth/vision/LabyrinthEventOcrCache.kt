package com.landosol.toolbox.labyrinth.vision

/** Called under the resolver's lock. Request identity is separate from visual similarity. */
internal class LabyrinthEventOcrCache {
    data class Key(val fingerprint: Long, val rect: EntryPixelRect)
    private data class Request(val id: Long, val key: Key, val startedAt: Long)
    private data class Result(val request: Request, val text: String, val completedAt: Long)

    private var sequence = 0L
    private var pending: Request? = null
    private var cached: Result? = null

    fun read(key: Key, now: Long): String? = cached?.takeIf {
        now - it.completedAt in 0 until CACHE_LIFETIME_MILLIS &&
            it.request.key.rect == key.rect &&
            java.lang.Long.bitCount(it.request.key.fingerprint xor key.fingerprint) <= 8
    }?.text

    fun begin(key: Key, now: Long): Long? {
        if (pending?.let { now - it.startedAt in 0 until REQUEST_TIMEOUT_MILLIS } == true) return null
        return (++sequence).also { pending = Request(it, key, now) }
    }

    fun complete(id: Long, text: String?, now: Long) {
        val request = pending?.takeIf { it.id == id } ?: return
        pending = null
        cached = text?.trim()?.takeIf { it.isNotEmpty() }?.let { Result(request, it, now) }
    }

    fun clear() {
        pending = null
        cached = null
    }

    private companion object {
        const val CACHE_LIFETIME_MILLIS = 5_000L
        const val REQUEST_TIMEOUT_MILLIS = 5_000L
    }
}
