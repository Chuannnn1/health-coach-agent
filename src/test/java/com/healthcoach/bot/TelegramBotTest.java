package com.healthcoach.bot;

import com.google.gson.JsonObject;
import com.healthcoach.agent.AgentCore;
import com.healthcoach.agent.ConversationStore;
import com.healthcoach.agent.PatchExecutor;
import com.healthcoach.agent.PromptBuilder;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.SetMyProfilePhoto;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.business.SetBusinessAccountProfilePhoto;
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPaidMedia;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendSticker;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVideoNote;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.methods.stickers.AddStickerToSet;
import org.telegram.telegrambots.meta.api.methods.stickers.CreateNewStickerSet;
import org.telegram.telegrambots.meta.api.methods.stickers.ReplaceStickerInSet;
import org.telegram.telegrambots.meta.api.methods.stickers.SetStickerSetThumbnail;
import org.telegram.telegrambots.meta.api.methods.stickers.UploadStickerFile;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageMedia;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that TelegramBot wraps the LLM call with a typing-action keepalive:
 *   - sends typing immediately
 *   - refreshes typing every ~4s while the LLM is busy
 *   - cancels the keepalive once the LLM call returns or throws
 */
class TelegramBotTest {

    private static final String EMPTY_PROFILE_JSON = ""
            + "{\n"
            + "  \"name\": \"\", \"heightCm\": 0.0, \"weightKg\": 0.0, \"age\": 0,\n"
            + "  \"gender\": \"\", \"activityLevel\": \"\", \"goal\": \"\",\n"
            + "  \"bmr\": 0, \"tdee\": 0,\n"
            + "  \"targetCalories\": 0, \"targetProteinG\": 0,\n"
            + "  \"targetCarbsG\": 0, \"targetFatG\": 0,\n"
            + "  \"dietaryRestrictions\": [], \"notes\": \"\", \"updatedAt\": \"\"\n"
            + "}\n";
    private static final String EMPTY_MEMORY_JSON =
            "{\"entries\":[],\"maxEntries\":20,\"maxChars\":2200}";

    @TempDir
    Path tempDir;

