package com.sendro.android.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The in-RAM inbox for PROTOCOL.md §11 text messages.
 *
 * EPHEMERALITY CONTRACT — read before touching this file:
 *  - Messages live in this StateFlow and nowhere else. No DataStore, no
 *    SharedPreferences, no file, no HistoryStore entry, no Log call.
 *  - A message's text is never put in a notification body — [Notifier] posts
 *    "Sent you text" and the sender's name, nothing more.
 *  - [dismiss] drops the last reference; the memory goes with it.
 *  - Process death takes the whole inbox with it (there is no restore path,
 *    deliberately: §11.1 delivery is at-most-once).
 */
class MessageCenter {

    private val _inbox = MutableStateFlow<List<SendroMessage>>(emptyList())

    /** Oldest first; the UI stacks the newest on top. */
    val inbox: StateFlow<List<SendroMessage>> = _inbox.asStateFlow()

    /**
     * Ingest a batch straight off the outbox long poll. Deduped by messageId:
     * delivery is at-most-once, but a resend must never double-stack a card.
     */
    @Synchronized
    fun receive(messages: List<SendroMessage>) {
        if (messages.isEmpty()) return
        val current = _inbox.value
        val known = current.mapTo(HashSet()) { it.messageId }
        val fresh = messages.filter { known.add(it.messageId) }
        if (fresh.isEmpty()) return
        // §11: "at most 20 undelivered messages; pushing past that drops the
        // oldest."
        _inbox.value = (current + fresh).takeLast(CAPACITY)
    }

    /** Permanently forget one message. */
    @Synchronized
    fun dismiss(messageId: String) {
        _inbox.value = _inbox.value.filterNot { it.messageId == messageId }
    }

    /** Permanently forget everything. */
    @Synchronized
    fun clear() {
        _inbox.value = emptyList()
    }

    companion object {
        const val CAPACITY = 20
    }
}
