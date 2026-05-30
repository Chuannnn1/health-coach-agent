package com.healthcoach.agent;

import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.PreferencesStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.DailyLog;
import com.healthcoach.model.ExecutionResult;
import com.healthcoach.model.MemoryData;
import com.healthcoach.model.Preferences;
import com.healthcoach.model.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PatchExecutorTest {

    @TempDir
    Path tempDir;

    private MemoryStore memoryStore;
    private SkillManager skillManager;
    private DailyLogStore dailyLogStore;
    private PatchExecutor executor;

    @BeforeEach
    void setUp() throws IOException {
        Path userProfile = tempDir.resolve("user_profile.json");
        Files.writeString(userProfile,
                "{\"name\":\"\",\"heightCm\":0,\"weightKg\":0,\"age\":0,\"gender\":\"\","
                        + "\"activityLevel\":\"\",\"goal\":\"\",\"bmr\":0,\"tdee\":0,"
                        + "\"targetCalories\":0,\"targetProteinG\":0,\"targetCarbsG\":0,\"targetFatG\":0,"
                        + "\"dietaryRestrictions\":[],\"notes\":\"\",\"updatedAt\":\"\"}",
                StandardCharsets.UTF_8);

        Path memoryFile = tempDir.resolve("memory.json");
        Files.writeString(memoryFile,
                "{\"entries\":[],\"maxEntries\":20,\"maxChars\":2200}",
                StandardCharsets.UTF_8);

        Path skillDir = tempDir.resolve("skills").resolve("nutrition-advice");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        String skillBody = "---\n"
                + "name: nutrition-advice\n"
                + "description: 飲食建議\n"
                + "---\n"
                + "\n"
                + "# 飲食建議\n"
                + "\n"
                + "- 雞腿便當：800-900 kcal\n";
        Files.writeString(skillMd, skillBody, StandardCharsets.UTF_8);

        Files.createDirectories(tempDir.resolve("logs"));

        memoryStore = new MemoryStore(tempDir);
        skillManager = new SkillManager(tempDir);
        dailyLogStore = new DailyLogStore(tempDir);
        executor = new PatchExecutor(memoryStore, skillManager, dailyLogStore);
    }

    @Test
    void t5_1_plainTextNoTags() {
        ExecutionResult result = executor.execute("Hello, no tags here");
        assertEquals("Hello, no tags here", result.cleanText);
        assertTrue(result.patchResults.isEmpty());
    }

    @Test
    void t5_2_userProfilePatch() {
        String input = "回覆文字\n<PATCH>\n{\"target\":\"user_profile\",\"action\":\"update\",\"field\":\"name\",\"value\":\"小明\"}\n</PATCH>";
        ExecutionResult result = executor.execute(input);
        assertEquals("回覆文字", result.cleanText.trim());
        UserProfile profile = memoryStore.loadUserProfile();
        assertEquals("小明", profile.name);
    }

    @Test
    void t5_3_memoryAdd() {
        String input = "ok\n<PATCH>\n{\"target\":\"memory\",\"action\":\"add\",\"content\":\"使用者不吃牛\"}\n</PATCH>";
        executor.execute(input);
        MemoryData data = memoryStore.loadMemory();
        assertEquals(1, data.entries.size());
        assertEquals("使用者不吃牛", data.entries.get(0).content);
    }

    @Test
    void t5_4_skillPatchAppend() {
        String input = "<PATCH>\n{\"target\":\"skill/nutrition-advice\",\"action\":\"append\",\"content\":\"雞腿便當修正為 700kcal\"}\n</PATCH>";
        executor.execute(input);
        String updated = skillManager.loadSkill("nutrition-advice");
        assertTrue(updated.contains("雞腿便當修正為 700kcal"));
        int idxOld = updated.indexOf("800-900 kcal");
        int idxNew = updated.indexOf("雞腿便當修正為 700kcal");
        assertTrue(idxOld >= 0);
        assertTrue(idxNew > idxOld);
    }

    @Test
    void t5_5_logBlockCreatesMeal() {
        String input = "估算 850 kcal\n<LOG>\n{\"time\":\"12:30\",\"description\":\"雞腿便當\",\"estimatedKcal\":850,\"proteinG\":35,\"carbsG\":95,\"fatG\":28}\n</LOG>";
        ExecutionResult result = executor.execute(input);
        assertEquals("估算 850 kcal", result.cleanText.trim());
        DailyLog today = dailyLogStore.loadToday();
        assertEquals(1, today.meals.size());
        assertEquals(850, today.meals.get(0).estimatedKcal);
    }

    @Test
    void t5_6_mixedBlocks() {
        String input = "先說明\n"
                + "<PATCH>\n{\"target\":\"user_profile\",\"action\":\"update\",\"field\":\"name\",\"value\":\"阿明\"}\n</PATCH>\n"
                + "<PATCH>\n{\"target\":\"memory\",\"action\":\"add\",\"content\":\"愛吃雞肉\"}\n</PATCH>\n"
                + "<LOG>\n{\"time\":\"19:00\",\"description\":\"晚餐\",\"estimatedKcal\":700,\"proteinG\":40,\"carbsG\":80,\"fatG\":20}\n</LOG>";
        ExecutionResult result = executor.execute(input);
        assertEquals("阿明", memoryStore.loadUserProfile().name);
        MemoryData mem = memoryStore.loadMemory();
        assertEquals(1, mem.entries.size());
        assertEquals("愛吃雞肉", mem.entries.get(0).content);
        DailyLog today = dailyLogStore.loadToday();
        assertEquals(1, today.meals.size());
        assertFalse(result.cleanText.contains("<PATCH>"));
        assertFalse(result.cleanText.contains("</PATCH>"));
        assertFalse(result.cleanText.contains("<LOG>"));
        assertFalse(result.cleanText.contains("</LOG>"));
    }

    @Test
    void t5_7_malformedJsonIsSkipped() {
        String input = "OK\n<PATCH>\n{invalid json}\n</PATCH>";
        ExecutionResult result = assertDoesNotThrow(() -> executor.execute(input));
        assertEquals("OK", result.cleanText.trim());
        assertTrue(result.patchResults.size() >= 1);
        String joined = String.join("\n", result.patchResults);
        assertTrue(joined.contains("skipped") || joined.contains("malformed"));
    }

    @Test
    void t5_8_validThenMalformed() {
        String input = "<PATCH>\n{\"target\":\"memory\",\"action\":\"add\",\"content\":\"first\"}\n</PATCH>\n"
                + "<PATCH>\n{bad}\n</PATCH>";
        ExecutionResult result = assertDoesNotThrow(() -> executor.execute(input));
        MemoryData mem = memoryStore.loadMemory();
        assertEquals(1, mem.entries.size());
        assertEquals("first", mem.entries.get(0).content);
        assertTrue(result.patchResults.size() >= 2);
    }

    @Test
    void t5_9_cleanTextStripsAllTags() {
        String input = "before\n<PATCH>\n{\"target\":\"memory\",\"action\":\"add\",\"content\":\"x\"}\n</PATCH>\nafter\n"
                + "<LOG>\n{\"time\":\"08:00\",\"description\":\"早餐\",\"estimatedKcal\":300,\"proteinG\":10,\"carbsG\":40,\"fatG\":8}\n</LOG>";
        ExecutionResult result = executor.execute(input);
        assertFalse(result.cleanText.contains("<PATCH>"));
        assertFalse(result.cleanText.contains("</PATCH>"));
        assertFalse(result.cleanText.contains("<LOG>"));
        assertFalse(result.cleanText.contains("</LOG>"));
    }

    @Test
    void t5_10_inlineTagStripped() {
        String input = "Here is some <PATCH>\n{\"target\":\"memory\",\"action\":\"add\",\"content\":\"test\"}\n</PATCH> and more text";
        ExecutionResult result = executor.execute(input);
        assertFalse(result.cleanText.contains("<PATCH>"));
        assertFalse(result.cleanText.contains("</PATCH>"));
        assertTrue(result.cleanText.contains("Here is some"));
        assertTrue(result.cleanText.contains("and more text"));
        MemoryData mem = memoryStore.loadMemory();
        assertEquals(1, mem.entries.size());
        assertEquals("test", mem.entries.get(0).content);
    }

    @Test
    void t5_11_emptyInput() {
        ExecutionResult result = assertDoesNotThrow(() -> executor.execute(""));
        assertNotNull(result);
        assertEquals("", result.cleanText);
    }

    // ---------- preferences target ----------

    private PatchExecutor newExecutorWithPreferences(int[] callCounter) {
        PreferencesStore prefStore = new PreferencesStore(tempDir);
        prefStore.save(new Preferences("Asia/Taipei",
                new java.util.ArrayList<>(java.util.List.of("07:30", "12:00", "18:00")),
                "20:00", "SUN 21:00"));
        return new PatchExecutor(memoryStore, skillManager, dailyLogStore, prefStore,
                () -> callCounter[0]++);
    }

    @Test
    void t5_12_preferencesSetMealsArray() {
        int[] calls = {0};
        PatchExecutor ex = newExecutorWithPreferences(calls);
        String input = "已改\n<PATCH>{\"target\":\"preferences\",\"action\":\"set_meals\",\"value\":[\"12:00\",\"18:30\"]}</PATCH>";
        ExecutionResult r = ex.execute(input);
        PreferencesStore p = new PreferencesStore(tempDir);
        assertEquals(java.util.List.of("12:00", "18:30"), p.load().mealReminders);
        assertEquals(1, calls[0], "reschedule callback should fire once");
        assertEquals("已改", r.cleanText.trim());
    }

    @Test
    void t5_13_preferencesSetMealsCommaString() {
        int[] calls = {0};
        PatchExecutor ex = newExecutorWithPreferences(calls);
        String input = "<PATCH>{\"target\":\"preferences\",\"action\":\"set_meals\",\"value\":\"08:00, 13:00, 19:00\"}</PATCH>";
        ex.execute(input);
        PreferencesStore p = new PreferencesStore(tempDir);
        assertEquals(java.util.List.of("08:00", "13:00", "19:00"), p.load().mealReminders);
        assertEquals(1, calls[0]);
    }

    @Test
    void t5_14_preferencesClearMeals() {
        int[] calls = {0};
        PatchExecutor ex = newExecutorWithPreferences(calls);
        String input = "<PATCH>{\"target\":\"preferences\",\"action\":\"clear_meals\"}</PATCH>";
        ex.execute(input);
        assertTrue(new PreferencesStore(tempDir).load().mealReminders.isEmpty());
        assertEquals(1, calls[0]);
    }

    @Test
    void t5_15_preferencesSetWorkout() {
        int[] calls = {0};
        PatchExecutor ex = newExecutorWithPreferences(calls);
        String input = "<PATCH>{\"target\":\"preferences\",\"action\":\"set_workout\",\"value\":\"21:30\"}</PATCH>";
        ex.execute(input);
        assertEquals("21:30", new PreferencesStore(tempDir).load().workoutReminder);
        assertEquals(1, calls[0]);
    }

    @Test
    void t5_16_preferencesPatchSkippedWhenStoreNotWired() {
        // 'executor' here is the legacy one from setUp (no preferences store)
        String input = "<PATCH>{\"target\":\"preferences\",\"action\":\"set_meals\",\"value\":[\"12:00\"]}</PATCH>";
        ExecutionResult r = executor.execute(input);
        // Should not throw and should not crash; result text is empty (only PATCH was in input)
        assertNotNull(r);
        assertTrue(r.patchResults.stream().anyMatch(s -> s.contains("no store wired") || s.contains("skipped")));
    }

    @Test
    void t5_17_scratchpadStripped() {
        String leak = ""
                + "*   User says: \"again hi there ?\"\n"
                + "*   Context: The user is greeting the coach again. No user profile is set yet.\n"
                + "\n"
                + "*   Role: Coach (professional yet friendly, warm but direct).\n"
                + "*   Goal: Get user information (height, weight, age, gender, activity level, goal) to start providing health/fitness guidance.\n"
                + "*   Constraints: Traditional Chinese, short response (<150 words), no meta-headers, no planning bullets, no code fences.\n"
                + "\n"
                + "*   Greeting: \"Hey there!\" or \"Hi again!\"\n"
                + "*   Call to action: Ask for the necessary details.\n"
                + "*   Specifics needed: Height, weight, age, gender, activity level, goal (muscle gain/fat loss/maintenance).嘿！我是 Coach。很高興再次見到你！\n"
                + "\n"
                + "準備好開始改變了嗎？\n"
                + "<PATCH>\n{\"target\":\"memory\",\"action\":\"add\",\"content\":\"使用者打招呼\"}\n</PATCH>";

        ExecutionResult result = executor.execute(leak);

        assertFalse(result.cleanText.contains("User says:"),
                "scratchpad 'User says:' should have been stripped; got: " + result.cleanText);
        assertFalse(result.cleanText.contains("Role:"));
        assertFalse(result.cleanText.contains("Goal:"));
        assertFalse(result.cleanText.contains("Constraints:"));
        assertTrue(result.cleanText.startsWith("嘿！我是 Coach。"),
                "expected cleaned text to start with the Chinese reply; got: " + result.cleanText);

        // PATCH was still applied
        MemoryData mem = memoryStore.loadMemory();
        assertEquals(1, mem.entries.size());
        assertEquals("使用者打招呼", mem.entries.get(0).content);
    }

    @Test
    void t5_18_thinkTagsStripped() {
        String raw = "<think>\n* Role: Coach\n* Plan: greet user\n</think>\n你好！我是 Coach。";
        ExecutionResult result = executor.execute(raw);
        assertTrue(result.cleanText.startsWith("你好！我是 Coach"),
                "expected think block stripped; got: " + result.cleanText);
        assertFalse(result.cleanText.contains("<think>"));
        assertFalse(result.cleanText.contains("Role:"));
    }

    @Test
    void t5_19_unclosedThinkTagStripped() {
        String raw = "<think>\n* Goal: greet\n* Maintain persona.\n\n你好！我是 Coach。";
        ExecutionResult result = executor.execute(raw);
        assertEquals("", result.cleanText,
                "unclosed think should strip everything; got: " + result.cleanText);
    }

    @Test
    void t5_20_thinkTagMixedWithPatch() {
        String raw = "<think>planning</think>\n你好！\n<PATCH>\n{\"target\":\"memory\",\"action\":\"add\",\"content\":\"test\"}\n</PATCH>";
        ExecutionResult result = executor.execute(raw);
        assertTrue(result.cleanText.startsWith("你好！"),
                "got: " + result.cleanText);
        assertFalse(result.cleanText.contains("planning"));
        assertFalse(result.cleanText.contains("PATCH"));
        assertEquals(1, result.patchResults.size());
    }
}
