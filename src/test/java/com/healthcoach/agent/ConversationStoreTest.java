package com.healthcoach.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {

    @Test
    void appendsUserAndAssistantInOrder() {
        ConversationStore s = new ConversationStore();
        s.appendUser("c1", "hi");
        s.appendAssistant("c1", "hello");
        List<ConversationStore.Message> hist = s.recent("c1");
        assertEquals(2, hist.size());
        assertEquals("user", hist.get(0).role());
        assertEquals("hi", hist.get(0).content());
        assertEquals("assistant", hist.get(1).role());
    }

    @Test
    void recentEmptyForUnknownChat() {
        ConversationStore s = new ConversationStore();
        assertTrue(s.recent("nobody").isEmpty());
    }

    @Test
    void ringBufferEvictsOldestOnceCapacityExceeded() {
        ConversationStore s = new ConversationStore(4);
        s.appendUser("c1", "1");
        s.appendAssistant("c1", "2");
        s.appendUser("c1", "3");
        s.appendAssistant("c1", "4");
        s.appendUser("c1", "5");
        List<ConversationStore.Message> hist = s.recent("c1");
        assertEquals(4, hist.size());
        assertEquals("2", hist.get(0).content());
        assertEquals("5", hist.get(3).content());
    }

    @Test
    void clearRemovesHistory() {
        ConversationStore s = new ConversationStore();
        s.appendUser("c1", "x");
        s.clear("c1");
        assertTrue(s.recent("c1").isEmpty());
    }

    @Test
    void chatsAreIsolatedByChatId() {
        ConversationStore s = new ConversationStore();
        s.appendUser("a", "from-a");
        s.appendUser("b", "from-b");
        assertEquals(1, s.recent("a").size());
        assertEquals("from-a", s.recent("a").get(0).content());
        assertEquals("from-b", s.recent("b").get(0).content());
    }
}
