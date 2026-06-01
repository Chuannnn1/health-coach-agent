package com.healthcoach.agent;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.PreferencesStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.ExecutionResult;
import com.healthcoach.model.LogInstruction;
import com.healthcoach.model.MealEntry;
import com.healthcoach.model.PatchInstruction;
import com.healthcoach.model.Preferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code <PATCH>}/{@code <LOG>} blocks emitted by the LLM, applies them to the
 * memory/skill/log stores, and returns the user-facing text stripped of those tags.
 */
public class PatchExecutor {

    private static final Pattern PATCH_PATTERN = Pattern.compile("<PATCH>\\s*(.*?)\\s*</PATCH>", Pattern.DOTALL);
    private static final Pattern LOG_PATTERN   = Pattern.compile("<LOG>\\s*(.*?)\\s*</LOG>",   Pattern.DOTALL);
    private static final Pattern THINK_PATTERN = Pattern.compile("<think>.*?</think>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern UNCLOSED_THINK = Pattern.compile("<think>.*", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final MemoryStore memoryStore;
    private final SkillManager skillManager;
    private final DailyLogStore dailyLogStore;
    private final PreferencesStore preferencesStore;
    private final Runnable onPreferencesChanged;
    private final Gson gson;

    private PatchListener listener;

    /** Construct an executor without preferences support (tests / legacy callers). */
    public PatchExecutor(MemoryStore memoryStore, SkillManager skillManager, DailyLogStore dailyLogStore) {
        this(memoryStore, skillManager, dailyLogStore, null, null);
    }

    /** Full constructor with preferences mutation and a reschedule callback. */
    public PatchExecutor(MemoryStore memoryStore, SkillManager skillManager, DailyLogStore dailyLogStore,
                         PreferencesStore preferencesStore, Runnable onPreferencesChanged) {
        this.memoryStore = memoryStore;
        this.skillManager = skillManager;
        this.dailyLogStore = dailyLogStore;
        this.preferencesStore = preferencesStore;
        this.onPreferencesChanged = onPreferencesChanged;
        this.gson = new Gson();
    }

    /** Set an optional listener that receives callbacks on every successful PATCH/LOG operation. */
    public void setListener(PatchListener listener) {
        this.listener = listener;
    }

    /** Parse PATCH/LOG blocks from rawReply, mutate stores, and return the cleaned reply text plus a per-block result log. */
    public ExecutionResult execute(String rawReply) {
        if (rawReply == null) {
            return new ExecutionResult("", new ArrayList<>());
        }
        List<String> patchResults = new ArrayList<>();

        Matcher patchMatcher = PATCH_PATTERN.matcher(rawReply);
        while (patchMatcher.find()) {
            String body = patchMatcher.group(1);
            try {
                PatchInstruction p;
                try {
                    p = gson.fromJson(body, PatchInstruction.class);
                } catch (JsonSyntaxException jse) {
                    patchResults.add("PATCH skipped (malformed JSON): " + body);
                    continue;
                }
                if (p == null || p.target == null) {
                    patchResults.add("PATCH skipped (malformed JSON): " + body);
                    continue;
                }
                routePatch(p, patchResults);
            } catch (Exception ex) {
                patchResults.add("PATCH failed: " + ex.getMessage());
            }
        }

        Matcher logMatcher = LOG_PATTERN.matcher(rawReply);
        while (logMatcher.find()) {
            String body = logMatcher.group(1);
            try {
                LogInstruction li;
                try {
                    li = gson.fromJson(body, LogInstruction.class);
                } catch (JsonSyntaxException jse) {
                    patchResults.add("LOG skipped (malformed JSON)");
                    continue;
                }
                if (li == null) {
                    patchResults.add("LOG skipped (malformed JSON)");
                    continue;
                }
                MealEntry meal = new MealEntry(li.time, li.description,
                        li.estimatedKcal, li.proteinG, li.carbsG, li.fatG, "llm_estimate");
                dailyLogStore.addMeal(meal);
                patchResults.add("meal logged: " + li.description);
                fireListener(false, "meal logged: " + li.description + " " + li.estimatedKcal + " kcal");
            } catch (Exception ex) {
                patchResults.add("LOG failed: " + ex.getMessage());
            }
        }

        String clean = PATCH_PATTERN.matcher(rawReply).replaceAll("");
        clean = LOG_PATTERN.matcher(clean).replaceAll("");
        clean = THINK_PATTERN.matcher(clean).replaceAll("");
        clean = UNCLOSED_THINK.matcher(clean).replaceAll("");
        clean = clean.trim();
        clean = ResponseSanitizer.sanitize(clean);
        return new ExecutionResult(clean, patchResults);
    }

    /** Dispatch a parsed PatchInstruction to the appropriate store and append a result line. */
    private void routePatch(PatchInstruction p, List<String> patchResults) {
        String target = p.target;
        if ("user_profile".equals(target)) {
            memoryStore.updateField(p.field, p.value);
            patchResults.add("user_profile." + p.field + " updated");
            fireListener(true, "user_profile." + p.field + " updated");
            return;
        }
        if (target.startsWith("skill/")) {
            String skillName = target.substring("skill/".length());
            boolean ok = skillManager.patchSkill(skillName, p.action, p.content);
            String result = "skill/" + skillName + ":" + p.action + " " + (ok ? "ok" : "failed");
            patchResults.add(result);
            if (ok) fireListener(true, result);
            return;
        }
        if ("preferences".equals(target)) {
            if (preferencesStore == null) {
                patchResults.add("preferences patch skipped (no store wired)");
                return;
            }
            applyPreferencesPatch(p, patchResults);
            return;
        }
        if ("daily_log".equals(target)) {
            applyDailyLogPatch(p, patchResults);
            return;
        }
        if ("memory".equals(target)) {
            String action = p.action == null ? "" : p.action;
            switch (action) {
                case "add": {
                    boolean ok = memoryStore.addMemory(p.content);
                    patchResults.add("memory add: " + (ok ? "ok" : "failed"));
                    if (ok) fireListener(true, "memory add: " + p.content);
                    break;
                }
                case "remove": {
                    boolean ok = memoryStore.removeMemory(p.content);
                    patchResults.add("memory remove: " + (ok ? "ok" : "failed"));
                    if (ok) fireListener(true, "memory remove: ok");
                    break;
                }
                case "replace": {
                    if (p.content == null) {
                        patchResults.add("memory replace: failed");
                        break;
                    }
                    String[] parts = p.content.split("\\|\\|\\|", 2);
                    if (parts.length != 2) {
                        patchResults.add("memory replace: failed");
                        break;
                    }
                    boolean ok = memoryStore.replaceMemory(parts[0], parts[1]);
                    patchResults.add("memory replace: " + (ok ? "ok" : "failed"));
                    if (ok) fireListener(true, "memory replace: ok");
                    break;
                }
                default:
                    patchResults.add("memory action unknown: " + action);
            }
            return;
        }
        patchResults.add("Unknown patch target: " + target);
    }

    private void applyDailyLogPatch(PatchInstruction p, List<String> patchResults) {
        String action = p.action == null ? "" : p.action;
        if ("remove_meal".equals(action)) {
            int index = -1;
            try {
                if (p.value instanceof Number) {
                    index = ((Number) p.value).intValue();
                } else if (p.value instanceof String) {
                    index = Integer.parseInt(((String) p.value).trim());
                } else if (p.value != null) {
                    index = (int) Double.parseDouble(p.value.toString());
                }
            } catch (NumberFormatException ignored) {}
            if (index < 0) {
                patchResults.add("daily_log remove_meal failed (invalid index)");
                return;
            }
            boolean ok = dailyLogStore.removeMeal(index);
            String desc = "meal #" + index + " removed";
            patchResults.add(ok ? desc : "daily_log remove_meal failed (index out of range)");
            if (ok) fireListener(true, desc);
            return;
        }
        patchResults.add("daily_log action unknown: " + action);
    }

    /** Mutate preferences (schedule / timezone) and fire reschedule. */
    private void applyPreferencesPatch(PatchInstruction p, List<String> patchResults) {
        String action = p.action == null ? "" : p.action;
        switch (action) {
            case "set_meals": {
                List<String> times = parseTimesList(p.value);
                if (times == null) {
                    patchResults.add("preferences set_meals failed (bad value)");
                    return;
                }
                Preferences updated = preferencesStore.setMealReminders(times);
                String mealDesc = "preferences.mealReminders = " + updated.mealReminders;
                patchResults.add(mealDesc);
                fireListener(true, mealDesc);
                fireReschedule();
                return;
            }
            case "clear_meals": {
                Preferences updated = preferencesStore.setMealReminders(new ArrayList<>());
                patchResults.add("preferences.mealReminders cleared");
                fireListener(true, "preferences.mealReminders cleared");
                fireReschedule();
                return;
            }
            case "set_workout": {
                String time = asString(p.value);
                preferencesStore.setWorkoutReminder(time);
                String workoutDesc = "preferences.workoutReminder = " + time;
                patchResults.add(workoutDesc);
                fireListener(true, workoutDesc);
                fireReschedule();
                return;
            }
            case "clear_workout": {
                preferencesStore.setWorkoutReminder("");
                patchResults.add("preferences.workoutReminder cleared");
                fireListener(true, "preferences.workoutReminder cleared");
                fireReschedule();
                return;
            }
            case "set_weekly": {
                String v = asString(p.value);
                preferencesStore.setWeeklySummary(v);
                String weeklyDesc = "preferences.weeklySummary = " + v;
                patchResults.add(weeklyDesc);
                fireListener(true, weeklyDesc);
                fireReschedule();
                return;
            }
            case "set_timezone": {
                String v = asString(p.value);
                preferencesStore.setTimezone(v);
                String tzDesc = "preferences.timezone = " + v;
                patchResults.add(tzDesc);
                fireListener(true, tzDesc);
                fireReschedule();
                return;
            }
            default:
                patchResults.add("preferences action unknown: " + action);
        }
    }

    /** Accept "07:30,12:00,18:00", ["07:30","12:00"], or comma+space variants. */
    @SuppressWarnings("unchecked")
    private List<String> parseTimesList(Object value) {
        if (value == null) return null;
        if (value instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) value) {
                if (o != null) out.add(o.toString().trim());
            }
            return out;
        }
        if (value instanceof String) {
            String[] parts = ((String) value).split(",");
            List<String> out = new ArrayList<>();
            for (String s : parts) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return out;
        }
        return null;
    }

    private String asString(Object value) {
        if (value == null) return "";
        return value.toString().trim();
    }

    private void fireReschedule() {
        if (onPreferencesChanged != null) {
            try { onPreferencesChanged.run(); } catch (Exception ignored) {}
        }
    }

    private void fireListener(boolean isPatch, String description) {
        if (listener == null) return;
        try {
            if (isPatch) listener.onPatchApplied(description);
            else listener.onLogApplied(description);
        } catch (Exception ignored) {}
    }
}
