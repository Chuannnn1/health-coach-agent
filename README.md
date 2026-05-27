# Health Coach Agent

Telegram-based AI health coach with self-learning memory. Reminds users to record meals/workouts, estimates nutrition from natural language, and patches its own knowledge when corrected.

## Quick start

```cmd
:: Windows
mvnw.cmd clean test
mvnw.cmd clean package
java -jar target\health-coach-agent.jar
```

```bash
# macOS / Linux
./mvnw clean test
./mvnw clean package
java -jar target/health-coach-agent.jar
```

> The first `mvnw` run downloads Apache Maven 3.9.9 into `~/.m2/wrapper/dists/`. Requires JDK 17+ and either `curl`/`wget` (Unix) or PowerShell (Windows).
>
> Alternatively, install Maven globally and use plain `mvn` commands.

## Configuration

Copy `config.json` and fill in credentials:

```json
{
  "telegram": { "botToken": "...", "botUsername": "..." },
  "llm":      { "apiKey": "sk-or-v1-...", "model": "google/gemini-2.0-flash-lite-001" }
}
```

Or run `./setup.sh` for interactive setup.

## Architecture

3-layer memory model:
- **Layer 1** (`data/user_profile.json`, `data/memory.json`) — read/write facts about user
- **Layer 2** (`data/skills/*/SKILL.md`) — knowledge modules, self-patchable via `<PATCH>` tags
- **Layer 3** (`data/logs/yyyy-MM-dd.json`) — daily meal & workout logs

LLM emits `<PATCH>` and `<LOG>` tags in replies; `PatchExecutor` parses them and mutates state on disk, then strips tags before sending the user-visible message.

## Project layout

```
src/main/java/com/healthcoach/
├── Main.java                    # entry point + DI wiring
├── bot/TelegramBot.java         # long-polling consumer
├── scheduler/CronScheduler.java # meal/workout reminders
├── agent/
│   ├── AgentCore.java           # OpenRouter HTTP client
│   ├── PromptBuilder.java       # assembles system prompt from layers
│   └── PatchExecutor.java       # parses <PATCH>/<LOG> tags
├── health/
│   ├── BmrCalculator.java       # Mifflin-St Jeor BMR + TDEE
│   ├── NutritionPlanner.java    # macro split by goal
│   └── WorkoutPlanner.java      # 3-day / 4-day templates
├── memory/
│   ├── MemoryStore.java         # Layer 1
│   ├── SkillManager.java        # Layer 2
│   └── DailyLogStore.java       # Layer 3
└── model/                       # 12 POJOs
```

## Testing

```bash
./mvnw test          # ~ 65+ tests across all layers
```

All tests use `@TempDir` — none touch the real `data/` directory.

## See also

- `SPEC_NOTES.md` — deviations from the original spec and rationale
- `TODO.md` — outstanding items for morning review
