package com.healthcoach.scheduler;

import com.google.gson.JsonObject;
import com.healthcoach.bot.TelegramBot;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.model.DailyLog;
import com.healthcoach.model.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CronScheduler {
    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);
    private static final long ONE_DAY_SECONDS = 24L * 60 * 60;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cron-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final TelegramBot bot;
    private final DailyLogStore dailyLogStore;
    private final MemoryStore memoryStore;
    private final ZoneId timezone;
    private final List<String> mealReminderTimes = new ArrayList<>();
    private final String workoutReminderTime;

    public CronScheduler(TelegramBot bot, DailyLogStore dailyLogStore, MemoryStore memoryStore, JsonObject scheduleConfig) {
        this.bot = bot;
        this.dailyLogStore = dailyLogStore;
        this.memoryStore = memoryStore;
        this.timezone = ZoneId.of(scheduleConfig.get("timezone").getAsString());
        scheduleConfig.getAsJsonArray("mealReminders").forEach(e -> mealReminderTimes.add(e.getAsString()));
        this.workoutReminderTime = scheduleConfig.get("workoutReminder").getAsString();
    }

    /** Schedule meal and workout reminders at fixed daily intervals. */
    public void start() {
        for (int i = 0; i < mealReminderTimes.size(); i++) {
            String type = (i == 0) ? "breakfast" : (i == 1) ? "lunch" : "dinner";
            scheduleDaily(type, LocalTime.parse(mealReminderTimes.get(i), HHMM));
        }
        scheduleDaily("workout", LocalTime.parse(workoutReminderTime, HHMM));
    }

    private void scheduleDaily(String type, LocalTime t) {
        long delay = calculateInitialDelay(t, timezone);
        executor.scheduleAtFixedRate(() -> fireForAll(type), delay, ONE_DAY_SECONDS, TimeUnit.SECONDS);
    }

    private void fireForAll(String type) {
        try {
            for (String chatId : bot.getRegisteredChatIds()) {
                sendReminder(chatId, type);
            }
        } catch (Exception e) {
            log.warn("reminder loop failed: {}", e.getMessage());
        }
    }

    /** Shut down the scheduler executor immediately. */
    public void stop() {
        executor.shutdownNow();
    }

    /** Visible for testing: build and send the reminder for a chat and type. */
    void sendReminder(String chatId, String type) {
        DailyLog logToday = dailyLogStore.loadToday();
        UserProfile profile = memoryStore.loadUserProfile();
        dailyLogStore.recalculateSummary(logToday, profile);
        String msg;
        switch (type) {
            case "breakfast":
                msg = "早安！等等吃完早餐跟我說吃了什麼 🍳";
                break;
            case "lunch":
                msg = String.format("午餐時間到！今天目前 %d / %d kcal",
                        logToday.dailySummary.totalKcal, logToday.dailySummary.targetKcal);
                break;
            case "dinner":
                msg = String.format("晚餐吃了什麼？還剩 %d kcal 的額度喔",
                        logToday.dailySummary.kcalRemaining);
                break;
            case "workout":
                msg = "今天有運動嗎？跟我說你做了什麼訓練 💪";
                break;
            default:
                return;
        }
        bot.sendText(chatId, msg);
    }

    /** Visible for testing: seconds until next occurrence of targetTime in zone. */
    long calculateInitialDelay(LocalTime targetTime, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime target = now.with(targetTime).withSecond(0).withNano(0);
        if (!target.isAfter(now)) target = target.plusDays(1);
        return Duration.between(now, target).getSeconds();
    }

    /** Visible for testing: whether the underlying executor has been stopped. */
    boolean isStopped() {
        return executor.isShutdown();
    }
}
