package com.healthcoach.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.healthcoach.model.DailyLog;
import com.healthcoach.model.DailySummary;
import com.healthcoach.model.MealEntry;
import com.healthcoach.model.UserProfile;
import com.healthcoach.model.WorkoutEntry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists per-date workout and meal logs as pretty JSON files under data/logs/YYYY-MM-DD.json.
 */
public class DailyLogStore {

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M/d");

    private final Path logsDir;
    private final Gson gson;

    /** Create a store rooted at dataDir/logs; ensures the logs directory exists. */
    public DailyLogStore(Path dataDir) {
        this.logsDir = dataDir.resolve("logs");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Load (and create if needed) today's daily log. */
    public DailyLog loadToday() {
        return loadDate(LocalDate.now());
    }

    /** Load the log for the given date; creates an empty log on disk if it does not yet exist. */
    public DailyLog loadDate(LocalDate date) {
        Path file = fileForDate(date);
        if (!Files.exists(file)) {
            DailyLog log = newEmptyLog(date);
            saveLog(log);
            return log;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            DailyLog parsed = gson.fromJson(reader, DailyLog.class);
            if (parsed == null) {
                parsed = newEmptyLog(date);
            }
            if (parsed.meals == null) {
                parsed.meals = new ArrayList<>();
            }
            if (parsed.dailySummary == null) {
                parsed.dailySummary = new DailySummary();
            }
            if (parsed.date == null || parsed.date.isEmpty()) {
                parsed.date = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            return parsed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Append a meal to today's log, refresh meal-side summary totals, then persist. */
    public void addMeal(MealEntry meal) {
        addMeal(LocalDate.now(), meal);
    }

    /** Append a meal to a specific date's log, refresh totals, then persist. */
    public void addMeal(LocalDate date, MealEntry meal) {
        DailyLog log = loadDate(date);
        log.meals.add(meal);
        sumMealTotals(log);
        saveLog(log);
    }

    /** Remove a meal from today's log by 0-based index, recalculate totals, and persist. Returns true if removed. */
    public boolean removeMeal(int index) {
        DailyLog log = loadToday();
        if (log.meals == null || index < 0 || index >= log.meals.size()) {
            return false;
        }
        log.meals.remove(index);
        sumMealTotals(log);
        saveLog(log);
        return true;
    }

    /** Set (overwrite) today's workout entry and persist. */
    public void setWorkout(WorkoutEntry workout) {
        DailyLog log = loadToday();
        log.workout = workout;
        saveLog(log);
    }

    /** Recompute totals and targets on the given log using the user's profile (does not save). */
    public void recalculateSummary(DailyLog log, UserProfile profile) {
        sumMealTotals(log);
        DailySummary s = log.dailySummary;
        s.targetKcal = profile.targetCalories;
        s.targetProteinG = profile.targetProteinG;
        s.kcalRemaining = s.targetKcal - s.totalKcal;
        s.proteinRemainingG = s.targetProteinG - s.totalProteinG;
    }

    /** Load every existing daily log between two dates (inclusive); silently skips missing dates. */
    public List<DailyLog> loadDateRange(LocalDate from, LocalDate to) {
        List<DailyLog> out = new ArrayList<>();
        if (from == null || to == null || from.isAfter(to)) {
            return out;
        }
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Path file = fileForDate(d);
            if (!Files.exists(file)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                DailyLog parsed = gson.fromJson(reader, DailyLog.class);
                if (parsed != null) {
                    if (parsed.meals == null) {
                        parsed.meals = new ArrayList<>();
                    }
                    if (parsed.dailySummary == null) {
                        parsed.dailySummary = new DailySummary();
                    }
                    out.add(parsed);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** Render a one-line zh-TW summary of today's intake versus the profile's targets. */
    public String getTodaySummaryText(UserProfile profile) {
        DailyLog log = loadToday();
        recalculateSummary(log, profile);
        saveLog(log);
        String shortDate = LocalDate.now().format(SHORT_DATE);
        DailySummary s = log.dailySummary;
        if (profile.targetCalories == 0) {
            return "今日紀錄（" + shortDate + "）：尚未設定目標。已攝取 " + s.totalKcal + " kcal。";
        }
        return "今日紀錄（" + shortDate + "）：已攝取 " + s.totalKcal + " kcal（目標 " + s.targetKcal
                + "），蛋白質 " + s.totalProteinG + "g（目標 " + s.targetProteinG + "g），碳水 "
                + s.totalCarbsG + "g，脂肪 " + s.totalFatG + "g。剩餘 " + s.kcalRemaining + " kcal。";
    }

    private Path fileForDate(LocalDate date) {
        return logsDir.resolve(date.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".json");
    }

    private DailyLog newEmptyLog(LocalDate date) {
        DailyLog log = new DailyLog();
        log.date = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        log.meals = new ArrayList<>();
        log.dailySummary = new DailySummary();
        return log;
    }

    private void sumMealTotals(DailyLog log) {
        int kcal = 0;
        int p = 0;
        int c = 0;
        int f = 0;
        if (log.meals != null) {
            for (MealEntry m : log.meals) {
                kcal += m.estimatedKcal;
                p += m.proteinG;
                c += m.carbsG;
                f += m.fatG;
            }
        }
        if (log.dailySummary == null) {
            log.dailySummary = new DailySummary();
        }
        log.dailySummary.totalKcal = kcal;
        log.dailySummary.totalProteinG = p;
        log.dailySummary.totalCarbsG = c;
        log.dailySummary.totalFatG = f;
    }

    private void saveLog(DailyLog log) {
        Path file = fileForDate(LocalDate.parse(log.date));
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            gson.toJson(log, writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
