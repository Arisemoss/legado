package io.legado.app.ai

import io.legado.app.ai.model.ChatMessage

/**
 * 对话上下文管理，维护消息历史和控制 Token 用量
 */
object ConversationManager {

    private const val MAX_HISTORY_TOKENS = 8000
    private const val AVG_CHARS_PER_TOKEN = 4
    private const val MAX_CONVERSATIONS = 20

    data class Conversation(
        val id: String = java.util.UUID.randomUUID().toString(),
        val systemPrompt: String = "",
        val messages: MutableList<ChatMessage> = mutableListOf(),
        var maxTokens: Int = MAX_HISTORY_TOKENS,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val conversations = LinkedHashMap<String, Conversation>()

    fun createConversation(systemPrompt: String, maxTokens: Int = MAX_HISTORY_TOKENS): Conversation {
        // 自动清理：超过最大对话数时移除最旧的
        if (conversations.size >= MAX_CONVERSATIONS) {
            val oldestKey = conversations.keys.firstOrNull()
            if (oldestKey != null) {
                conversations.remove(oldestKey)
            }
        }
        val conv = Conversation(
            systemPrompt = systemPrompt,
            maxTokens = maxTokens
        )
        conversations[conv.id] = conv
        return conv
    }

    fun getConversation(id: String): Conversation? = conversations[id]

    fun addMessage(convId: String, message: ChatMessage) {
        val conv = conversations[convId] ?: return
        conv.messages.add(message)
        trimHistory(conv)
    }

    fun buildRequestMessages(convId: String): List<ChatMessage> {
        val conv = conversations[convId] ?: return emptyList()
        val messages = mutableListOf<ChatMessage>()

        // System prompt
        if (conv.systemPrompt.isNotBlank()) {
            messages.add(ChatMessage(role = "system", content = conv.systemPrompt))
        }

        // History
        messages.addAll(conv.messages)

        return messages
    }

    fun removeConversation(convId: String) {
        conversations.remove(convId)
    }

    fun clearAll() {
        conversations.clear()
    }

    /**
     * 清理超过指定存活时间的对话（毫秒）
     */
    fun clearExpired(maxAgeMs: Long = 30 * 60 * 1000) {
        val now = System.currentTimeMillis()
        val expired = conversations.filter { (now - it.value.createdAt) > maxAgeMs }.keys
        expired.forEach { conversations.remove(it) }
    }

    private fun trimHistory(conv: Conversation) {
        var totalChars = conv.messages.sumOf { it.content?.length ?: 0 }
        while (totalChars > conv.maxTokens * AVG_CHARS_PER_TOKEN && conv.messages.size > 1) {
            val removed = conv.messages.removeFirstOrNull() ?: break
            totalChars -= removed.content?.length ?: 0
        }
    }
}