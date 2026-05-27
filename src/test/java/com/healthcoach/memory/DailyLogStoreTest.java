package com.healthcoach.memory;

import com.google.gson.Gson;
import com.healthcoach.model.DailyLog;
import com.healthcoach.model.MealEntry;
import com.healthcoach.model.UserProfile;
import com.healthcoach.model.WorkoutEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyLogStoreTest {

    @Test
    void t3_13_loadTodayOnEmptyDirCreatesFile(@TempDir Path tempDir) {
        DailyLogStore store = new DailyLogStore(tempDir);
        DailyLog log = store.loadToday();
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        assertEquals(today, log.date);
        assertNotNull(log.meals);
        assertTrue(log.meals.isEmpty());
        Path file = tempDir.resolve("logs").resolve(today + ".json");
        assertTrue(Files.exists(file), "expected daily log file to be created at " + file);
    }

    @Test
    void t3_14_addMealAppendsSingle(@TempDir Path tempDir) {
        DailyLogStore store = new DailyLogStore(tempDir);
        store.addMeal(new MealEntry("12:00", "便當", 800, 30, 80, 25, "llm"));
        assertEquals(1, store.loadToday().meals.size());
    }

    @Test
    void t3_15_addMealAppendsTwice(@TempDir Path tempDir) {
        DailyLogStore store = new DailyLogStore(tempDir);
        store.addMeal(new MealEntry("12:00", "便當", 800, 30, 80, 25, "llm"));
        store.addMeal(new MealEntry("18:30", "晚餐", 700, 25, 70, 22, "llm"));
        assertEquals(2, store.loadToday().meals.size());
    }

    @Test
    void t3_16_setWorkoutPersists(@TempDir Path tempDir) {
        DailyLogStore store = new DailyLogStore(tempDir);
        store.setWorkout(new WorkoutEntry("strength", "推", 45, true));
        DailyLog log = store.loadToday();
        assertNotNull(log.workout);
        assertEquals("strength", log.workout.type);
    }

    @Test
    void t3_17_recalculateSummaryTotalsKcal(@TempDir Path tempDir) {
        DailyLogStore store = new DailyLogStore(tempDir);
        store.addMeal(new MealEntry("12:00", "午餐", 500, 20, 60, 15, "llm"));
        store.addMeal(new MealEntry("18:00", "晚餐", 300, 15, 30, 8, "llm"));
        UserProfile profile = new UserProfile();
        profile.targetCalories = 2800;
        profile.targetProteinG = 140;
        DailyLog log = store.loadToday();
        store.recalculateSummary(log, profile);
        assertEquals(800, log.dailySummary.totalKcal);
    }

    @Test
    void t3_18_recalculateSummaryRemainingKcal(@TempDir Path tempDir) {
        DailyLogStore store = new DailyLogStore(tempDir);
        store.addMeal(new MealEntry("12:00", "午餐", 500, 20, 60, 15, "llm"));
        store.addMeal(new MealEntry("18:00", "晚餐", 300, 15, 30, 8, "llm"));
        UserProfile profile = new UserProfile();
        profile.targetCalories = 2800;
        profile.targetProteinG = 140;
        DailyLog log = store.loadToday();
        store.recalculateSummary(log, profile);
        assertEquals(2000, log.dailySummary.kcalRemaining);
    }

    @Test
    void t3_19_loadDateRangeSkipsMissingDates(@TempDir Path tempDir) throws Exception {
        Path logsDir = tempDir.resolve("logs");
        Files.createDirectories(logsDir);
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        writeEmptyLog(logsDir, today);
        writeEmptyLog(logsDir, yesterday);

        DailyLogStore store = new DailyLogStore(tempDir);
        List<DailyLog> logs = store.loadDateRange(today.minusDays(2), today);
        assertEquals(2, logs.size());
        // ensure the missing day did NOT get created on disk
        Path missing = logsDir.resolve(today.minusDays(2).format(DateTimeFormatter.ISO_LOCAL_DATE) + ".json");
        assertTrue(!Files.exists(missing), "loadDateRange must not create missing date files");
    }

    @Test
    void t3_20_todaySummaryTextContainsKcal(@TempDir Path tempDir) {
        DailyLogStore store = new DailyLogStore(tempDir);
        UserProfile profile = new UserProfile();
        profile.targetCalories = 2000;
        profile.targetProteinG = 120;
        String text = store.getTodaySummaryText(profile);
        assertTrue(text.contains("kcal"), text);
    }

    private static void writeEmptyLog(Path logsDir, LocalDate date) throws Exception {
        DailyLog log = new DailyLog();
        log.date = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        log.meals = new ArrayList<>();
        Path file = logsDir.resolve(date.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".json");
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new Gson().toJson(log, w);
        }
    }
}
