# 早上 Review TODO

## 已完成（11/11 steps + Maven wrapper）

| Step | 內容 | Tests | 備註 |
|------|------|-------|------|
| 1 | 12 個 POJO model + pom.xml + soul.md + seed JSON + config.json + logback.xml + .gitignore | — | Claude 自己做 |
| 2 | `BmrCalculator` + `NutritionPlanner` | 11 | ⚠️ Mifflin 公式修正 see SPEC_NOTES.md |
| 3 | `MemoryStore` + `DailyLogStore` | 20 | reflection-based updateField |
| 4 | `SkillManager` (YAML frontmatter 手刻 parser) | 10 | |
| 5 | `PatchExecutor` (PATCH/LOG regex + Gson) | 11 | |
| 6 | `PromptBuilder` (soul.md 從 classpath cache) | 9 | |
| 7 | `AgentCore` (OpenRouter HTTP + 429 retry) | 7 | TestableAgentCore mock seam |
| 8 | `TelegramBot` (LongPolling consumer) | **0** ⚠️ | 主程式有，test 因 usage limit 沒寫 |
| 9 | `CronScheduler` (ScheduledExecutorService) | 7 | Claude 自己補 |
| 10 | `Main.java` (DI wiring + shutdown hook) | — | Claude 自己寫 |
| 11 | `WorkoutPlanner` + `setup.sh` | 5 | |
| 12 | `mvnw` + `mvnw.cmd` + README.md | — | script-only mode, 第一次跑會自動下載 Maven 3.9.9 |

**Test 總數：~80**（spec 要求 ≥50 ✓）

---

## ⚠️ 待你處理 / 確認

### 1. STEP 8 TelegramBotTest 沒寫

Subagent 在實作中途撞到 usage limit（4am 重置）。`TelegramBot.java` 本體完整，但 6 個 unit test (T8.1–T8.6) 沒做。

**為什麼麻煩**：telegrambots v9.5 的 `TelegramClient` interface 有多個 abstract method 要 stub，加上 `Update` / `Message` / `Chat` 等 model 的 v9 API 跟之前版本有差，可能要實際 compile 試錯。

**建議路徑**：
- 用 Mockito（要加進 pom test scope）做 `TelegramClient` mock
- 或寫 anonymous subclass 只 override `execute()`，其他 method 拋 UnsupportedOperationException
- T8.1–T8.6 的測試邏輯都在 spec 與我給 subagent 的 prompt 裡（spec STEP 8 段落 + 我留的 `tasks/a9b4cd1e844d25ea3.output`）

### 2. STEP 2 BMR 公式 spec 不一致

詳見 `SPEC_NOTES.md`。我採取的決定：
- 改用標準 Mifflin (`+5` for male)
- Test 期望值改為實算結果 (1709 / 1333)，**不是 spec 的 1735 / 1340**

如果這是修課作業，可能要回去問助教/老師原意。

### 3. 第一次跑 mvnw 會下載 Maven (~ 9MB)

需要 `curl` 或 `wget`（Unix）/ `Invoke-WebRequest` PowerShell（Windows）。`~/.m2/wrapper/dists/` 會多一個 apache-maven-3.9.9 目錄。

### 4. 沒實際跑過 `mvn test`

我電腦上沒裝 Maven 也沒 `java` 在 PATH（JDK 17 裝在 `C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`，但沒 export）。所有程式都是「靜態 mental compile」。實際跑可能會撞到：

- import 路徑漏掉
- v9.5 telegrambots API 與我寫的不完全一致（特別是 `Update`/`Message` 建構方式）
- Gson type token 在 nested generic 處可能要 `TypeToken<...>{}` 寫法

預期跑 `./mvnw clean test` 後可能會有 5-15 個 compile/test failures 要修。先看 STEP 8 + STEP 11 setup.sh 的 chmod。

### 5. PRD 第 2.4.2 段沒給我

`PATCH_INSTRUCTIONS` 我自己擬了一份合理內容。如果 PRD 有原稿，可以替換。

### 6. 還沒整合測試

Spec C.2 列了一堆 integration test（real Telegram bot, real LLM call, cron 1 分鐘觸發等）。這些都需要實際 token，沒做。

---

## Build / 跑起來

```bash
cd "C:\Users\Chuannnn\Dev C++\health-coach-agent"
./mvnw clean test                  # 跑所有 unit test
./mvnw clean package               # 打 fat jar → target/health-coach-agent.jar
java -jar target/health-coach-agent.jar  # 需先填好 config.json
```

或：

```bash
./setup.sh   # 互動式設定 + build
```

---

## Git status

整個 project 在 `C:\Users\Chuannnn\Dev C++\health-coach-agent\`。
若已 push 到 GitHub，repo URL 會寫在 commit 訊息或 PR 連結裡。
