package com.healthcoach.health;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for BmrCalculator. */
class BmrCalculatorTest {

    private static void assertNear(int expected, int actual, int tolerance) {
        assertTrue(Math.abs(expected - actual) <= tolerance,
                "expected " + expected + " ± " + tolerance + " but was " + actual);
    }

    // NOTE: Spec test values (1735 male, 1340 female) do not match canonical
    // Mifflin-St Jeor results for 72/175/22 and 60/163/25. Canonical formula
    // (male: +5, female: -161) yields 1709 and 1333. Implementation follows
    // canonical Mifflin; expected values adjusted to match. See SPEC_NOTES.md.
    @Test
    void t21_bmrMale() {
        assertNear(1709, BmrCalculator.calculateBmr(72, 175, 22, "male"), 5);
    }

    @Test
    void t22_bmrFemale() {
        assertNear(1333, BmrCalculator.calculateBmr(60, 163, 25, "female"), 5);
    }

    @Test
    void t23_bmrInvalidGender() {
        assertThrows(IllegalArgumentException.class,
                () -> BmrCalculator.calculateBmr(70, 170, 25, "alien"));
    }

    @Test
    void t24_tdeeModerate() {
        assertNear(2689, BmrCalculator.calculateTdee(1735, "moderate"), 5);
    }

    @Test
    void t25_tdeeInvalidActivity() {
        assertThrows(IllegalArgumentException.class,
                () -> BmrCalculator.calculateTdee(1735, "extreme"));
    }

    @Test
    void t26_targetMuscleGain() {
        assertNear(3089, BmrCalculator.calculateTargetCalories(2689, "muscle_gain"), 5);
    }

    @Test
    void t27_targetFatLoss() {
        assertNear(2289, BmrCalculator.calculateTargetCalories(2689, "fat_loss"), 5);
    }

    @Test
    void t28_targetMaintain() {
        assertEquals(2689, BmrCalculator.calculateTargetCalories(2689, "maintain"));
    }
}
