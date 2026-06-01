package com.healthcoach.scheduler;

import com.healthcoach.bot.MessageSender;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.PreferencesStore;
import com.healthcoach.model.DailyLog;
import com.healthcoach.model.Preferences;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Schedules meal and workout reminders. Reads current schedule from {@link PreferencesStore}
 * so it can be re-armed at runtime via {@link #reschedule()} when the user changes preferences.
 */
public class CronScheduler {
    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);
    private static final long ONE_DAY_SECONDS = 24L * 60 * 60;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cron-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final MessageSender bot;
    private final DailyLogStore dailyLogStore;
    private final MemoryStore memoryStore;
    private final PreferencesStore preferencesStore;
    private final List<ScheduledFuture<?>> currentTasks = new ArrayList<>();

    private ZoneId timezone;

    public CronScheduler(MessageSender bot, DailyLogStore dailyLogStore, MemoryStore memoryStore,
                         PreferencesStore preferencesStore) {
        this.bot = bot;
        this.dailyLogStore = dailyLogStore;
        this.memoryStore = memoryStore;
        this.preferencesStore = preferencesStore;
        this.timezone = ZoneId.of(preferencesStore.load().timezone);
    }

    /** Schedule meal and workout reminders from the current preferences. */
    public synchronized void start() {
        armFromPreferences();
    }

    /** Cancel all scheduled tasks and re-arm from current preferences. Idempotent. */
    public synchronized void reschedule() {
        for (ScheduledFuture<?> f : currentTasks) {
            f.cancel(false);
        }
        currentTasks.clear();
        armFromPreferences();
    }

    private void armFromPreferences() {
        Preferences pref = preferencesStore.load();
        this.timezone = ZoneId.of(pref.timezone);

        List<String> meals = pref.mealReminders == null ? List.of() : pref.mealReminders;
        for (int i = 0; i < meals.size(); i++) {
            String type = (i == 0) ? "breakfast" : (i == 1) ? "lunch" : "dinner";
            scheduleDaily(type, LocalTime.parse(meals.get(i), HHMM));
        }
        if (pref.workoutReminder != null && !pref.workoutReminder.isBlank()) {
            scheduleDaily("workout", LocalTime.parse(pref.workoutReminder, HHMM));
        }
    }

    private void scheduleDaily(String type, LocalTime t) {
        long delay = calculateInitialDelay(t, timezone);
        ScheduledFuture<?> f = executor.scheduleAtFixedRate(
                () -> fireForAll(type), delay, ONE_DAY_SECONDS, TimeUnit.SECONDS);
        currentTasks.add(f);
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

    public void stop() {
        executor.shutdownNow();
    }

    /** Build and send the reminder for a chat and type. */
    public void sendReminder(String chatId, String type) {
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

    /** Visible for testing: count of currently armed tasks. */
    int activeTaskCount() {
        return currentTasks.size();
    }

    boolean isStopped() {
        return executor.isShutdown();
    }
}
