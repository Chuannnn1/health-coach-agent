# Health Coach Agent

AI 健康教練 — 支援 Telegram 和 LINE。自動估算熱量、追蹤三大營養素、安排訓練提醒，並能自我學習修正知識。

## Quick Start

```bash
# 1. 互動式設定（選 channel、填 credentials、選 LLM）
.\setup.ps1        # Windows PowerShell
./setup.sh         # macOS / Linux

# 2. 啟動
java -jar target\health-coach-agent.jar
```

> 需要 JDK 17+。第一次跑 `mvnw` 會自動下載 Maven。

---

## Setup Wizard（6 步）

| 步驟 | 內容 |
|------|------|
| [1/6] | 環境檢查（Java 17+ / Maven） |
| [2/6] | **選擇 Channel**：Telegram 或 LINE |
| [3/6] | Channel credentials（根據選擇） |
| [4/6] | LLM provider（Gemini / OpenRouter） |
| [5/6] | 提醒設定（時區、餐提醒、訓練提醒） |
| [6/6] | 寫 config + build jar |

所有 secret 輸入時**不會顯示任何字元**（同 Linux 密碼輸入）。

---

## Telegram Setup

### 前置作業

1. 在 Telegram 找 [@BotFather](https://t.me/BotFather)
2. 發 `/newbot` → 取得 **Bot Token**（格式：`123456:ABC-DEF...`）
3. 記下 bot username

### Setup 流程

```
.\setup.ps1

[2/6] 選擇 Telegram
[3/6] 貼上 Bot Token（畫面不會顯示）
      輸入 Bot Username
[4/6] 選 LLM provider + model
[5/6] 設用餐提醒時間
[6/6] Build

→ java -jar target\health-coach-agent.jar
→ 到 Telegram 找你的 bot，/start 開始
```

### config.json 結構

```json
{
    "channel": "telegram",
    "telegram": {
        "botToken": "123456:ABC-DEF...",
        "botUsername": "my_bot",
        "allowedChatIds": []
    },
    "llm": { ... },
    "dataDir": "./data"
}
```

Telegram 使用 long polling，不需要公開 IP 或 HTTPS。

---

## LINE Setup

### 前置作業

1. 到 [LINE Developers Console](https://developers.line.biz/console/) 登入
2. 建立 Provider → 建立 **Messaging API Channel**
3. 在 Basic settings 取得 **Channel Secret**（32 字 hex）
4. 在 Messaging API tab 按 Issue 取得 **Channel Access Token**（長 Base64 字串）
5. 在 LINE Official Account Manager → 設定：
   - **關閉** 自動回覆訊息
   - **開啟** Webhook

### Setup 流程

```
.\setup.ps1

[2/6] 選擇 LINE
[3/6] 貼上 Channel Secret（畫面不會顯示）
      貼上 Channel Access Token（畫面不會顯示）
      設定 Webhook port（預設 8080）
[4/6] 選 LLM provider + model
[5/6] 設用餐提醒時間
[6/6] Build

→ java -jar target\health-coach-agent.jar
  (LINE webhook on port 8080)
```

### 暴露 Webhook URL

LINE 要求 webhook 是 **HTTPS 公開 URL**。開發環境用 tunnel：

```bash
# 方法 A: cloudflared（推薦，免註冊）
cloudflared tunnel --url http://localhost:8080

# 方法 B: ngrok
ngrok http 8080
```

拿到 URL 後（例如 `https://xxx.trycloudflare.com`）：

1. 到 LINE Developers Console → Messaging API → Webhook URL
2. 填入 `https://xxx.trycloudflare.com/callback`
3. 按 Verify → 應顯示 Success
4. 開啟 "Use webhook"

### config.json 結構

```json
{
    "channel": "line",
    "line": {
        "channelSecret": "abc123...(32 hex)",
        "channelAccessToken": "very-long-base64-string...",
        "webhookPort": 8080
    },
    "llm": { ... },
    "dataDir": "./data"
}
```

### LINE 免費額度

| 類型 | 計費 |
|------|------|
| Reply（回覆用戶訊息） | 免費無限 |
| Push（主動發送，如提醒） | 免費 500 則/月 |

提醒功能走 push message，注意月額度。

---

## LLM 設定

Setup wizard 的 [4/6] 選 provider：

| Provider | 模型 | endpoint style |
|----------|------|---------------|
| Google Gemini API | gemma-4-31b-it, gemini-3.5-flash, etc. | gemini-native / openai |
| OpenRouter | 任何模型 | openai |

Gemma 模型用 `gemini-native` endpoint（免費 tier）；Gemini 模型用 OpenAI-compat。

---

## Slash Commands

| Command | 功能 |
|---------|------|
| `/start` | 啟動 Coach |
| `/setup` | 步驟式設定個人資料（互動表單） |
| `/new` | 清空對話上下文 |
| `/profile` | 個人資料 + BMR/TDEE |
| `/today` | 今日紀錄 + 熱量進度條 |
| `/memory` | 長期記憶列表 |
| `/skills` | 知識模組列表 |
| `/reminders` | 看/改提醒時間 |
| `/analyze` | LLM 分析今日狀況 |
| `/suggest <餐>` | 推薦下一餐 |
| `/help` | 指令清單 |

---

## Knowledge Skills

Agent 的知識存在 `data/skills/*/SKILL.md`，隨時可擴充：

| Skill | 內容 |
|-------|------|
| `nutrition-advice` | 台灣常見食物熱量、三大營養素分配 |
| `workout-planning` | 推拉腿/上下肢分化、組數次數建議 |
| `fat-loss-program` | 減脂熱量赤字、macro 分配、訓練策略 |
| `muscle-building` | 增肌盈餘、漸進式超負荷、訓練量建議 |
| `body-composition` | 先增先減決策樹、體脂率估算、新手策略 |
| `reminder-management` | PATCH 指令格式參考 |

LLM 會在回覆中用 `<PATCH>` 標籤自動修正知識。

---

## Architecture

```
User → [Telegram LongPoll / LINE Webhook]
              ↓
        AgentCore.chatStream()  ← streamGenerateContent SSE
              ↓ (onDelta)
        StreamingConsumer  → editMessageText progressive edit
              ↓ (finish)
        PatchExecutor.execute()  → mutate stores + PatchListener callback
              ↓
        ResponseSanitizer.sanitize()  → 三層過濾
              ↓
        editFinal() → User
```

Interactive:
- `/setup` → ProfileWizard state machine → InlineKeyboard (TG) / text (LINE)
- PatchListener → real-time status messages (meal logged, profile updated)

Memory layers:
- Layer 1: `user_profile.json` + `memory.json`
- Layer 2: `skills/*/SKILL.md`
- Layer 3: `logs/yyyy-MM-dd.json`
- Preferences: `preferences.json`

---

## Testing

```bash
./mvnw test    # 164 tests, all use @TempDir
```

---

## Security

- `config.json` 已 gitignore，不會被推到 GitHub
- Setup wizard 輸入 secret 時完全不顯示字元
- System prompt 含 prompt injection 防護（角色鎖定 + 命令偵測 + 社會工程免疫）
- Response 三層過濾：API field filter → think tag strip → regex safety net
