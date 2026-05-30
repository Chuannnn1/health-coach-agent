package com.healthcoach.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.healthcoach.model.Preferences;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists user-mutable runtime preferences (schedule, timezone) as data/preferences.json.
 *
 * Separate from config.json (which holds system/secret config) so it can be safely mutated
 * at runtime via slash commands or LLM PATCH blocks without touching credentials.
 */
public class PreferencesStore {

    private static final String FILE = "preferences.json";

    private final Path dataDir;
    private final Gson gson;
    private final Object lock = new Object();

    public PreferencesStore(Path dataDir) {
        this.dataDir = dataDir;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Load preferences from disk; returns defaults if file missing or unreadable. */
    public Preferences load() {
        synchronized (lock) {
            Path p = dataDir.resolve(FILE);
            if (!Files.exists(p)) return new Preferences();
            try {
                String json = Files.readString(p, StandardCharsets.UTF_8);
                Preferences pref = gson.fromJson(json, Preferences.class);
                if (pref == null) return new Preferences();
                if (pref.mealReminders == null) pref.mealReminders = new ArrayList<>();
                if (pref.workoutReminder == null) pref.workoutReminder = "";
                if (pref.weeklySummary == null) pref.weeklySummary = "";
                if (pref.timezone == null || pref.timezone.isBlank()) pref.timezone = "Asia/Taipei";
                if (pref.effort == null || pref.effort.isBlank()) pref.effort = "medium";
                return pref;
            } catch (Exception e) {
                return new Preferences();
            }
        }
    }

    /** Persist preferences atomically. */
    public void save(Preferences pref) {
        synchronized (lock) {
            Path p = dataDir.resolve(FILE);
            String json = gson.toJson(pref);
            try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                w.write(json);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Replace meal reminder times, save, and return updated prefs. */
    public Preferences setMealReminders(List<String> times) {
        synchronized (lock) {
            Preferences pref = load();
            pref.mealReminders = times == null ? new ArrayList<>() : new ArrayList<>(times);
            save(pref);
            return pref;
        }
    }

    public Preferences setWorkoutReminder(String time) {
        synchronized (lock) {
            Preferences pref = load();
            pref.workoutReminder = time == null ? "" : time;
            save(pref);
            return pref;
        }
    }

    public Preferences setWeeklySummary(String dowAndTime) {
        synchronized (lock) {
            Preferences pref = load();
            pref.weeklySummary = dowAndTime == null ? "" : dowAndTime;
            save(pref);
            return pref;
        }
    }

    public Preferences setTimezone(String tz) {
        synchronized (lock) {
            Preferences pref = load();
            if (tz != null && !tz.isBlank()) pref.timezone = tz;
            save(pref);
            return pref;
        }
    }

    public Preferences setEffort(String level) {
        synchronized (lock) {
            Preferences pref = load();
            String v = level == null ? "" : level.trim().toLowerCase();
            if (!v.equals("low") && !v.equals("medium") && !v.equals("high")) {
                v = "medium";
            }
            pref.effort = v;
            save(pref);
            return pref;
        }
    }

    /**
     * One-time migration: if preferences.json doesn't exist but the legacy schedule block does,
     * seed preferences.json from it. Called once at startup.
     */
    public void migrateFromLegacyConfigIfNeeded(JsonObject legacyScheduleBlock) {
        synchronized (lock) {
            Path p = dataDir.resolve(FILE);
            if (Files.exists(p) || legacyScheduleBlock == null) return;
            Preferences pref = new Preferences();
            if (legacyScheduleBlock.has("timezone")) {
                pref.timezone = legacyScheduleBlock.get("timezone").getAsString();
            }
            if (legacyScheduleBlock.has("mealReminders")) {
                legacyScheduleBlock.getAsJsonArray("mealReminders")
                        .forEach(e -> pref.mealReminders.add(e.getAsString()));
            }
            if (legacyScheduleBlock.has("workoutReminder")) {
                pref.workoutReminder = legacyScheduleBlock.get("workoutReminder").getAsString();
            }
            if (legacyScheduleBlock.has("weeklySummary")) {
                pref.weeklySummary = legacyScheduleBlock.get("weeklySummary").getAsString();
            }
            save(pref);
        }
    }
}
