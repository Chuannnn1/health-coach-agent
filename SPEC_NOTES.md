# Spec 差異紀錄（早上 review 用）

## STEP 2 — BmrCalculator male formula

Spec 第 463 行 docstring:
```
Male:   10 * weight(kg) + 6.25 * height(cm) - 5 * age - 5
```

但標準 Mifflin-St Jeor 公式（Wikipedia、學術來源、所有 BMR 計算器）是：

```
Male:   10w + 6.25h - 5a + 5   (注意：+5，不是 -5)
Female: 10w + 6.25h - 5a - 161  ← 這個與 spec 一致
```

### Spec 測試期望值也對不上

| 測試 | spec 期望 | 標準 Mifflin 實算 | 差距 |
|------|---------|----------------|------|
| T2.1: male 72/175/22 | 1735 ± 5 | 1709 | 26 |
| T2.2: female 60/163/25 | 1340 ± 5 | 1333 | 7 |

`-5` 版本算出來 1699，`+5` 算出來 1709，都對不上 1735。spec 作者可能用了別的公式或寫錯數字。

### 決定

- BmrCalculator.java 改用 `+5`（標準 Mifflin）
- BmrCalculatorTest.java 期望值改為 1709 / 1333（標準 Mifflin 實算 + 註解）
- T2.4 / T2.6 / T2.7（下游 TDEE / 目標卡路里）spec 期望值與輸入 bmr=1735 一致，不受影響，不動

### 需要你決定

1. 接受目前修正：使用標準 Mifflin、test 改為實算值 ← **目前狀態**
2. 完全照 spec：把 `+5` 改回 `-5`、test 改為 1699/1333（仍會 fail 因為 spec 寫 1735）
3. 跟 spec 作者確認原意

---

## 其他可能需要 review 的設計選擇

- **MemoryStore.updateField** 用 switch 還是 reflection？目前 subagent 自己決定，看實作再說
- **Math.round 回傳 long → cast int**：BMR / TDEE / 卡路里都用 `(int) Math.round(...)`
- **patchSkill append**：在內容前加一個 `\n`（spec 寫 "preceded by newline"，但沒說是否要保留 trailing newline）— 實作只 prepend 一個 `\n`
- **DailyLogStore.loadDateRange**：spec 說 "skips missing dates"。實作不會呼叫 loadDate（避免副作用建檔），直接讀檔案，缺檔就跳過
