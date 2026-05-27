package com.healthcoach.scheduler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.healthcoach.bot.TelegramBot;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CronSchedulerTest {

    @TempDir Path tempDir;

    private MemoryStore memoryStore;
    private DailyLogStore dailyLogStore;
    private FakeBot bot;
    private CronScheduler cron;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("user_profile.json"),
                "{\"name\":\"\",\"heightCm\":0,\"weightKg\":0,\"age\":0,\"gender\":\"\"," +
                "\"activityLevel\":\"\",\"goal\":\"\",\"bmr\":0,\"tdee\":0,\"targetCalories\":2800," +
                "\"targetProteinG\":140,\"targetCarbsG\":0,\"targetFatG\":0," +
                "\"dietaryRestrictions\":[],\"notes\":\"\",\"updatedAt\":\"\"}");
        Files.writeString(tempDir.resolve("memory.json"),
                "{\"entries\":[],\"maxEntries\":20,\"maxChars\":2200}");
        Files.createDirectories(tempDir.resolve("skills"));
        Files.createDirectories(tempDir.resolve("logs"));

        memoryStore = new MemoryStore(tempDir);
        dailyLogStore = new DailyLogStore(tempDir);
        bot = new FakeBot();
        cron = new CronScheduler(bot, dailyLogStore, memoryStore, defaultConfig());
    }

    private JsonObject defaultConfig() {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("timezone", "Asia/Taipei");
        JsonArray meals = new JsonArray();
        meals.add("07:30");
        meals.add("12:00");
        meals.add("18:00");
        cfg.add("mealReminders", meals);
        cfg.addProperty("workoutReminder", "20:00");
        cfg.addProperty("weeklySummary", "SUN 21:00");
        return cfg;
    }

    @Test
    void t91_initialDelayFutureTimeApproxOneHour() {
        ZoneId zone = ZoneId.of("Asia/Taipei");
        LocalTime target = ZonedDateTime.now(zone).plusHours(1).toLocalTime().withSecond(0).withNano(0);
        long delay = cron.calculateInitialDelay(target, zone);
        assertTrue(delay >= 3540 && delay <= 3660,
                "expected ~3600s but was " + delay);
    }

    @Test
    void t92_initialDelayPastTimeWrapsToTomorrow() {
        ZoneId zone = ZoneId.of("Asia/Taipei");
        LocalTime target = ZonedDateTime.now(zone).minusHours(1).toLocalTime().withSecond(0).withNano(0);
        long delay = cron.calculateInitialDelay(target, zone);
        assertTrue(delay >= 22 * 3600 && delay <= 24 * 3600,
                "expected ~23h but was " + delay);
    }

    @Test
    void t93_lunchReminderContainsKcal() {
        cron.sendReminder("1001", "lunch");
        assertEquals(1, bot.sent.size());
        assertTrue(bot.sent.get(0)[1].contains("kcal"), "lunch message: " + bot.sent.get(0)[1]);
    }

    @Test
    void t94_breakfastReminderContainsZaoan() {
        cron.sendReminder("1001", "breakfast");
        assertTrue(bot.sent.get(0)[1].contains("早安"));
    }

    @Test
    void t95_workoutReminderContainsYundong() {
        cron.sendReminder("1001", "workout");
        assertTrue(bot.sent.get(0)[1].contains("運動"));
    }

    @Test
    void t96_startDoesNotThrow() {
        assertDoesNotThrow(() -> cron.start());
        cron.stop();
    }

    @Test
    void t97_stopShutsDownExecutor() {
        cron.start();
        cron.stop();
        assertTrue(cron.isStopped());
    }

    /** FakeBot bypasses the OkHttp client by overriding sendText / getRegisteredChatIds. */
    private static class FakeBot extends TelegramBot {
        final List<String[]> sent = new ArrayList<>();
        final Set<String> chatIds = new HashSet<>(Set.of("1001"));

        FakeBot() {
            super((org.telegram.telegrambots.meta.generics.TelegramClient) null, null, null);
        }

        @Override
        public void sendText(String chatId, String text) {
            sent.add(new String[]{chatId, text});
        }

        @Override
        public Set<String> getRegisteredChatIds() {
            return chatIds;
        }
    }
}
