package com.healthcoach.bot;

import com.healthcoach.agent.AgentCore;
import com.healthcoach.agent.PatchExecutor;
import com.healthcoach.model.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class TelegramBot implements LongPollingSingleThreadUpdateConsumer {
    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);
    private static final String WELCOME =
            "嗨！我是你的健康教練 Coach。\n" +
            "請告訴我你的身高體重年齡性別，與你的目標（增肌/減脂/維持），" +
            "我會幫你算出每日熱量與三大營養素配比。\n" +
            "之後吃了什麼、做了什麼運動，跟我說一聲即可。\n" +
            "我會在用餐時間提醒你紀錄。";
    private static final String ERROR_MSG = "教練暫時不在線，請稍後再試 🙏";

    private final TelegramClient telegramClient;
    private final AgentCore agentCore;
    private final PatchExecutor patchExecutor;
    private final Set<String> registeredChatIds = Collections.synchronizedSet(new HashSet<>());

    public TelegramBot(String botToken, AgentCore agentCore, PatchExecutor patchExecutor) {
        this(new OkHttpTelegramClient(botToken), agentCore, patchExecutor);
    }

    TelegramBot(TelegramClient telegramClient, AgentCore agentCore, PatchExecutor patchExecutor) {
        this.telegramClient = telegramClient;
        this.agentCore = agentCore;
        this.patchExecutor = patchExecutor;
    }

    /** Handle one incoming Telegram update — dispatches to /start or the agent pipeline. */
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
            String raw = agentCore.chat(text);
            ExecutionResult result = patchExecutor.execute(raw);
            sendText(chatId, result.cleanText);
        } catch (Exception e) {
            log.warn("consume failed: {}", e.getMessage(), e);
            sendText(chatId, ERROR_MSG);
        }
    }

    /** Send a text message to a chat. Errors are logged, not thrown. */
    public void sendText(String chatId, String text) {
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
}
