package com.healthcoach.memory;

import com.healthcoach.model.SkillSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for SkillManager. */
class SkillManagerTest {

    private static final String NUTRITION_SKILL =
            "---\n" +
            "name: nutrition-advice\n" +
            "description: 飲食建議、台灣常見食物熱量估算、三大營養素分配\n" +
            "---\n\n" +
            "# 飲食建議\n\n" +
            "## 台灣常見食物\n\n" +
            "- 雞腿便當：800-900 kcal\n";

    private static final String WORKOUT_SKILL =
            "---\n" +
            "name: workout-planning\n" +
            "description: 阻力訓練規劃、推拉腿分化、上下肢分化、組數次數建議\n" +
            "---\n\n" +
            "# 訓練規劃\n";

    @TempDir
    Path tempDir;

    private SkillManager manager;

    @BeforeEach
    void setUp() throws IOException {
        Path nutritionDir = tempDir.resolve("skills").resolve("nutrition-advice");
        Path workoutDir = tempDir.resolve("skills").resolve("workout-planning");
        Files.createDirectories(nutritionDir);
        Files.createDirectories(workoutDir);
        Files.writeString(nutritionDir.resolve("SKILL.md"), NUTRITION_SKILL, StandardCharsets.UTF_8);
        Files.writeString(workoutDir.resolve("SKILL.md"), WORKOUT_SKILL, StandardCharsets.UTF_8);
        manager = new SkillManager(tempDir);
    }

    @Test
    void t41_listSkillsReturnsBoth() {
        List<SkillSummary> skills = manager.listSkills();
        assertEquals(2, skills.size());
        List<String> names = skills.stream().map(s -> s.name).collect(Collectors.toList());
        assertTrue(names.contains("nutrition-advice"));
        assertTrue(names.contains("workout-planning"));
    }

    @Test
    void t42_eachSkillHasNonEmptyNameAndDescription() {
        List<SkillSummary> skills = manager.listSkills();
        for (SkillSummary s : skills) {
            assertNotNull(s.name);
            assertFalse(s.name.isEmpty(), "name should be non-empty");
            assertNotNull(s.description);
            assertFalse(s.description.isEmpty(), "description should be non-empty");
        }
    }

    @Test
    void t43_loadSkillReturnsFullText() {
        String text = manager.loadSkill("nutrition-advice");
        assertTrue(text.contains("台灣常見食物"));
    }

    @Test
    void t44_loadSkillMissingThrows() {
        assertThrows(IllegalArgumentException.class, () -> manager.loadSkill("nonexistent"));
    }

    @Test
    void t45_patchAppendAddsContent() {
        assertTrue(manager.patchSkill("nutrition-advice", "append", "新增內容"));
        assertTrue(manager.loadSkill("nutrition-advice").contains("新增內容"));
    }

    @Test
    void t46_appendedContentAppearsAfterOriginal() {
        manager.patchSkill("nutrition-advice", "append", "新增內容");
        String text = manager.loadSkill("nutrition-advice");
        int chickenIdx = text.indexOf("雞腿便當");
        int addedIdx = text.indexOf("新增內容");
        assertTrue(chickenIdx >= 0, "雞腿便當 should be present");
        assertTrue(addedIdx > chickenIdx, "新增內容 should appear AFTER 雞腿便當");
    }

    @Test
    void t47_patchReplaceSwapsText() {
        assertTrue(manager.patchSkill("nutrition-advice", "replace", "800-900 kcal|||700-750 kcal"));
        String text = manager.loadSkill("nutrition-advice");
        assertTrue(text.contains("700-750 kcal"));
        assertFalse(text.contains("800-900 kcal"));
    }

    @Test
    void t48_patchReplaceMissingTargetReturnsFalseAndKeepsFile() {
        String before = manager.loadSkill("nutrition-advice");
        assertFalse(manager.patchSkill("nutrition-advice", "replace", "NONEXISTENT|||X"));
        String after = manager.loadSkill("nutrition-advice");
        assertEquals(before, after);
    }

    @Test
    void t49_patchMissingSkillReturnsFalse() {
        assertFalse(manager.patchSkill("nonexistent-skill", "append", "x"));
    }

    @Test
    void t410_indexTextListsAllSkills() {
        String index = manager.getSkillsIndexText();
        assertTrue(index.contains("可用的知識模組"));
        assertTrue(index.contains("nutrition-advice"));
        assertTrue(index.contains("workout-planning"));
    }
}
