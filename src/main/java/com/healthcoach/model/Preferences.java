package com.healthcoach.model;

import java.util.ArrayList;
import java.util.List;

/** User-mutable schedule and runtime preferences (persisted to data/preferences.json). */
public class Preferences {
    public String timezone = "Asia/Taipei";
    public List<String> mealReminders = new ArrayList<>();
    public String workoutReminder = "";
    public String weeklySummary = "";
    public String effort = "medium";  // low | medium | high

    public Preferences() {}

    public Preferences(String timezone, List<String> mealReminders, String workoutReminder, String weeklySummary) {
        this(timezone, mealReminders, workoutReminder, weeklySummary, "medium");
    }

    public Preferences(String timezone, List<String> mealReminders, String workoutReminder, String weeklySummary, String effort) {
        this.timezone = timezone;
        this.mealReminders = mealReminders == null ? new ArrayList<>() : mealReminders;
        this.workoutReminder = workoutReminder == null ? "" : workoutReminder;
        this.weeklySummary = weeklySummary == null ? "" : weeklySummary;
        this.effort = (effort == null || effort.isBlank()) ? "medium" : effort;
    }
}
