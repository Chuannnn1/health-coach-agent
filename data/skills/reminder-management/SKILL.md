---
name: reminder-management
description: 用 PATCH 改使用者的用餐 / 訓練 / 週報提醒時間與時區
---

# Reminder Management

當使用者用自然語言要求調整提醒時間（例如「以後只要午餐跟晚餐提醒」、「我改成 IF 一天一餐」、「訓練提醒改成晚上九點」、「先暫停所有提醒」），你**必須**在回覆中插入一個 `<PATCH>` 區塊，target 設為 `preferences`。

## 可用 action 與範例

```
<PATCH>{"target":"preferences","action":"set_meals","value":["12:00","18:30"]}</PATCH>
```
也可以用 comma string：`"value":"12:00,18:30"` — executor 兩種都吃。

| action | value 範例 | 效果 |
|--------|-----------|------|
| `set_meals` | `["07:30","12:00","18:00"]` 或 `"07:30,12:00,18:00"` | 取代用餐提醒時間清單 |
| `clear_meals` | (省略) | 清空用餐提醒 |
| `set_workout` | `"20:00"` | 設訓練提醒（HH:MM） |
| `clear_workout` | (省略) | 清空訓練提醒 |
| `set_weekly` | `"SUN 21:00"` | 週報時間（DOW HH:MM） |
| `set_timezone` | `"Asia/Taipei"` | IANA timezone string |

## 常見 preset

| 使用者說法 | 適合的 set_meals value |
|----------|-----------------------|
| 「三餐都提醒」/「正常作息」 | `["07:30","12:00","18:00"]` |
| 「我只吃午晚」/「兩餐」 | `["12:00","18:30"]` |
| 「IF 一日一餐」/「16/8 斷食」 | `["18:00"]` |
| 「暫停所有用餐提醒」 | 用 `clear_meals` |

## 重要

- 改完 executor 會自動 trigger CronScheduler.reschedule() — 你不用提醒使用者重啟
- PATCH 區塊**不會**顯示在使用者眼前（會被 strip 掉），所以在 PATCH 外面要用自然語言告訴使用者你做了什麼，例如「好，已改成只在 12:00 和 18:30 提醒」
- 如果使用者問「現在的提醒設定是什麼」，回答「可以打 /reminders 直接看，或我列給你看」並用記憶中的設定回答（不需要下 PATCH）

## 反例（不要這樣做）

- ❌ 把時間寫成「下午 12 點」之類自然語言 — 一定要 HH:MM 24 小時制
- ❌ 在同一個 PATCH 裡塞多個 action — 每個 action 一個 PATCH 區塊
- ❌ 用其他 target 名稱（例如 `schedule`、`reminder`）— 只認 `preferences`
