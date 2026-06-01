package com.healthcoach.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

    public record Message(String role, String content) {}

    private static final Type MSG_LIST_TYPE = new TypeToken<List<Message>>() {}.getType();

    private final int maxMessages;
    private final Map<String, Deque<Message>> buffers = new ConcurrentHashMap<>();
    private final Path conversationsDir; // nullable — null means in-memory only
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** In-memory only (tests, legacy). */
    public ConversationStore() {
        this(20);
    }

    /** In-memory only with custom capacity. */
    public ConversationStore(int maxMessages) {
        this.maxMessages = maxMessages;
        this.conversationsDir = null;
    }

    /** Persistent store: saves/loads conversations under dataDir/conversations/. */
    public ConversationStore(int maxMessages, Path dataDir) {
        this.maxMessages = maxMessages;
        this.conversationsDir = dataDir.resolve("conversations");
        try {
            Files.createDirectories(conversationsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        loadAll();
    }

    public void appendUser(String chatId, String content) {
        append(chatId, new Message("user", content));
    }

    public void appendAssistant(String chatId, String content) {
        append(chatId, new Message("assistant", content));
    }

    private void append(String chatId, Message msg) {
        Deque<Message> buf = buffers.computeIfAbsent(chatId, k -> new ArrayDeque<>());
        synchronized (buf) {
            buf.addLast(msg);
            while (buf.size() > maxMessages) buf.pollFirst();
        }
        persist(chatId);
    }

    public List<Message> recent(String chatId) {
        Deque<Message> buf = buffers.get(chatId);
        if (buf == null) return List.of();
        synchronized (buf) {
            return new ArrayList<>(buf);
        }
    }

    public void clear(String chatId) {
        Deque<Message> buf = buffers.get(chatId);
        if (buf != null) {
            synchronized (buf) {
                buf.clear();
            }
        }
        if (conversationsDir != null) {
            try {
                Files.deleteIfExists(fileFor(chatId));
            } catch (IOException e) {
                log.warn("failed to delete conversation file for {}: {}", chatId, e.getMessage());
            }
        }
    }

    public int size(String chatId) {
        Deque<Message> buf = buffers.get(chatId);
        return buf == null ? 0 : buf.size();
    }

    private void persist(String chatId) {
        if (conversationsDir == null) return;
        List<Message> snapshot = recent(chatId);
        try (BufferedWriter w = Files.newBufferedWriter(fileFor(chatId), StandardCharsets.UTF_8)) {
            gson.toJson(snapshot, w);
        } catch (IOException e) {
            log.warn("failed to persist conversation for {}: {}", chatId, e.getMessage());
        }
    }

    private void loadAll() {
        if (conversationsDir == null || !Files.isDirectory(conversationsDir)) return;
        try (Stream<Path> files = Files.list(conversationsDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::loadFile);
        } catch (IOException e) {
            log.warn("failed to scan conversations dir: {}", e.getMessage());
        }
    }

    private void loadFile(Path file) {
        String filename = file.getFileName().toString();
        String chatId = filename.substring(0, filename.length() - ".json".length());
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Message> messages = gson.fromJson(r, MSG_LIST_TYPE);
            if (messages == null || messages.isEmpty()) return;
            Deque<Message> buf = new ArrayDeque<>();
            for (Message m : messages) {
                buf.addLast(m);
                while (buf.size() > maxMessages) buf.pollFirst();
            }
            buffers.put(chatId, buf);
            log.info("restored {} messages for chatId={}", buf.size(), chatId);
        } catch (Exception e) {
            log.warn("failed to load conversation {}: {}", file, e.getMessage());
        }
    }

    private Path fileFor(String chatId) {
        return conversationsDir.resolve(chatId + ".json");
    }
}
