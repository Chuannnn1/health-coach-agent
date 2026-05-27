package com.healthcoach.health;

import com.healthcoach.model.MacroTarget;

/** Pure static utility for computing macro nutrient targets. */
public class NutritionPlanner {

    private NutritionPlanner() {
        // utility class
    }

    /** Compute protein/carbs/fat macros for a target calorie level and goal. */
    public static MacroTarget calculateMacros(int targetCalories, double weightKg, String goal) {
        if (goal == null) {
            throw new IllegalArgumentException("Unknown goal: null");
        }
        double proteinPerKg;
        switch (goal.toLowerCase()) {
            case "muscle_gain":
                proteinPerKg = 2.0;
                break;
            case "fat_loss":
                proteinPerKg = 2.2;
                break;
            case "maintain":
                proteinPerKg = 1.6;
                break;
            default:
                throw new IllegalArgumentException("Unknown goal: " + goal);
        }

        int fat = (int) Math.round(targetCalories * 0.25 / 9.0);
        int protein = (int) Math.round(proteinPerKg * weightKg);
        long carbsRaw = Math.round((targetCalories - protein * 4.0 - fat * 9.0) / 4.0);
        int carbs = (int) Math.max(0, carbsRaw);

        return new MacroTarget(protein, carbs, fat);
    }
}
