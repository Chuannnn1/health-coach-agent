package com.healthcoach;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.healthcoach.agent.AgentCore;
import com.healthcoach.agent.PatchExecutor;
import com.healthcoach.agent.PromptBuilder;
import com.healthcoach.bot.TelegramBot;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
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
            System.err.println("Run ./setup.sh first, or copy config.json template into place.");
            System.exit(1);
        }

        String configText = Files.readString(configPath, StandardCharsets.UTF_8);
        JsonObject config = new Gson().fromJson(configText, JsonObject.class);

        JsonObject telegramCfg = config.getAsJsonObject("telegram");
        JsonObject llmCfg = config.getAsJsonObject("llm");
        JsonObject scheduleCfg = config.getAsJsonObject("schedule");
        String dataDirStr = config.get("dataDir").getAsString();
        String botToken = telegramCfg.get("botToken").getAsString();

        if (botToken == null || botToken.isBlank()) {
            System.err.println("Telegram botToken is empty in " + configPath + ". Fill it in and re-run.");
            System.exit(1);
        }
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
        PromptBuilder promptBuilder = new PromptBuilder(memoryStore, skillManager, dailyLogStore);
        AgentCore agentCore = new AgentCore(promptBuilder, llmCfg);
        PatchExecutor patchExecutor = new PatchExecutor(memoryStore, skillManager, dailyLogStore);
        TelegramBot bot = new TelegramBot(botToken, agentCore, patchExecutor);
        CronScheduler scheduler = new CronScheduler(bot, dailyLogStore, memoryStore, scheduleCfg);

        TelegramBotsLongPollingApplication botsApp = new TelegramBotsLongPollingApplication();
        botsApp.registerBot(botToken, bot);
        scheduler.start();

        log.info("Health Coach Agent 已啟動！");
        System.out.println("Health Coach Agent 已啟動！");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            try {
                scheduler.stop();
                botsApp.close();
            } catch (Exception e) {
                log.warn("Shutdown error: {}", e.getMessage());
            }
        }, "shutdown-hook"));
    }
}
