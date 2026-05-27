package com.healthcoach.agent;

import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.UserProfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Assembles the agent's full system prompt by concatenating the persona, user profile,
 * long-term memory, skills index, today's log summary, and the self-learning patch protocol.
 */
public class PromptBuilder {

    private static final String SECTION_SEPARATOR = "\n\n---\n\n";

    private static final String PATCH_INSTRUCTIONS = ""
            + "# 自我學習指令\n\n"
            + "如果使用者糾正你的估算、提供新事實、或更新個人資料，你可以在回覆中加入特殊標記。\n"
            + "這些標記會被系統解析後從顯示給使用者的訊息中移除。\n\n"
            + "## <PATCH> — 修改長期記憶或技能\n\n"
            + "格式：<PATCH>{...JSON...}</PATCH>\n\n"
            + "可用 target：\n"
            + "- \"user_profile\"：修改個人資料欄位\n"
            + "  範例：<PATCH>{\"target\":\"user_profile\",\"action\":\"update\",\"field\":\"weightKg\",\"value\":70.5}</PATCH>\n"
            + "- \"memory\"：長期記憶條目（最多 20 條）\n"
            + "  範例：<PATCH>{\"target\":\"memory\",\"action\":\"add\",\"content\":\"使用者不吃牛肉\"}</PATCH>\n"
            + "  範例：<PATCH>{\"target\":\"memory\",\"action\":\"remove\",\"content\":\"關鍵字\"}</PATCH>\n"
            + "  範例：<PATCH>{\"target\":\"memory\",\"action\":\"replace\",\"content\":\"舊內容|||新內容\"}</PATCH>\n"
            + "- \"skill/{name}\"：修正知識模組（例：skill/nutrition-advice）\n"
            + "  範例：<PATCH>{\"target\":\"skill/nutrition-advice\",\"action\":\"append\",\"content\":\"雞腿便當實際 700kcal\"}</PATCH>\n"
            + "  範例：<PATCH>{\"target\":\"skill/nutrition-advice\",\"action\":\"replace\",\"content\":\"舊文字|||新文字\"}</PATCH>\n\n"
            + "## <LOG> — 記錄一餐\n\n"
            + "格式：<LOG>{...JSON...}</LOG>\n\n"
            + "範例：<LOG>{\"time\":\"12:30\",\"description\":\"雞腿便當\",\"estimatedKcal\":850,\"proteinG\":35,\"carbsG\":95,\"fatG\":28}</LOG>\n\n"
            + "## 使用原則\n\n"
            + "- 只在資訊明確時才下 PATCH；不確定就用一般文字回覆。\n"
            + "- 每次回覆可有多個 PATCH 或 LOG，但避免無意義刷新。\n"
            + "- PATCH/LOG 不會顯示給使用者，使用者只看到 PATCH/LOG 以外的文字。\n";

    private final String soulContent;
    private final MemoryStore memoryStore;
    private final SkillManager skillManager;
    private final DailyLogStore dailyLogStore;

    /** Construct the builder; eagerly loads soul.md from the classpath once and caches it. */
    public PromptBuilder(MemoryStore memoryStore, SkillManager skillManager, DailyLogStore dailyLogStore)
            throws IOException {
        this.memoryStore = memoryStore;
        this.skillManager = skillManager;
        this.dailyLogStore = dailyLogStore;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("soul.md")) {
            if (is == null) {
                throw new IOException("soul.md not found on classpath");
            }
            this.soulContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Assemble the full system prompt from all memory layers. */
    public String buildSystemPrompt() {
        UserProfile profile = memoryStore.loadUserProfile();
        return String.join(
                SECTION_SEPARATOR,
                soulContent,
                memoryStore.getUserProfileSummary(),
                memoryStore.getMemorySummary(),
                skillManager.getSkillsIndexText(),
                dailyLogStore.getTodaySummaryText(profile),
                PATCH_INSTRUCTIONS);
    }
}
