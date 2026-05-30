package com.healthcoach.bot;

import com.healthcoach.agent.AgentCore;
import com.healthcoach.agent.ConversationStore;
import com.healthcoach.agent.PatchExecutor;
import com.healthcoach.model.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TelegramBot implements LongPollingSingleThreadUpdateConsumer, MessageSender {
    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    private static final String WELCOME =
            "嗨！我是你的健康教練 Coach。\n" +
            "請告訴我你的身高體重年齡性別，與你的目標（增肌/減脂/維持），" +
            "我會幫你算出每日熱量與三大營養素配比。\n" +
            "之後吃了什麼、做了什麼運動，跟我說一聲即可。\n" +
            "我會在用餐時間提醒你紀錄。\n\n" +
            "輸入 /help 查看所有指令。";
    private static final String ERROR_MSG = "教練暫時不在線，請稍後再試 🙏";
    private static final long DEBOUNCE_MS = 600;

    private final TelegramClient telegramClient;
    private final AgentCore agentCore;
    private final PatchExecutor patchExecutor;
    private final SlashRouter slashRouter;
    private final ConversationStore conversationStore;

    private final Set<String> registeredChatIds = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService debounceScheduler =
            Executors.newSingleThreadScheduledExecutor(daemon("tg-debounce"));
    private final ExecutorService workers =
            Executors.newCachedThreadPool(daemon("tg-worker"));
    private final ScheduledExecutorService typingScheduler =
            Executors.newScheduledThreadPool(2, daemon("tg-typing"));

    public TelegramBot(String botToken, AgentCore agentCore, PatchExecutor patchExecutor,
                       SlashRouter slashRouter, ConversationStore conversationStore) {
        this(new OkHttpTelegramClient(botToken), agentCore, patchExecutor, slashRouter, conversationStore);
    }

    protected TelegramBot(TelegramClient telegramClient, AgentCore agentCore, PatchExecutor patchExecutor,
                          SlashRouter slashRouter, ConversationStore conversationStore) {
        this.telegramClient = telegramClient;
        this.agentCore = agentCore;
        this.patchExecutor = patchExecutor;
        this.slashRouter = slashRouter;
        this.conversationStore = conversationStore;
    }

    /** Test-only constructor without slash/conversation deps. */
    protected TelegramBot(TelegramClient telegramClient, AgentCore agentCore, PatchExecutor patchExecutor) {
        this(telegramClient, agentCore, patchExecutor, null, null);
    }

    private static java.util.concurrent.ThreadFactory daemon(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix);
            t.setDaemon(true);
            return t;
        };
    }

    /** Handle one incoming Telegram update — slash command, debounced chat, or no-op. */
    @Override
    public void consume(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) return;
        String chatId = String.valueOf(update.getMessage().getChatId());
        String text = update.getMessage().getText();

        try {
            if ("/start".equals(text.trim())) {
                registeredChatIds.add(chatId);
                sendText(chatId, WELCOME);
                return;
            }

            if (slashRouter != null && text.startsWith("/")) {
                SlashRouter.Action action = slashRouter.route(chatId, text);
                if (action instanceof SlashRouter.Action.Reply r) {
                    sendText(chatId, r.text());
                    return;
                }
                if (action instanceof SlashRouter.Action.DelegateToAgent d) {
                    enqueueAgentCall(chatId, d.syntheticUserMessage());
                    return;
                }
                // NotHandled → fall through to normal agent path
            }

            enqueueChatMessage(chatId, text);
        } catch (Exception e) {
            log.warn("consume failed: {}", e.getMessage(), e);
            sendText(chatId, ERROR_MSG);
        }
    }

    private void enqueueChatMessage(String chatId, String text) {
        ChatSession s = sessions.computeIfAbsent(chatId, k -> new ChatSession());
        synchronized (s.lock) {
            s.pending.add(text);
            if (s.debounce != null) s.debounce.cancel(false);
            s.debounce = debounceScheduler.schedule(
                    () -> flushSession(chatId), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushSession(String chatId) {
        ChatSession s = sessions.get(chatId);
        if (s == null) return;
        String combined;
        synchronized (s.lock) {
            if (s.pending.isEmpty()) return;
            combined = String.join("\n", s.pending);
            s.pending.clear();
            s.debounce = null;
        }
        workers.submit(() -> processAgent(chatId, combined, true));
    }

    private void enqueueAgentCall(String chatId, String syntheticMessage) {
        workers.submit(() -> processAgent(chatId, syntheticMessage, false));
    }

    private void processAgent(String chatId, String userMessage, boolean recordHistory) {
        // Show typing immediately so user gets instant feedback
        sendTypingAction(chatId);
        // Refresh typing every 4s (Telegram expires it at 5s) until cancelled
        ScheduledFuture<?> typingTask = typingScheduler.scheduleAtFixedRate(
                () -> sendTypingAction(chatId), 4, 4, TimeUnit.SECONDS);
        try {
            List<ConversationStore.Message> history = conversationStore != null
                    ? conversationStore.recent(chatId)
                    : List.of();
            String raw = agentCore.chat(userMessage, history);
            ExecutionResult result = patchExecutor.execute(raw);
            if (recordHistory && conversationStore != null) {
                conversationStore.appendUser(chatId, userMessage);
                conversationStore.appendAssistant(chatId, result.cleanText);
            }
            sendText(chatId, result.cleanText);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("agent call failed: {}", e.getMessage(), e);
            sendText(chatId, ERROR_MSG);
        } finally {
            typingTask.cancel(false);
        }
    }

    /** Send a single typing action; ignore failure silently. */
    private void sendTypingAction(String chatId) {
        try {
            telegramClient.execute(SendChatAction.builder()
                    .chatId(chatId)
                    .action(ActionType.TYPING.toString())
                    .build());
        } catch (TelegramApiException e) {
            log.debug("sendChatAction failed: {}", e.getMessage());
        }
    }

    /** Send a text message to a chat. Errors are logged, not thrown. */
    public void sendText(String chatId, String text) {
        if (text == null || text.isEmpty()) return;
        try {
            telegramClient.execute(SendMessage.builder().chatId(chatId).text(text).build());
        } catch (TelegramApiException e) {
            log.warn("sendText failed: {}", e.getMessage());
        }
    }

    /** Return an unmodifiable view of chat IDs that have run /start. */
    public Set<String> getRegisteredChatIds() {
        return Collections.unmodifiableSet(registeredChatIds);
    }

    /** Shut down scheduler and worker threads. */
    public void shutdown() {
        debounceScheduler.shutdownNow();
        workers.shutdownNow();
        typingScheduler.shutdownNow();
    }

    /** Register the default slash command menu with Telegram (shows in the / button). */
    public void registerDefaultCommands() {
        List<BotCommand> cmds = List.of(
                new BotCommand("start", "啟動 Coach 並訂閱提醒"),
                new BotCommand("new", "開始新對話，清空最近上下文"),
                new BotCommand("profile", "查看你的個人資料"),
                new BotCommand("today", "查看今日紀錄與剩餘熱量"),
                new BotCommand("memory", "查看 Coach 記得的長期事實"),
                new BotCommand("skills", "列出知識模組"),
                new BotCommand("skill", "查看某個知識模組的內容"),
                new BotCommand("reminders", "看 / 改用餐 & 訓練提醒"),
                new BotCommand("effort", "設定模型 reasoning effort"),
                new BotCommand("analyze", "分析今日狀況並建議下一餐"),
                new BotCommand("suggest", "根據偏好推薦餐點"),
                new BotCommand("chart", "本週飲食趨勢表"),
                new BotCommand("help", "顯示所有指令")
        );
        try {
            telegramClient.execute(new SetMyCommands(cmds));
            log.info("Registered {} slash commands.", cmds.size());
        } catch (TelegramApiException e) {
            log.warn("setMyCommands failed: {}", e.getMessage());
        }
    }

    private static class ChatSession {
        final Object lock = new Object();
        final List<String> pending = new ArrayList<>();
        ScheduledFuture<?> debounce;
    }
}
