# Health Coach Agent

AI 健康教練 Telegram Bot — 用 Gemma 4 免費模型自動估算熱量、追蹤三大營養素、安排提醒，支援串流回覆與自我學習。

## Prerequisites

- **JDK 17+**（[Adoptium](https://adoptium.net/) 或 `brew install openjdk@17`）
- **Telegram 帳號**
- **Google AI Studio API Key**（免費）

---

## 1. 申請 API Key

### Telegram Bot Token

1. 開啟 Telegram，搜尋 [@BotFather](https://t.me/BotFather)
2. 發送 `/newbot`，依指示設定名稱
3. 取得 Bot Token（格式：`123456789:AAF-xxxxxxxxx`）

### Google AI Studio API Key（Gemma 4 免費額度）

1. 前往 [Google AI Studio](https://aistudio.google.com/apikey)
2. 登入 Google 帳號 → 點選 **Create API Key**
3. 複製 API Key（格式：`AIza...` 或 `AQ.Ab...`）

> Gemma 4 (31B) 透過 Google AI Studio 完全免費，無需綁定信用卡。
> 每分鐘 10 次請求 / 每日無上限（Free tier 2025.05 現況）。

---

## 2. 安裝與設定

```bash
git clone https://github.com/Chuannnn1/health-coach-agent.git
cd health-coach-agent

# macOS / Linux
bash setup.sh

# Windows PowerShell
.\setup.ps1
```

Setup Wizard 會依序詢問：

| 步驟 | 內容 |
|------|------|
| 1 | 環境檢查（Java + Maven） |
| 2 | Telegram Bot Token |
| 3 | LLM Provider + Model（推薦 Gemma 4 31B） |
| 4 | 用餐模式 + 時區 |
| 5 | Build |

完成後會建立 `healthy` 指令。macOS/Linux 需執行 `source ~/.zshrc`（或 `~/.bashrc`）讓 alias 生效。

---

## 3. 啟動與使用

```bash
healthy          # 啟動 bot（背景執行）
healthy stop     # 停止
healthy log      # 看最近 log
healthy config   # 編輯 config.json（API Key、Bot Token、Chart Key）
healthy status   # 檢查 bot 是否在執行
healthy help     # 顯示指令說明
```

啟動後到 Telegram 找你的 bot，發送 `/start` 開始對話。

---

## 4. 使用指南

### 基本對話

直接打字告訴 Coach 你吃了什麼，它會自動估算熱量並記錄：

```
我：中午吃了排骨便當加一杯珍奶
Coach：已記錄午餐 — 排骨便當 ~750 kcal + 珍奶 ~450 kcal
       今日累計 1200/2660 kcal，蛋白質 45/169g
       下一餐建議多補蛋白質...
```

### 設定個人資料

```
/setup    ← 互動式表單，設定身高體重年齡目標
```

Bot 會自動計算 BMR、TDEE、三大營養素目標。

### 常用指令

| 指令 | 功能 |
|------|------|
| `/start` | 啟動 Coach |
| `/setup` | 設定個人資料 |
| `/today` | 今日紀錄 + 進度條 |
| `/profile` | 看 BMR/TDEE/Macro |
| `/analyze` | AI 分析今日狀況 + 建議下一餐 |
| `/suggest 晚餐` | 推薦晚餐選項 |
| `/reminders` | 管理用餐/訓練提醒 |
| `/stop` | 中斷當前串流回覆 |
| `/new` | 清空對話上下文 |
| `/help` | 指令清單 |

### 提醒系統

Bot 會在設定的時間推送提醒（用餐、訓練、週報）。
用 `/reminders` 或直接說「以後只要午餐跟晚餐提醒」即可修改。

---

## 5. 設定檔說明

### config.json（gitignored，不會被 push）

```json
{
  "channel": "telegram",
  "telegram": {
    "botToken": "YOUR_BOT_TOKEN",
    "botUsername": "your_bot_name",
    "allowedChatIds": []
  },
  "llm": {
    "apiKey": "YOUR_GOOGLE_AI_STUDIO_KEY",
    "baseUrl": "https://generativelanguage.googleapis.com/v1beta",
    "model": "gemma-4-31b-it",
    "endpointStyle": "gemini-native",
    "effort": "medium",
    "maxTokens": 1000,
    "temperature": 0.7
  },
  "services": {
    "chartApiKey": ""
  },
  "dataDir": "./data"
}
```

### data/preferences.json（gitignored）

```json
{
  "timezone": "Asia/Taipei",
  "mealReminders": ["07:30", "12:00", "18:00"],
  "workoutReminder": "20:00",
  "weeklySummary": "SUN 21:00"
}
```

### Optional: Chart API Key

`config.json` → `services.chartApiKey` 用於圖表生成功能。
設定後，`/chart` 指令可產生視覺化的每週熱量趨勢圖（PNG），而非純文字表格。

**如何設定：**

```bash
healthy config    # 會開啟 config.json 編輯器
```

在 `"services"` 區塊加入 key：

```json
{
  "services": {
    "chartApiKey": "YOUR_QUICKCHART_KEY_HERE"
  }
}
```

**API 選項：**
- [QuickChart.io](https://quickchart.io/documentation/) — 免費 500 次/月，無需 key 即可使用基本功能；申請 key 可解鎖更高 rate limit
- 不填也能用 — `/chart` 預設以文字表格呈現

---

## 6. 資料儲存

所有使用者資料都在本機 `data/` 目錄，不會上傳：

| 檔案 | 內容 |
|------|------|
| `data/user_profile.json` | 身高體重年齡 + BMR/TDEE |
| `data/memory.json` | AI 長期記憶（飲食偏好等） |
| `data/preferences.json` | 提醒時間、時區 |
| `data/logs/yyyy-MM-dd.json` | 每日飲食紀錄 |
| `data/skills/` | 知識模組（可擴充） |

---

## 7. 進階設定

### Reasoning Effort

在 Telegram 用 `/effort low|medium|high` 調整回覆品質：

| 等級 | 效果 |
|------|------|
| `low` | 直覺回覆，最快 |
| `medium` | 預設，平衡品質與速度 |
| `high` | 深度推理，較慢 |

### 知識模組擴充

在 `data/skills/` 下建立資料夾 + `SKILL.md`：

```
data/skills/my-custom-skill/SKILL.md
```

Bot 會自動載入並在需要時參考。用 `/skills` 查看已載入的模組。

---

## Tech Stack

- Java 17 + Maven
- Telegram Bot API (Long Polling)
- Google AI Studio (Gemma 4 / Gemini)
- Streaming SSE + Progressive Edit

---

## License

MIT
