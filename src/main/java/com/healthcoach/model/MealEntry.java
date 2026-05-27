package com.healthcoach.model;

public class MealEntry {
    public String time = "";
    public String description = "";
    public int estimatedKcal;
    public int proteinG;
    public int carbsG;
    public int fatG;
    public String source = "";

    public MealEntry() {}

    public MealEntry(String time, String description, int estimatedKcal,
                     int proteinG, int carbsG, int fatG, String source) {
        this.time = time;
        this.description = description;
        this.estimatedKcal = estimatedKcal;
        this.proteinG = proteinG;
        this.carbsG = carbsG;
        this.fatG = fatG;
        this.source = source;
    }
}
