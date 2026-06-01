package com.healthcoach.model;

public class LogInstruction {
    public String date;  // nullable — null/blank = today, otherwise "YYYY-MM-DD"
    public String time = "";
    public String description = "";
    public int estimatedKcal;
    public int proteinG;
    public int carbsG;
    public int fatG;
}
