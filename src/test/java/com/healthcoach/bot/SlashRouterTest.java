package com.healthcoach.bot;

import com.healthcoach.agent.ConversationStore;
import com.healthcoach.memory.DailyLogStore;
import com.healthcoach.memory.MemoryStore;
import com.healthcoach.memory.SkillManager;
import com.healthcoach.model.MealEntry;
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
        router = new SlashRouter(memoryStore, skillManager, dailyLogStore, conv);
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
}
