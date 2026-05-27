package com.healthcoach.model;

import java.util.ArrayList;
import java.util.List;

public class DailyLog {
    public String date = "";
    public List<MealEntry> meals = new ArrayList<>();
    public WorkoutEntry workout;
    public DailySummary dailySummary = new DailySummary();
    public Double weightKg;
    public String notes = "";
}
