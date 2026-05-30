package com.healthcoach.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.healthcoach.model.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreferencesStoreTest {

    @TempDir Path tempDir;

    @Test
    void loadReturnsDefaultsWhenFileMissing() {
        PreferencesStore s = new PreferencesStore(tempDir);
        Preferences p = s.load();
        assertEquals("Asia/Taipei", p.timezone);
        assertNotNull(p.mealReminders);
        assertTrue(p.mealReminders.isEmpty());
    }

    @Test
    void saveAndLoadRoundtrip() {
        PreferencesStore s = new PreferencesStore(tempDir);
        Preferences in = new Preferences("America/New_York",
                List.of("08:00", "13:00"), "21:00", "MON 09:00");
        s.save(in);
        Preferences out = s.load();
        assertEquals("America/New_York", out.timezone);
        assertEquals(List.of("08:00", "13:00"), out.mealReminders);
        assertEquals("21:00", out.workoutReminder);
        assertEquals("MON 09:00", out.weeklySummary);
    }

    @Test
    void setMealRemindersPersists() {
        PreferencesStore s = new PreferencesStore(tempDir);
        s.setMealReminders(List.of("12:00", "18:30"));
        Preferences p = s.load();
        assertEquals(List.of("12:00", "18:30"), p.mealReminders);
    }

    @Test
    void setWorkoutReminderPersists() {
        PreferencesStore s = new PreferencesStore(tempDir);
        s.setWorkoutReminder("21:30");
        assertEquals("21:30", s.load().workoutReminder);
    }

    @Test
    void migrateFromLegacyConfigSeedsPreferences() throws Exception {
        // preferences.json absent, legacy schedule block provided
        JsonObject legacy = new JsonObject();
        legacy.addProperty("timezone", "Asia/Taipei");
        JsonArray meals = new JsonArray();
        meals.add("07:00"); meals.add("12:00"); meals.add("19:00");
        legacy.add("mealReminders", meals);
        legacy.addProperty("workoutReminder", "20:00");
        legacy.addProperty("weeklySummary", "SUN 21:00");

        PreferencesStore s = new PreferencesStore(tempDir);
        s.migrateFromLegacyConfigIfNeeded(legacy);

        Preferences p = s.load();
        assertEquals(List.of("07:00", "12:00", "19:00"), p.mealReminders);
        assertEquals("20:00", p.workoutReminder);
        assertEquals("SUN 21:00", p.weeklySummary);
        assertTrue(Files.exists(tempDir.resolve("preferences.json")));
    }

    @Test
    void tSetEffortValid() {
        PreferencesStore s = new PreferencesStore(tempDir);
        s.setEffort("low");
        assertEquals("low", s.load().effort);
    }

    @Test
    void tSetEffortInvalidFallsBackToMedium() {
        PreferencesStore s = new PreferencesStore(tempDir);
        s.setEffort("foo");
        assertEquals("medium", s.load().effort);
    }

    @Test
    void migrateIsNoOpWhenPreferencesAlreadyExist() {
        PreferencesStore s = new PreferencesStore(tempDir);
        s.save(new Preferences("UTC", List.of("10:00"), "", ""));

        JsonObject legacy = new JsonObject();
        legacy.addProperty("timezone", "Asia/Taipei");
        JsonArray meals = new JsonArray();
        meals.add("07:30");
        legacy.add("mealReminders", meals);
        legacy.addProperty("workoutReminder", "20:00");
        legacy.addProperty("weeklySummary", "");

        s.migrateFromLegacyConfigIfNeeded(legacy);

        Preferences p = s.load();
        assertEquals("UTC", p.timezone, "existing prefs should win, not be overwritten by legacy");
        assertEquals(List.of("10:00"), p.mealReminders);
    }
}
