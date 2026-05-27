package com.healthcoach.agent;

import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.SkillManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for PromptBuilder — verifies system prompt assembly across all memory layers. */
class PromptBuilderTest {

    private static final String EMPTY_PROFILE_JSON = ""
            + "{\n"
            + "  \"name\": \"\",\n"
            + "  \"heightCm\": 0.0,\n"
            + "  \"weightKg\": 0.0,\n"
            + "  \"age\": 0,\n"
            + "  \"gender\": \"\",\n"
            + "  \"activityLevel\": \"\",\n"
            + "  \"goal\": \"\",\n"
            + "  \"bmr\": 0,\n"
            + "  \"tdee\": 0,\n"
            + "  \"targetCalories\": 0,\n"
            + "  \"targetProteinG\": 0,\n"
            + "  \"targetCarbsG\": 0,\n"
            + "  \"targetFatG\": 0,\n"
            + "  \"dietaryRestrictions\": [],\n"
            + "  \"notes\": \"\",\n"
            + "  \"updatedAt\": \"\"\n"
            + "}\n";

    private static final String EMPTY_MEMORY_JSON = ""
            + "{\n"
            + "  \"entries\": [],\n"
            + "  \"maxEntries\": 20,\n"
            + "  \"maxChars\": 2200\n"
            + "}\n";

    private static final String NUTRITION_SKILL = ""
            + "---\n"
            + "name: nutrition-advice\n"
            + "description: 飲食建議\n"
            + "---\n\n"
            + "# 飲食建議\n";

    private static final String WORKOUT_SKILL = ""
            + "---\n"
            + "name: workout-planning\n"
            + "description: 訓練規劃\n"
            + "---\n\n"
            + "# 訓練規劃\n";

    @TempDir
    Path tempDir;

    private MemoryStore ms;
    private SkillManager sm;
    private DailyLogStore ds;
    private PromptBuilder pb;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("user_profile.json"), EMPTY_PROFILE_JSON, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("memory.json"), EMPTY_MEMORY_JSON, StandardCharsets.UTF_8);

        Path nutritionDir = tempDir.resolve("skills").resolve("nutrition-advice");
        Path workoutDir = tempDir.resolve("skills").resolve("workout-planning");
        Files.createDirectories(nutritionDir);
        Files.createDirectories(workoutDir);
        Files.writeString(nutritionDir.resolve("SKILL.md"), NUTRITION_SKILL, StandardCharsets.UTF_8);
        Files.writeString(workoutDir.resolve("SKILL.md"), WORKOUT_SKILL, StandardCharsets.UTF_8);

        Files.createDirectories(tempDir.resolve("logs"));

        ms = new MemoryStore(tempDir);
        sm = new SkillManager(tempDir);
        ds = new DailyLogStore(tempDir);
        pb = new PromptBuilder(ms, sm, ds);
    }

    @Test
    void t61_constructorOk() {
        assertDoesNotThrow(() -> new PromptBuilder(ms, sm, ds));
    }

    @Test
    void t62_promptNotNullAndNonEmpty() {
        String prompt = pb.buildSystemPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.length() > 0, "prompt should be non-empty");
    }

    @Test
    void t63_promptContainsCoach() {
        assertTrue(pb.buildSystemPrompt().contains("Coach"));
    }

    @Test
    void t64_promptContainsPatchTag() {
        assertTrue(pb.buildSystemPrompt().contains("<PATCH>"));
    }

    @Test
    void t65_promptContainsLogTag() {
        assertTrue(pb.buildSystemPrompt().contains("<LOG>"));
    }

    @Test
    void t66_promptContainsSkillsHeading() {
        assertTrue(pb.buildSystemPrompt().contains("可用的知識模組"));
    }

    @Test
    void t67_promptReflectsAddedMemory() {
        ms.addMemory("test fact");
        String prompt = pb.buildSystemPrompt();
        assertTrue(prompt.contains("test fact"), prompt);
    }

    @Test
    void t68_promptContainsTodaySummaryMarker() {
        String prompt = pb.buildSystemPrompt();
        assertTrue(prompt.contains("今日紀錄") || prompt.contains("kcal"), prompt);
    }

    @Test
    void t69_promptLengthWithinBounds() {
        int len = pb.buildSystemPrompt().length();
        assertTrue(len >= 500 && len <= 10000, "expected 500..10000, got " + len);
    }
}
