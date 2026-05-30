package com.healthcoach.bot;

import com.healthcoach.agent.ConversationStore;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.PreferencesStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.MealEntry;
import com.healthcoach.model.Preferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SlashRouterTest {

    @TempDir Path tempDir;
    private SlashRouter router;
    private ConversationStore conv;
    private MemoryStore memoryStore;
    private DailyLogStore dailyLogStore;
    private PreferencesStore prefStore;
    private final int[] rescheduleCalls = {0};

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("user_profile.json"),
                "{\"name\":\"\",\"heightCm\":0,\"weightKg\":0,\"age\":0,\"gender\":\"\"," +
                "\"activityLevel\":\"\",\"goal\":\"\",\"bmr\":0,\"tdee\":0,\"targetCalories\":2800," +
                "\"targetProteinG\":140,\"targetCarbsG\":0,\"targetFatG\":0," +
                "\"dietaryRestrictions\":[],\"notes\":\"\",\"updatedAt\":\"\"}");
        Files.writeString(tempDir.resolve("memory.json"),
                "{\"entries\":[],\"maxEntries\":20,\"maxChars\":2200}");
        Files.createDirectories(tempDir.resolve("skills/nutrition-advice"));
        Files.writeString(tempDir.resolve("skills/nutrition-advice/SKILL.md"),
                "---\nname: nutrition-advice\ndescription: 飲食建議\n---\n# body\n台灣常見食物\n");
        Files.createDirectories(tempDir.resolve("logs"));

        memoryStore = new MemoryStore(tempDir);
        dailyLogStore = new DailyLogStore(tempDir);
        SkillManager skillManager = new SkillManager(tempDir);
        conv = new ConversationStore();
        prefStore = new PreferencesStore(tempDir);
        prefStore.save(new Preferences("Asia/Taipei",
                java.util.List.of("07:30", "12:00", "18:00"), "20:00", "SUN 21:00"));
        rescheduleCalls[0] = 0;
        router = new SlashRouter(memoryStore, skillManager, dailyLogStore, conv,
                prefStore, () -> rescheduleCalls[0]++);
    }

    @Test
    void unknownTextReturnsNotHandled() {
        assertInstanceOf(SlashRouter.Action.NotHandled.class, router.route("1", "你好"));
    }

    @Test
    void unknownSlashReturnsNotHandled() {
        assertInstanceOf(SlashRouter.Action.NotHandled.class, router.route("1", "/unknown"));
    }

    @Test
    void helpReturnsReplyContainingCommands() {
        SlashRouter.Action a = router.route("1", "/help");
        assertInstanceOf(SlashRouter.Action.Reply.class, a);
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("/profile"));
        assertTrue(text.contains("/today"));
        assertTrue(text.contains("/new"));
    }

    @Test
    void newClearsConversationAndReplies() {
        conv.appendUser("1", "old msg");
        SlashRouter.Action a = router.route("1", "/new");
        assertInstanceOf(SlashRouter.Action.Reply.class, a);
        assertTrue(conv.recent("1").isEmpty());
    }

    @Test
    void profileShowsHintWhenEmpty() {
        SlashRouter.Action a = router.route("1", "/profile");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("還沒設定") || text.contains("設定"));
    }

    @Test
    void todayShowsLogSummary() {
        dailyLogStore.addMeal(new MealEntry("12:00", "雞胸便當", 700, 50, 60, 20, "test"));
        SlashRouter.Action a = router.route("1", "/today");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("雞胸便當"));
        assertTrue(text.contains("kcal") || text.contains("熱量"));
    }

    @Test
    void memoryShowsEmptyWhenNoEntries() {
        SlashRouter.Action a = router.route("1", "/memory");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("空") || text.contains("（空"));
    }

    @Test
    void skillsListsAvailableModules() {
        SlashRouter.Action a = router.route("1", "/skills");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("nutrition-advice"));
    }

    @Test
    void skillShowsContent() {
        SlashRouter.Action a = router.route("1", "/skill nutrition-advice");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("台灣常見食物"));
    }

    @Test
    void skillUnknownNameReturnsFriendlyMessage() {
        SlashRouter.Action a = router.route("1", "/skill nope");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("找不到"));
    }

    @Test
    void analyzeDelegatesToAgentWithPromptTemplate() {
        SlashRouter.Action a = router.route("1", "/analyze");
        assertInstanceOf(SlashRouter.Action.DelegateToAgent.class, a);
        String synthetic = ((SlashRouter.Action.DelegateToAgent) a).syntheticUserMessage();
        assertTrue(synthetic.contains("熱量"));
    }

    @Test
    void suggestPassesArgIntoTemplate() {
        SlashRouter.Action a = router.route("1", "/suggest 晚餐");
        assertInstanceOf(SlashRouter.Action.DelegateToAgent.class, a);
        String synthetic = ((SlashRouter.Action.DelegateToAgent) a).syntheticUserMessage();
        assertTrue(synthetic.contains("晚餐"));
    }

    @Test
    void chartDelegatesToAgent() {
        SlashRouter.Action a = router.route("1", "/chart");
        assertInstanceOf(SlashRouter.Action.DelegateToAgent.class, a);
    }

    // ---------- /reminders ----------

    @Test
    void remindersWithoutArgsShowsCurrentSettings() {
        SlashRouter.Action a = router.route("1", "/reminders");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("07:30"));
        assertTrue(text.contains("20:00"));
        assertTrue(text.contains("用法"));
    }

    @Test
    void remindersSetMealsUpdatesAndReschedules() {
        SlashRouter.Action a = router.route("1", "/reminders set meals 12:00,18:30");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("12:00"));
        assertTrue(text.contains("18:30"));
        assertFalse(text.contains("07:30"), "old time should be replaced");
        assertEquals(java.util.List.of("12:00", "18:30"), prefStore.load().mealReminders);
        assertEquals(1, rescheduleCalls[0]);
    }

    @Test
    void remindersClearMealsEmptiesAndReschedules() {
        router.route("1", "/reminders clear meals");
        assertTrue(prefStore.load().mealReminders.isEmpty());
        assertEquals(1, rescheduleCalls[0]);
    }

    @Test
    void remindersPreset2mealsSetsTwoTimes() {
        router.route("1", "/reminders preset 2meals");
        assertEquals(java.util.List.of("12:00", "18:30"), prefStore.load().mealReminders);
        assertEquals(1, rescheduleCalls[0]);
    }

    @Test
    void remindersPresetIfSetsOneMeal() {
        router.route("1", "/reminders preset if");
        assertEquals(java.util.List.of("18:00"), prefStore.load().mealReminders);
    }

    @Test
    void remindersUnknownVerbReturnsUsage() {
        SlashRouter.Action a = router.route("1", "/reminders blarp");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("用法") || text.contains("不認得"));
        assertEquals(0, rescheduleCalls[0]);
    }

    // ---------- /effort ----------

    @Test
    void tEffortShowsCurrent() {
        SlashRouter.Action a = router.route("1", "/effort");
        assertInstanceOf(SlashRouter.Action.Reply.class, a);
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("medium"), "should show current effort 'medium': " + text);
        assertTrue(text.contains("用法：/effort low|medium|high"), "should show usage: " + text);
    }

    @Test
    void tEffortSetsLow() {
        SlashRouter.Action a = router.route("1", "/effort low");
        assertInstanceOf(SlashRouter.Action.Reply.class, a);
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("已設定 effort = low"), "should confirm low: " + text);
        // reload from disk and verify
        PreferencesStore reloaded = new PreferencesStore(tempDir);
        assertEquals("low", reloaded.load().effort);
    }

    @Test
    void tEffortRejectsInvalid() {
        String beforeEffort = prefStore.load().effort;
        SlashRouter.Action a = router.route("1", "/effort foo");
        String text = ((SlashRouter.Action.Reply) a).text();
        assertTrue(text.contains("不認得：foo"), "should report unknown value: " + text);
        assertEquals(beforeEffort, prefStore.load().effort, "effort should be unchanged");
    }

    @Test
    void tEffortHelpAlias() {
        SlashRouter.Action aq = router.route("1", "/effort ?");
        SlashRouter.Action ah = router.route("1", "/effort help");
        String tq = ((SlashRouter.Action.Reply) aq).text();
        String th = ((SlashRouter.Action.Reply) ah).text();
        assertTrue(tq.contains("用法：/effort low|medium|high"), "? alias should show usage: " + tq);
        assertTrue(th.contains("用法：/effort low|medium|high"), "help alias should show usage: " + th);
    }
}
