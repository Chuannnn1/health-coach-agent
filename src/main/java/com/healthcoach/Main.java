package com.healthcoach;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.healthcoach.agent.AgentCore;
import com.healthcoach.agent.ConversationStore;
import com.healthcoach.agent.PatchExecutor;
import com.healthcoach.agent.PromptBuilder;
import com.healthcoach.bot.SlashRouter;
import com.healthcoach.bot.LineWebhookServer;
import com.healthcoach.bot.TelegramBot;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.PreferencesStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.scheduler.CronScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Path configPath = Paths.get(args.length > 0 ? args[0] : "config.json");
        if (!Files.exists(configPath)) {
            System.err.println("Config file not found: " + configPath.toAbsolutePath());
            System.err.println("Run setup.ps1 / setup.sh, or copy config.example.json → config.json and fill in credentials.");
            System.exit(1);
        }

        String configText = Files.readString(configPath, StandardCharsets.UTF_8);
        JsonObject config = new Gson().fromJson(configText, JsonObject.class);

        String channel = config.has("channel") ? config.get("channel").getAsString() : "telegram";
        JsonObject llmCfg = config.getAsJsonObject("llm");
        JsonObject legacyScheduleCfg = config.has("schedule") ? config.getAsJsonObject("schedule") : null;
        String dataDirStr = config.get("dataDir").getAsString();

        String apiKey = llmCfg.get("apiKey").getAsString();
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("LLM apiKey is empty in " + configPath + ". Fill it in and re-run.");
            System.exit(1);
        }

        Path dataDir = Paths.get(dataDirStr).toAbsolutePath();
        Files.createDirectories(dataDir);

        MemoryStore memoryStore = new MemoryStore(dataDir);
        SkillManager skillManager = new SkillManager(dataDir);
        DailyLogStore dailyLogStore = new DailyLogStore(dataDir);
        PreferencesStore preferencesStore = new PreferencesStore(dataDir);
        preferencesStore.migrateFromLegacyConfigIfNeeded(legacyScheduleCfg);

        PromptBuilder promptBuilder = new PromptBuilder(memoryStore, skillManager, dailyLogStore);
        AgentCore agentCore = new AgentCore(promptBuilder, llmCfg, preferencesStore);
        ConversationStore conversationStore = new ConversationStore(20);

        CronScheduler[] schedulerHolder = new CronScheduler[1];
        Runnable reschedule = () -> {
            if (schedulerHolder[0] != null) schedulerHolder[0].reschedule();
        };

        PatchExecutor patchExecutor = new PatchExecutor(memoryStore, skillManager, dailyLogStore,
                preferencesStore, reschedule);
        SlashRouter slashRouter = new SlashRouter(memoryStore, skillManager, dailyLogStore,
                conversationStore, preferencesStore, reschedule);

        if ("line".equals(channel)) {
            JsonObject lineCfg = config.getAsJsonObject("line");
            if (lineCfg == null) {
                System.err.println("channel=line but no 'line' section in " + configPath);
                System.exit(1);
            }
            String lineSecret = lineCfg.get("channelSecret").getAsString();
            String lineToken = lineCfg.get("channelAccessToken").getAsString();
            int linePort = lineCfg.has("webhookPort") ? lineCfg.get("webhookPort").getAsInt() : 8080;

            LineWebhookServer lineServer = new LineWebhookServer(
                    lineSecret, lineToken, linePort,
                    agentCore, patchExecutor, conversationStore);
            lineServer.start();

            CronScheduler scheduler = new CronScheduler(lineServer, dailyLogStore, memoryStore, preferencesStore);
            schedulerHolder[0] = scheduler;
            scheduler.start();

            log.info("Health Coach Agent 已啟動！(LINE webhook on port {})", linePort);
            System.out.println("Health Coach Agent 已啟動！(LINE webhook on port " + linePort + ")");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                scheduler.stop();
                lineServer.stop();
            }, "shutdown-hook"));
        } else {
            JsonObject telegramCfg = config.getAsJsonObject("telegram");
            if (telegramCfg == null) {
                System.err.println("channel=telegram but no 'telegram' section in " + configPath);
                System.exit(1);
            }
            String botToken = telegramCfg.get("botToken").getAsString();
            if (botToken == null || botToken.isBlank()) {
                System.err.println("Telegram botToken is empty in " + configPath);
                System.exit(1);
            }

            TelegramBot bot = new TelegramBot(botToken, agentCore, patchExecutor, slashRouter, conversationStore);
            CronScheduler scheduler = new CronScheduler(bot, dailyLogStore, memoryStore, preferencesStore);
            schedulerHolder[0] = scheduler;

            TelegramBotsLongPollingApplication botsApp = new TelegramBotsLongPollingApplication();
            botsApp.registerBot(botToken, bot);
            bot.registerDefaultCommands();
            scheduler.start();

            log.info("Health Coach Agent 已啟動！(Telegram)");
            System.out.println("Health Coach Agent 已啟動！(Telegram)");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down...");
                try {
                    scheduler.stop();
                    bot.shutdown();
                    botsApp.close();
                } catch (Exception e) {
                    log.warn("Shutdown error: {}", e.getMessage());
                }
            }, "shutdown-hook"));
        }
    }
}
