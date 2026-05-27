package com.healthcoach.health;

/** Pure static utility for BMR, TDEE, and target calorie calculations. */
public class BmrCalculator {

    private BmrCalculator() {
        // utility class
    }

    /** Mifflin-St Jeor BMR: male = 10w+6.25h-5a+5; female = 10w+6.25h-5a-161. */
    public static int calculateBmr(double weightKg, double heightCm, int age, String gender) {
        if (gender == null) {
            throw new IllegalArgumentException("Unknown gender: null");
        }
        String g = gender.toLowerCase();
        double base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age;
        double bmr;
        if (g.equals("male")) {
            bmr = base + 5.0;
        } else if (g.equals("female")) {
            bmr = base - 161.0;
        } else {
            throw new IllegalArgumentException("Unknown gender: " + gender);
        }
        return (int) Math.round(bmr);
    }

    /** Apply activity multiplier to BMR to produce TDEE. */
    public static int calculateTdee(int bmr, String activityLevel) {
        if (activityLevel == null) {
            throw new IllegalArgumentException("Unknown activityLevel: null");
        }
        double multiplier;
        switch (activityLevel.toLowerCase()) {
            case "sedentary":
                multiplier = 1.2;
                break;
            case "light":
                multiplier = 1.375;
                break;
            case "moderate":
                multiplier = 1.55;
                break;
            case "active":
                multiplier = 1.725;
                break;
            case "very_active":
                multiplier = 1.9;
                break;
            default:
                throw new IllegalArgumentException("Unknown activityLevel: " + activityLevel);
        }
        return (int) Math.round(bmr * multiplier);
    }

    /** Adjust TDEE by goal: muscle_gain=+400, fat_loss=-400, maintain=0. */
    public static int calculateTargetCalories(int tdee, String goal) {
        if (goal == null) {
            throw new IllegalArgumentException("Unknown goal: null");
        }
        switch (goal.toLowerCase()) {
            case "muscle_gain":
                return tdee + 400;
            case "fat_loss":
                return tdee - 400;
            case "maintain":
                return tdee;
            default:
                throw new IllegalArgumentException("Unknown goal: " + goal);
        }
    }
}