    private TelegramBot bot;
    private RecordingTelegramClient client;
    private StubAgentCore agent;
    private StubPatchExecutor patcher;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("user_profile.json"), EMPTY_PROFILE_JSON, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("memory.json"), EMPTY_MEMORY_JSON, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("logs"));

        MemoryStore ms = new MemoryStore(tempDir);
        SkillManager sm = new SkillManager(tempDir);
        DailyLogStore ds = new DailyLogStore(tempDir);
        PromptBuilder pb = new PromptBuilder(ms, sm, ds);

        JsonObject cfg = new JsonObject();
        cfg.addProperty("apiKey", "fake-key");
        cfg.addProperty("baseUrl", "https://example.invalid/chat");
        cfg.addProperty("model", "test-model");
        cfg.addProperty("maxTokens", 100);
        cfg.addProperty("temperature", 0.5);

        client = new RecordingTelegramClient();
        agent = new StubAgentCore(pb, cfg);
        patcher = new StubPatchExecutor(ms, sm, ds);
        bot = new TelegramBot(client, agent, patcher) {};
    }

    @AfterEach
    void tearDown() {
        if (bot != null) bot.shutdown();
    }

    // ---------- helpers ----------

    /** Invokes the private processAgent(chatId, msg, recordHistory) on a daemon thread. */
    private Thread runProcessAgentAsync(String chatId, String msg) throws Exception {
        Method m = TelegramBot.class.getDeclaredMethod(
                "processAgent", String.class, String.class, boolean.class);
        m.setAccessible(true);
        Thread t = new Thread(() -> {
            try {
                m.invoke(bot, chatId, msg, false);
            } catch (Exception ignore) {
                // exceptions inside processAgent are already caught + logged by the bot
            }
        }, "test-processAgent");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------- 1) typing fires before reply ----------

    @Test
    void tTypingSentBeforeReply() throws Exception {
        agent.replyText = "hello";

        Thread t = runProcessAgentAsync("42", "hi coach");
        t.join(2_000);
        assertFalse(t.isAlive(), "processAgent should finish quickly when chat returns immediately");

        List<Object> ordered = client.calls();
        // Find first SendChatAction and first SendMessage
        int chatActionIdx = -1, sendMessageIdx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            Object o = ordered.get(i);
            if (chatActionIdx < 0 && o instanceof SendChatAction) chatActionIdx = i;
            if (sendMessageIdx < 0 && o instanceof SendMessage) sendMessageIdx = i;
        }
        assertTrue(chatActionIdx >= 0, "expected at least one SendChatAction");
        assertTrue(sendMessageIdx >= 0, "expected at least one SendMessage");
        assertTrue(chatActionIdx < sendMessageIdx,
                "typing action must be sent before the reply message");

        SendChatAction sca = (SendChatAction) ordered.get(chatActionIdx);
        assertEquals("42", sca.getChatId());
        assertEquals(ActionType.TYPING.toString(), sca.getAction(),
                "action wire value should be 'typing'");
    }

    // ---------- 2) initial typing fires even during slow LLM (keepalive replaced by streaming) ----------

    @Test
    void tTypingFiresBeforeSlowLlm() throws Exception {
        agent.replyText = "done";
        agent.sleepMs = 2_000;

        Thread t = runProcessAgentAsync("99", "long task");
        t.join(5_000);
        assertFalse(t.isAlive(), "processAgent should complete after slow chat returns");

        long typingCount = client.calls().stream().filter(o -> o instanceof SendChatAction).count();
        assertTrue(typingCount >= 1,
                "expected at least 1 initial SendChatAction before streaming begins, got " + typingCount);
    }

    // ---------- 3) keepalive cancelled when LLM fails ----------

    @Test
    void tTypingCancelledWhenLlmFails() throws Exception {
        agent.throwOnChat = new IOException("boom");

        Thread t = runProcessAgentAsync("7", "bad");
        t.join(2_000);
        assertFalse(t.isAlive(), "processAgent should finish quickly after exception");

        int initialTypingCount = (int) client.calls().stream().filter(o -> o instanceof SendChatAction).count();
        assertTrue(initialTypingCount >= 1,
                "initial typing action should have been sent before exception, got " + initialTypingCount);

        // Wait well past one keepalive interval (4s) to verify nothing else fires.
        TimeUnit.SECONDS.sleep(5);
        int afterTypingCount = (int) client.calls().stream().filter(o -> o instanceof SendChatAction).count();
        assertEquals(initialTypingCount, afterTypingCount,
                "no further SendChatAction should fire after processAgent's finally block ran");
    }

    // ===================================================================
    // Stubs
    // ===================================================================

    /** Stub AgentCore — replies with a canned string after an optional sleep, or throws. */
    static class StubAgentCore extends AgentCore {
        volatile String replyText = "ok";
        volatile long sleepMs = 0;
        volatile Exception throwOnChat = null;

        StubAgentCore(PromptBuilder pb, JsonObject cfg) {
            super(pb, cfg);
        }

        @Override
        public String chat(String userMessage, List<ConversationStore.Message> history)
                throws IOException, InterruptedException {
            if (sleepMs > 0) Thread.sleep(sleepMs);
            if (throwOnChat instanceof IOException io) throw io;
            if (throwOnChat instanceof RuntimeException re) throw re;
            return replyText;
        }
    }

    /** Stub PatchExecutor — bypasses parsing and just echoes rawReply as cleanText. */
    static class StubPatchExecutor extends PatchExecutor {
        StubPatchExecutor(MemoryStore ms, SkillManager sm, DailyLogStore ds) {
            super(ms, sm, ds);
        }
        @Override
        public ExecutionResult execute(String rawReply) {
            return new ExecutionResult(rawReply == null ? "" : rawReply, new java.util.ArrayList<>());
        }
    }

    /** TelegramClient stub that records every execute(...) call. */
    static class RecordingTelegramClient implements TelegramClient {
        private final List<Object> calls = new CopyOnWriteArrayList<>();
        private final AtomicInteger execCount = new AtomicInteger();

        List<Object> calls() { return calls; }
        int execCount() { return execCount.get(); }

        // ---- the only execute overload the bot actually uses ----
        @Override
        @SuppressWarnings("unchecked")
        public <T extends Serializable, M extends BotApiMethod<T>> T execute(M method) {
            calls.add(method);
            execCount.incrementAndGet();
            // SendChatAction returns Boolean; SendMessage returns Message. null is fine here.
            if (method instanceof SendChatAction) return (T) Boolean.TRUE;
            return null;
        }

        @Override
        public <T extends Serializable, M extends BotApiMethod<T>> CompletableFuture<T> executeAsync(M method) {
            return CompletableFuture.completedFuture(execute(method));
        }

        // ---- everything else: unsupported (we never call these) ----
        @Override public Message execute(SendDocument m) { return record(m); }
        @Override public Message execute(SendPhoto m) { return record(m); }
        @Override public Boolean execute(SetWebhook m) { calls.add(m); return Boolean.TRUE; }
        @Override public Message execute(SendVideo m) { return record(m); }
        @Override public Message execute(SendVideoNote m) { return record(m); }
        @Override public Message execute(SendSticker m) { return record(m); }
        @Override public Boolean execute(SetBusinessAccountProfilePhoto m) { calls.add(m); return Boolean.TRUE; }
        @Override public Boolean execute(SetMyProfilePhoto m) { calls.add(m); return Boolean.TRUE; }
        @Override public Message execute(SendAudio m) { return record(m); }
        @Override public Message execute(SendVoice m) { return record(m); }
        @Override public List<Message> execute(SendMediaGroup m) { calls.add(m); return List.of(); }
        @Override public List<Message> execute(SendPaidMedia m) { calls.add(m); return List.of(); }
        @Override public Boolean execute(SetChatPhoto m) { calls.add(m); return Boolean.TRUE; }
        @Override public Boolean execute(AddStickerToSet m) { calls.add(m); return Boolean.TRUE; }
        @Override public Boolean execute(ReplaceStickerInSet m) { calls.add(m); return Boolean.TRUE; }
        @Override public Boolean execute(SetStickerSetThumbnail m) { calls.add(m); return Boolean.TRUE; }
        @Override public Boolean execute(CreateNewStickerSet m) { calls.add(m); return Boolean.TRUE; }
        @Override public File execute(UploadStickerFile m) { calls.add(m); return null; }
        @Override public Serializable execute(EditMessageMedia m) { calls.add(m); return null; }
        @Override public Message execute(SendAnimation m) { return record(m); }

        @Override public java.io.File downloadFile(File f) { return null; }
        @Override public InputStream downloadFileAsStream(File f) { return null; }

        @Override public CompletableFuture<Message> executeAsync(SendDocument m) { return CompletableFuture.completedFuture(record(m)); }
        @Override public CompletableFuture<Message> executeAsync(SendPhoto m) { return CompletableFuture.completedFuture(record(m)); }
        @Override public CompletableFuture<Boolean> executeAsync(SetWebhook m) { calls.add(m); return CompletableFuture.completedFuture(Boolean.TRUE); }
        @Override public CompletableFuture<Message> executeAsync(SendVideo m) { return CompletableFuture.completedFuture(record(m)); }
        @Override public CompletableFuture<Message> executeAsync(SendVideoNote m) { return CompletableFuture.completedFuture(record(m)); }
        @Override public CompletableFuture<Message> executeAsync(SendSticker m) { return CompletableFuture.completedFuture(record(m)); }
        @Override public CompletableFuture<Message> executeAsync(SendAudio m) { return CompletableFuture.completedFuture(record(m)); }
        @Override public CompletableFuture<Message> executeAsync(SendVoice m) { return CompletableFuture.completedFuture(record(m)); }
        @Override public CompletableFuture<List<Message>> executeAsync(SendMediaGroup m) { calls.add(m); return CompletableFuture.completedFuture(List.of()); }
        @Override public CompletableFuture<List<Message>> executeAsync(SendPaidMedia m) { calls.add(m); return CompletableFuture.completedFuture(List.of()); }
        @Override public CompletableFuture<Boolean> executeAsync(SetChatPhoto m) { calls.add(m); return CompletableFuture.completedFuture(Boolean.TRUE); }
        @Override public CompletableFuture<Boolean> executeAsync(AddStickerToSet m) { calls.add(m); return CompletableFuture.completedFuture(Boolean.TRUE); }
        @Override public CompletableFuture<Boolean> executeAsync(ReplaceStickerInSet m) { calls.add(m); return CompletableFuture.completedFuture(Boolean.TRUE); }
        @Override public CompletableFuture<Boolean> executeAsync(SetStickerSetThumbnail m) { calls.add(m); return CompletableFuture.completedFuture(Boolean.TRUE); }
        @Override public CompletableFuture<Boolean> executeAsync(CreateNewStickerSet m) { calls.add(m); return CompletableFuture.completedFuture(Boolean.TRUE); }
        @Override public CompletableFuture<File> executeAsync(UploadStickerFile m) { calls.add(m); return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Serializable> executeAsync(EditMessageMedia m) { calls.add(m); return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Message> executeAsync(SendAnimation m) { return CompletableFuture.completedFuture(record(m)); }
        @Override public CompletableFuture<Boolean> executeAsync(SetBusinessAccountProfilePhoto m) { calls.add(m); return CompletableFuture.completedFuture(Boolean.TRUE); }
        @Override public CompletableFuture<Boolean> executeAsync(SetMyProfilePhoto m) { calls.add(m); return CompletableFuture.completedFuture(Boolean.TRUE); }
        @Override public CompletableFuture<java.io.File> downloadFileAsync(File f) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<InputStream> downloadFileAsStreamAsync(File f) { return CompletableFuture.completedFuture(null); }

        private Message record(Object m) { calls.add(m); return null; }
    }
}
