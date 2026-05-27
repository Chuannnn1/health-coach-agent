package com.healthcoach.agent;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.ExecutionResult;
import com.healthcoach.model.LogInstruction;
import com.healthcoach.model.MealEntry;
import com.healthcoach.model.PatchInstruction;

import java.util.ArrayList;
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

    private final MemoryStore memoryStore;
    private final SkillManager skillManager;
    private final DailyLogStore dailyLogStore;
    private final Gson gson;

    /** Construct an executor that mutates the supplied stores when patches/logs are applied. */
    public PatchExecutor(MemoryStore memoryStore, SkillManager skillManager, DailyLogStore dailyLogStore) {
        this.memoryStore = memoryStore;
        this.skillManager = skillManager;
        this.dailyLogStore = dailyLogStore;
        this.gson = new Gson();
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
            } catch (Exception ex) {
                patchResults.add("LOG failed: " + ex.getMessage());
            }
        }

        String clean = PATCH_PATTERN.matcher(rawReply).replaceAll("");
        clean = LOG_PATTERN.matcher(clean).replaceAll("");
        clean = clean.trim();
        return new ExecutionResult(clean, patchResults);
    }

    /** Dispatch a parsed PatchInstruction to the appropriate store and append a result line. */
    private void routePatch(PatchInstruction p, List<String> patchResults) {
        String target = p.target;
        if ("user_profile".equals(target)) {
            memoryStore.updateField(p.field, p.value);
            patchResults.add("user_profile." + p.field + " updated");
            return;
        }
        if (target.startsWith("skill/")) {
            String skillName = target.substring("skill/".length());
            boolean ok = skillManager.patchSkill(skillName, p.action, p.content);
            patchResults.add("skill/" + skillName + ":" + p.action + " " + (ok ? "ok" : "failed"));
            return;
        }
        if ("memory".equals(target)) {
            String action = p.action == null ? "" : p.action;
            switch (action) {
                case "add": {
                    boolean ok = memoryStore.addMemory(p.content);
                    patchResults.add("memory add: " + (ok ? "ok" : "failed"));
                    break;
                }
                case "remove": {
                    boolean ok = memoryStore.removeMemory(p.content);
                    patchResults.add("memory remove: " + (ok ? "ok" : "failed"));
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
                    break;
                }
                default:
                    patchResults.add("memory action unknown: " + action);
            }
            return;
        }
        patchResults.add("Unknown patch target: " + target);
    }
}
