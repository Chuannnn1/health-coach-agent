package com.healthcoach.model;

import java.util.ArrayList;
import java.util.List;

public class UserProfile {
    public String name = "";
    public double heightCm;
    public double weightKg;
    public int age;
    public String gender = "";
    public String activityLevel = "";
    public String goal = "";
    public int bmr;
    public int tdee;
    public int targetCalories;
    public int targetProteinG;
    public int targetCarbsG;
    public int targetFatG;
    public List<String> dietaryRestrictions = new ArrayList<>();
    public String notes = "";
    public String updatedAt = "";
}
