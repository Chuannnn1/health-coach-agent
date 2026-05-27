package com.healthcoach.health;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for WorkoutPlanner weekly plan generation. */
class WorkoutPlannerTest {

    @Test
    void t11_1_threeDaysContainsPushPullLegs() {
        String plan = WorkoutPlanner.generateWeeklyPlan("muscle_gain", 3);
        assertTrue(plan.contains("Push"));
        assertTrue(plan.contains("Pull"));
        assertTrue(plan.contains("Legs"));
    }

    @Test
    void t11_2_fourDaysContainsUpperLower() {
        String plan = WorkoutPlanner.generateWeeklyPlan("fat_loss", 4);
        assertTrue(plan.contains("Upper"));
        assertTrue(plan.contains("Lower"));
    }

    @Test
    void t11_3_muscleGainNote() {
        String plan = WorkoutPlanner.generateWeeklyPlan("muscle_gain", 3);
        assertTrue(plan.contains("增肌建議"));
    }

    @Test
    void t11_4_fatLossNote() {
        String plan = WorkoutPlanner.generateWeeklyPlan("fat_loss", 4);
        assertTrue(plan.contains("減脂建議"));
    }

    @Test
    void t11_5_invalidDaysFallback() {
        String plan = WorkoutPlanner.generateWeeklyPlan("maintain", 5);
        assertTrue(plan.contains("建議每週訓練 3-4 天"));
    }
}
