package com.healthcoach.model;

public class MemoryEntry {
    public String id = "";
    public String content = "";
    public String createdAt = "";
    public String updatedAt = "";

    public MemoryEntry() {}

    public MemoryEntry(String id, String content, String createdAt, String updatedAt) {
        this.id = id;
        this.content = content;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
