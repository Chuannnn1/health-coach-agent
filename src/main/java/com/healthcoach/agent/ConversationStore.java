package com.healthcoach.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConversationStore {

    public record Message(String role, String content) {}

    private final int maxMessages;
    private final Map<String, Deque<Message>> buffers = new ConcurrentHashMap<>();

    public ConversationStore() {
        this(20);
    }

    public ConversationStore(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    /** Append a user message to the chat's ring buffer. */
    public void appendUser(String chatId, String content) {
        append(chatId, new Message("user", content));
    }

    /** Append an assistant message to the chat's ring buffer. */
    public void appendAssistant(String chatId, String content) {
        append(chatId, new Message("assistant", content));
    }

    private void append(String chatId, Message msg) {
        Deque<Message> buf = buffers.computeIfAbsent(chatId, k -> new ArrayDeque<>());
        synchronized (buf) {
            buf.addLast(msg);
            while (buf.size() > maxMessages) buf.pollFirst();
        }
    }

    /** Return a snapshot of recent messages (oldest first). Empty if no history. */
    public List<Message> recent(String chatId) {
        Deque<Message> buf = buffers.get(chatId);
        if (buf == null) return List.of();
        synchronized (buf) {
            return new ArrayList<>(buf);
        }
    }

    /** Clear all conversation history for a chat. */
    public void clear(String chatId) {
        Deque<Message> buf = buffers.get(chatId);
        if (buf != null) {
            synchronized (buf) {
                buf.clear();
            }
        }
    }

    /** Total turn count (one turn = one stored message) for diagnostics. */
    public int size(String chatId) {
        Deque<Message> buf = buffers.get(chatId);
        return buf == null ? 0 : buf.size();
    }
}
