package com.healthcoach.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.regex.Pattern;

public class StreamingConsumer {
    private static final Logger log = LoggerFactory.getLogger(StreamingConsumer.class);
    private static final long EDIT_INTERVAL_MS = 800;
    private static final int BUFFER_THRESHOLD = 30;
    private static final String CURSOR = " ◌";
    private static final Pattern PATCH_BLOCK = Pattern.compile("<PATCH>.*?</PATCH>", Pattern.DOTALL);
    private static final Pattern LOG_BLOCK = Pattern.compile("<LOG>.*?</LOG>", Pattern.DOTALL);

    private final TelegramClient client;
    private final String chatId;
    private Integer messageId;
    private final StringBuilder accumulated = new StringBuilder();
    private long lastEditTime = 0;

    public StreamingConsumer(TelegramClient client, String chatId) {
        this.client = client;
        this.chatId = chatId;
    }

    public synchronized void onDelta(String text) {
        accumulated.append(text);
        long now = System.currentTimeMillis();
        if (messageId == null
                || accumulated.length() >= BUFFER_THRESHOLD && now - lastEditTime >= EDIT_INTERVAL_MS
                || now - lastEditTime >= EDIT_INTERVAL_MS * 2) {
            flush(false);
        }
    }

    public synchronized void finish() {
        flush(true);
    }

    public synchronized void finishCancelled() {
        String text = stripDisplayTags(accumulated.toString()).trim();
        if (text.isEmpty()) text = "...";
        text += "\n（已中斷）";
        try {
            if (messageId == null) {
                Message sent = (Message) client.execute(SendMessage.builder()
                        .chatId(chatId).text(text).build());
                if (sent != null) messageId = sent.getMessageId();
            } else {
                client.execute(EditMessageText.builder()
                        .chatId(chatId).messageId(messageId)
                        .text(text).build());
            }
        } catch (TelegramApiException e) {
            log.debug("finishCancelled edit failed: {}", e.getMessage());
        }
    }

    public synchronized void editFinal(String text) {
        if (messageId == null || text == null || text.isEmpty()) return;
        try {
            client.execute(EditMessageText.builder()
                    .chatId(chatId).messageId(messageId)
                    .text(text).build());
        } catch (TelegramApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("message is not modified")) return;
            log.warn("editFinal failed: {}", e.getMessage());
        }
    }

    public String getAccumulated() {
        return accumulated.toString();
    }

    private static String stripDisplayTags(String text) {
        text = PATCH_BLOCK.matcher(text).replaceAll("");
        text = LOG_BLOCK.matcher(text).replaceAll("");
        int patchOpen = text.lastIndexOf("<PATCH>");
        if (patchOpen >= 0 && text.indexOf("</PATCH>", patchOpen) < 0) {
            text = text.substring(0, patchOpen);
        }
        int logOpen = text.lastIndexOf("<LOG>");
        if (logOpen >= 0 && text.indexOf("</LOG>", logOpen) < 0) {
            text = text.substring(0, logOpen);
        }
        return text;
    }

    private void flush(boolean isFinal) {
        String text = stripDisplayTags(accumulated.toString()).trim();
        if (text.isEmpty()) return;

        String display = isFinal ? text : text + CURSOR;

        try {
            if (messageId == null) {
                Message sent = (Message) client.execute(SendMessage.builder()
                        .chatId(chatId).text(display).build());
                if (sent != null) {
                    messageId = sent.getMessageId();
                }
            } else {
                client.execute(EditMessageText.builder()
                        .chatId(chatId).messageId(messageId)
                        .text(display).build());
            }
            lastEditTime = System.currentTimeMillis();
        } catch (TelegramApiException e) {
            if (e.getMessage() != null && e.getMessage().contains("429")) {
                // Flood control - back off
                lastEditTime = System.currentTimeMillis() + 2000;
                log.debug("Telegram flood control, backing off");
            } else if (e.getMessage() != null && e.getMessage().contains("message is not modified")) {
                // Content unchanged, ignore
            } else {
                log.warn("StreamingConsumer flush failed: {}", e.getMessage());
            }
        }
    }
}
