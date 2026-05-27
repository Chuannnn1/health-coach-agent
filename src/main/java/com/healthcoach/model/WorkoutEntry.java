package com.healthcoach.model;

public class WorkoutEntry {
    public String type = "";
    public String description = "";
    public int durationMin;
    public boolean completed;

    public WorkoutEntry() {}

    public WorkoutEntry(String type, String description, int durationMin, boolean completed) {
        this.type = type;
        this.description = description;
        this.durationMin = durationMin;
        this.completed = completed;
    }
}
