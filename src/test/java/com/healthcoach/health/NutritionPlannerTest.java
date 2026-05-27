package com.healthcoach.health;

import com.healthcoach.model.MacroTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for NutritionPlanner. */
class NutritionPlannerTest {

    private static void assertNear(int expected, int actual, int tolerance) {
        assertTrue(Math.abs(expected - actual) <= tolerance,
                "expected " + expected + " ± " + tolerance + " but was " + actual);
    }

    @Test
    void t29_muscleGainMacros() {
        MacroTarget m = NutritionPlanner.calculateMacros(3089, 72, "muscle_gain");
        assertNear(144, m.proteinG, 2);
        assertNear(86, m.fatG, 2);
        assertNear(435, m.carbsG, 10);
        assertTrue(m.proteinG > 0, "protein > 0");
        assertTrue(m.fatG > 0, "fat > 0");
        assertTrue(m.carbsG > 0, "carbs > 0");
    }

    @Test
    void t210_fatLossMacros() {
        MacroTarget m = NutritionPlanner.calculateMacros(1500, 55, "fat_loss");
        assertNear(121, m.proteinG, 2);
        assertNear(42, m.fatG, 2);
        assertNear(160, m.carbsG, 10);
        assertTrue(m.carbsG >= 0, "carbs >= 0");
    }

    @Test
    void t211_calorieBalanceWithin50kcal() {
        String[] goals = {"muscle_gain", "fat_loss", "maintain"};
        int targetCalories = 2500;
        double weightKg = 70;
        for (String goal : goals) {
            MacroTarget m = NutritionPlanner.calculateMacros(targetCalories, weightKg, goal);
            int total = m.proteinG * 4 + m.carbsG * 4 + m.fatG * 9;
            assertTrue(Math.abs(total - targetCalories) <= 50,
                    "goal=" + goal + " total=" + total + " expected≈" + targetCalories);
        }
    }
}
