package com.healthcoach.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.healthcoach.model.MemoryData;
import com.healthcoach.model.MemoryEntry;
import com.healthcoach.model.UserProfile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Persists the user's profile and long-term memory entries as pretty JSON files in a data directory.
 */
public class MemoryStore {

    private static final String USER_PROFILE_FILE = "user_profile.json";
    private static final String MEMORY_FILE = "memory.json";

    private final Path dataDir;
    private final Gson gson;

    /** Create a store rooted at the given data directory; ensures the directory exists. */
    public MemoryStore(Path dataDir) {
        this.dataDir = dataDir;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Load the user profile from disk; returns a fresh UserProfile if the file is missing. */
    public UserProfile loadUserProfile() {
        Path file = dataDir.resolve(USER_PROFILE_FILE);
        if (!Files.exists(file)) {
            return new UserProfile();
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            UserProfile parsed = gson.fromJson(reader, UserProfile.class);
            return parsed != null ? parsed : new UserProfile();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Persist the user profile as pretty JSON. */
    public void saveUserProfile(UserProfile p) {
        Path file = dataDir.resolve(USER_PROFILE_FILE);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            gson.toJson(p, writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Set a single field on the user profile by name and persist; returns false if the field is unknown. */
    public boolean updateField(String fieldName, Object value) {
        UserProfile profile = loadUserProfile();
        Field field;
        try {
            field = UserProfile.class.getField(fieldName);
        } catch (NoSuchFieldException e) {
            return false;
        }
        try {
            Class<?> type = field.getType();
            if (type == int.class || type == Integer.class) {
                if (value instanceof Number n) {
                    field.setInt(profile, n.intValue());
                } else if (value instanceof String s) {
                    field.setInt(profile, Integer.parseInt(s.trim()));
                } else {
                    return false;
                }
            } else if (type == double.class || type == Double.class) {
                if (value instanceof Number n) {
                    field.setDouble(profile, n.doubleValue());
                } else if (value instanceof String s) {
                    field.setDouble(profile, Double.parseDouble(s.trim()));
                } else {
                    return false;
                }
            } else if (type == String.class) {
                field.set(profile, value == null ? "" : value.toString());
            } else if (List.class.isAssignableFrom(type)) {
                if (value instanceof List<?> list) {
                    List<String> coerced = new ArrayList<>();
                    for (Object o : list) {
                        coerced.add(o == null ? "" : o.toString());
                    }
                    field.set(profile, coerced);
                } else {
                    return false;
                }
            } else {
                field.set(profile, value);
            }
        } catch (IllegalAccessException | NumberFormatException e) {
            return false;
        }
        profile.updatedAt = Instant.now().toString();
        saveUserProfile(profile);
        return true;
    }

    /** Load the memory store from disk; returns a fresh MemoryData if the file is missing. */
    public MemoryData loadMemory() {
        Path file = dataDir.resolve(MEMORY_FILE);
        if (!Files.exists(file)) {
            return new MemoryData();
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            MemoryData parsed = gson.fromJson(reader, MemoryData.class);
            if (parsed == null) {
                return new MemoryData();
            }
            if (parsed.entries == null) {
                parsed.entries = new ArrayList<>();
            }
            return parsed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Persist the given memory data as pretty JSON. */
    private void saveMemory(MemoryData data) {
        Path file = dataDir.resolve(MEMORY_FILE);
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Append a memory entry; returns false if maxEntries or maxChars caps would be exceeded. */
    public boolean addMemory(String content) {
        if (content == null) {
            return false;
        }
        MemoryData data = loadMemory();
        if (data.entries.size() >= data.maxEntries) {
            return false;
        }
        int currentChars = 0;
        for (MemoryEntry e : data.entries) {
            if (e.content != null) {
                currentChars += e.content.length();
            }
        }
        if (currentChars + content.length() > data.maxChars) {
            return false;
        }
        String now = Instant.now().toString();
        String id = "mem_" + System.currentTimeMillis();
        data.entries.add(new MemoryEntry(id, content, now, now));
        saveMemory(data);
        return true;
    }

    /** Remove the first memory entry whose content contains the substring; returns false if no match. */
    public boolean removeMemory(String substring) {
        if (substring == null) {
            return false;
        }
        MemoryData data = loadMemory();
        Iterator<MemoryEntry> it = data.entries.iterator();
        while (it.hasNext()) {
            MemoryEntry entry = it.next();
            if (entry.content != null && entry.content.contains(substring)) {
                it.remove();
                saveMemory(data);
                return true;
            }
        }
        return false;
    }

    /** Replace the content of the first memory entry containing oldSubstring; returns false if no match. */
    public boolean replaceMemory(String oldSubstring, String newContent) {
        if (oldSubstring == null || newContent == null) {
            return false;
        }
        MemoryData data = loadMemory();
        for (MemoryEntry entry : data.entries) {
            if (entry.content != null && entry.content.contains(oldSubstring)) {
                entry.content = newContent;
                entry.updatedAt = Instant.now().toString();
                saveMemory(data);
                return true;
            }
        }
        return false;
    }

    /** Render a one-paragraph zh-TW summary of the user profile. */
    public String getUserProfileSummary() {
        UserProfile p = loadUserProfile();
        if (p.name == null || p.name.isEmpty()) {
            return "使用者資料：尚未設定。請告訴我你的身高體重年齡性別、活動程度與目標。";
        }
        String restrictions = (p.dietaryRestrictions == null || p.dietaryRestrictions.isEmpty())
                ? "" : String.join(",", p.dietaryRestrictions);
        return "使用者資料：" + p.name + "，" + p.age + "歲" + p.gender + "，"
                + p.heightCm + "cm/" + p.weightKg + "kg，" + p.activityLevel + "活動，目標" + p.goal + "。\n"
                + "BMR=" + p.bmr + " TDEE=" + p.tdee + " 目標熱量=" + p.targetCalories + "kcal"
                + " 蛋白質=" + p.targetProteinG + "g 碳水=" + p.targetCarbsG + "g 脂肪=" + p.targetFatG + "g\n"
                + "飲食限制：" + restrictions + " 備註：" + (p.notes == null ? "" : p.notes);
    }

    /** Render the long-term memory entries joined with " § ". */
    public String getMemorySummary() {
        MemoryData data = loadMemory();
        if (data.entries == null || data.entries.isEmpty()) {
            return "長期記憶：（空）";
        }
        StringBuilder sb = new StringBuilder("長期記憶：\n");
        for (int i = 0; i < data.entries.size(); i++) {
            if (i > 0) {
                sb.append(" § ");
            }
            sb.append(data.entries.get(i).content);
        }
        return sb.toString();
    }
}
