package com.healthcoach.memory;

import com.healthcoach.model.MemoryData;
import com.healthcoach.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    @Test
    void t3_1_loadFromFreshDirReturnsEmptyDefaults(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        UserProfile p = store.loadUserProfile();
        assertEquals("", p.name);
        assertEquals(0, p.bmr);
    }

    @Test
    void t3_2_saveAndLoadRoundTripPreservesFields(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        UserProfile p = new UserProfile();
        p.name = "小明";
        p.heightCm = 175.5;
        p.age = 22;
        p.gender = "male";
        p.goal = "muscle_gain";
        p.bmr = 1735;
        p.dietaryRestrictions = List.of("無牛", "無羊");
        store.saveUserProfile(p);

        UserProfile loaded = store.loadUserProfile();
        assertEquals("小明", loaded.name);
        assertEquals(175.5, loaded.heightCm, 0.0001);
        assertEquals(22, loaded.age);
        assertEquals("male", loaded.gender);
        assertEquals("muscle_gain", loaded.goal);
        assertEquals(1735, loaded.bmr);
        assertEquals(List.of("無牛", "無羊"), loaded.dietaryRestrictions);
    }

    @Test
    void t3_3_updateFieldName(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        assertTrue(store.updateField("name", "小明"));
        assertEquals("小明", store.loadUserProfile().name);
    }

    @Test
    void t3_4_updateFieldDouble(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        assertTrue(store.updateField("heightCm", 175.0));
        assertEquals(175.0, store.loadUserProfile().heightCm, 0.0001);
    }

    @Test
    void t3_5_updateUnknownFieldReturnsFalse(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        assertFalse(store.updateField("nonexistent_field", "x"));
    }

    @Test
    void t3_6_addMemoryCreatesEntry(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        assertTrue(store.addMemory("test entry"));
        MemoryData data = store.loadMemory();
        assertEquals(1, data.entries.size());
        assertEquals("test entry", data.entries.get(0).content);
    }

    @Test
    void t3_7_addMemoryRespectsMaxEntries(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        for (int i = 0; i < 20; i++) {
            assertTrue(store.addMemory("e" + i), "entry " + i + " should be accepted");
        }
        assertFalse(store.addMemory("e20"));
    }

    @Test
    void t3_8_removeMemoryBySubstring(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        store.addMemory("test entry");
        assertTrue(store.removeMemory("test"));
        assertTrue(store.loadMemory().entries.isEmpty());
    }

    @Test
    void t3_9_removeMemoryNoMatchReturnsFalse(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        assertFalse(store.removeMemory("nonexistent"));
    }

    @Test
    void t3_10_replaceMemoryBySubstring(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        store.addMemory("test entry");
        assertTrue(store.replaceMemory("test", "updated content"));
        assertEquals("updated content", store.loadMemory().entries.get(0).content);
    }

    @Test
    void t3_11_userProfileSummaryContainsKeyFields(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        UserProfile p = new UserProfile();
        p.name = "小明";
        p.age = 22;
        p.gender = "male";
        p.goal = "muscle_gain";
        store.saveUserProfile(p);

        String summary = store.getUserProfileSummary();
        assertTrue(summary.contains("小明"), summary);
        assertTrue(summary.contains("22"), summary);
        assertTrue(summary.contains("male"), summary);
        assertTrue(summary.contains("muscle_gain"), summary);
    }

    @Test
    void t3_12_memorySummaryJoinsEntries(@TempDir Path tempDir) {
        MemoryStore store = new MemoryStore(tempDir);
        store.addMemory("A");
        store.addMemory("B");
        String summary = store.getMemorySummary();
        assertTrue(summary.contains("A"), summary);
        assertTrue(summary.contains("B"), summary);
        assertTrue(summary.contains("§"), summary);
    }
}
