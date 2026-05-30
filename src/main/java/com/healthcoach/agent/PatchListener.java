package com.healthcoach.agent;

/**
 * Callback for PATCH/LOG execution events, allowing the bot to display
 * real-time status updates (e.g., "meal logged: 雞腿便當 850 kcal").
 */
public interface PatchListener {
    void onPatchApplied(String description);
    void onLogApplied(String description);
}
