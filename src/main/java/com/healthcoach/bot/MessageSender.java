package com.healthcoach.bot;

import java.util.Set;

public interface MessageSender {
    void sendText(String chatId, String text);
    Set<String> getRegisteredChatIds();
}
