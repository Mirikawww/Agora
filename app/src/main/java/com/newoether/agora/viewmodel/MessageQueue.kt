package com.newoether.agora.viewmodel

import com.newoether.agora.model.SelectedAttachment
import java.util.UUID

/**
 * A user message waiting to be sent after the current generation for a conversation finishes.
 * Attachments are kept as [SelectedAttachment] snapshots so re-send can reuse processed frames.
 */
data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val text: String,
    val attachments: List<SelectedAttachment> = emptyList(),
)

/**
 * Per-conversation send queue. Snapshots are persisted by [ChatViewModel] via DataStore.
 */
class MessageQueueStore {
    private val lock = Any()
    private var queues: Map<String, List<QueuedMessage>> = emptyMap()

    fun snapshot(): Map<String, List<QueuedMessage>> = synchronized(lock) { queues }

    fun replaceAll(next: Map<String, List<QueuedMessage>>): Map<String, List<QueuedMessage>> =
        synchronized(lock) {
            queues = next
            queues
        }

    fun forConversation(conversationId: String): List<QueuedMessage> =
        synchronized(lock) { queues[conversationId].orEmpty() }

    fun enqueue(item: QueuedMessage): Map<String, List<QueuedMessage>> = synchronized(lock) {
        val next = queues[item.conversationId].orEmpty() + item
        queues = queues + (item.conversationId to next)
        queues
    }

    fun remove(conversationId: String, itemId: String): Map<String, List<QueuedMessage>> =
        synchronized(lock) {
            val next = queues[conversationId].orEmpty().filterNot { it.id == itemId }
            queues = if (next.isEmpty()) queues - conversationId else queues + (conversationId to next)
            queues
        }

    fun update(conversationId: String, itemId: String, text: String, attachments: List<SelectedAttachment>): Map<String, List<QueuedMessage>> =
        synchronized(lock) {
            val list = queues[conversationId].orEmpty()
            val next = list.map {
                if (it.id == itemId) it.copy(text = text, attachments = attachments) else it
            }
            queues = queues + (conversationId to next)
            queues
        }

    fun popFirst(conversationId: String): Pair<QueuedMessage?, Map<String, List<QueuedMessage>>> =
        synchronized(lock) {
            val list = queues[conversationId].orEmpty()
            if (list.isEmpty()) return@synchronized null to queues
            val head = list.first()
            val rest = list.drop(1)
            queues = if (rest.isEmpty()) queues - conversationId else queues + (conversationId to rest)
            head to queues
        }

    fun clearConversation(conversationId: String): Map<String, List<QueuedMessage>> =
        synchronized(lock) {
            queues = queues - conversationId
            queues
        }
}
