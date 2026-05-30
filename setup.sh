#!/bin/bash
# Health Coach Agent — Interactive Setup Wizard (bash)
# Usage: ./setup.sh
set -e

# ----- ANSI colors -----
BOLD=$'\033[1m'
DIM=$'\033[2m'
CYAN=$'\033[36m'
GREEN=$'\033[32m'
RED=$'\033[31m'
YELLOW=$'\033[33m'
BLUE=$'\033[34m'
RESET=$'\033[0m'

# ----- Restore cursor on exit (in case user Ctrl+C during menu) -----
trap 'printf "\033[?25h" 2>/dev/null; exit' INT TERM

banner() {
    echo ""
    echo "${CYAN}${BOLD}==============================================${RESET}"
    echo "${CYAN}${BOLD}   Health Coach Agent — Setup Wizard${RESET}"
    echo "${CYAN}${BOLD}==============================================${RESET}"
}

section() {
    echo ""
    echo "${BLUE}${BOLD}[$1/5]${RESET} ${BOLD}$2${RESET}"
    [ -n "$3" ] && echo "${DIM}      $3${RESET}"
    echo ""
}

ok()   { echo "  ${GREEN}OK${RESET}  $1"; }
warn() { echo "  ${YELLOW}!!${RESET}  $1"; }
err()  { echo "  ${RED}XX${RESET}  $1"; }

# ----- Bullet-point arrow-key menu -----
# Usage: select_menu "Title" "opt1" "opt2" "opt3" ...
# Sets globals: SELECTED_INDEX, SELECTED_VALUE
select_menu() {
    local title="$1"; shift
    local opts=("$@")
    local sel=0
    local n=${#opts[@]}
    local key1 key2 key3

    printf '\033[?25l'  # hide cursor

    printf '  %s(↑/↓ 選擇，Enter 確認)%s\n' "$DIM" "$RESET"
    printf '  %s%s%s\n' "$BOLD" "$title" "$RESET"
    for i in "${!opts[@]}"; do
        if [ "$i" -eq "$sel" ]; then
            printf '  %s> %s%s\n' "$GREEN" "${opts[$i]}" "$RESET"
        else
            printf '    %s\n' "${opts[$i]}"
        fi
    done

    while true; do
        IFS= read -rsn1 key1
        if [[ "$key1" == $'\x1b' ]]; then
            IFS= read -rsn1 -t 0.01 key2 || true
            IFS= read -rsn1 -t 0.01 key3 || true
            case "${key2}${key3}" in
                '[A') ((sel--)); [ $sel -lt 0 ] && sel=$((n-1));;
                '[B') ((sel++)); [ $sel -ge $n ] && sel=0;;
            esac
        elif [[ -z "$key1" ]]; then
            printf '\033[?25h'
            SELECTED_INDEX=$sel
            SELECTED_VALUE="${opts[$sel]}"
            return 0
        fi

        printf '\033[%dA' "$n"
        for i in "${!opts[@]}"; do
            printf '\033[2K'
            if [ "$i" -eq "$sel" ]; then
                printf '  %s> %s%s\n' "$GREEN" "${opts[$i]}" "$RESET"
            else
                printf '    %s\n' "${opts[$i]}"
            fi
        done
    done
}

read_with_default() {
    local __var="$1" __prompt="$2" __default="$3" __value
    if [ -n "$__default" ]; then
        read -p "  $__prompt [${YELLOW}$__default${RESET}]: " __value
        __value="${__value:-$__default}"
    else
        read -p "  $__prompt: " __value
    fi
    printf -v "$__var" "%s" "$__value"
}

read_validated() {
    local __var="$1" __prompt="$2" __regex="$3" __hint="$4" __value
    while true; do
        read -p "  $__prompt: " __value
        if [[ "$__value" =~ $__regex ]]; then
            printf -v "$__var" "%s" "$__value"
            return 0
        fi
        err "格式不對（$__hint）"
    done
}

# Like read_validated but hides input (for secrets like tokens and API keys)
read_secret_validated() {
    local __var="$1" __prompt="$2" __regex="$3" __hint="$4" __value
    while true; do
        read -s -p "  $__prompt: " __value
        echo ""
        if [[ "$__value" =~ $__regex ]]; then
            printf -v "$__var" "%s" "$__value"
            return 0
        fi
        err "格式不對（$__hint）"
    done
}

confirm() {
    local prompt="$1" default="$2" reply
    if [ "$default" = "y" ]; then
        read -p "  $prompt [Y/n]: " reply
        reply="${reply:-y}"
    else
        read -p "  $prompt [y/N]: " reply
        reply="${reply:-n}"
    fi
    [[ "$reply" =~ ^[Yy] ]]
}

banner

# ----- [1/5] Environment -----
section 1 "Environment check" "驗證 Java 17+ 與 Maven"

if ! command -v java &> /dev/null; then
    err "Java not found. 請安裝 JDK 17+：https://adoptium.net/"
    exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1)
ok "Java detected: ${JAVA_VER}"

if [ -f "./mvnw" ]; then
    MVN="./mvnw"
    ok "Maven wrapper found (./mvnw)"
elif command -v mvn &> /dev/null; then
    MVN="mvn"
    ok "Maven detected on PATH"
else
    err "Maven not found. 安裝 Maven 3.6+ 或使用 mvnw."
    exit 1
fi

# ----- Overwrite check -----
if [ -f "config.json" ]; then
    echo ""
    warn "config.json 已存在。"
    if ! confirm "覆蓋現有設定？" "n"; then
        echo ""
        echo "  ${DIM}已取消，未變更任何檔案。${RESET}"
        exit 0
    fi
fi

# ----- [2/5] Telegram -----
section 2 "Telegram credentials" "從 @BotFather 取得"

echo "  ${DIM}(輸入時不顯示，同密碼)${RESET}"
read_secret_validated BOT_TOKEN \
    "Bot Token" \
    '^[0-9]+:[A-Za-z0-9_-]{20,}$' \
    "<數字>:<亂碼>，至少 20 字"
ok "Token 格式 OK"

echo ""
read_with_default BOT_USERNAME "Bot Username (可空白)" ""
if [ -n "$BOT_USERNAME" ] && [[ ! "$BOT_USERNAME" =~ [Bb]ot$ ]]; then
    warn "Username 通常以 'bot' 結尾，仍照填入。"
fi

# ----- [3/5] LLM Provider + Model -----
section 3 "LLM provider" "預設 Google Gemini API (OpenAI-compat endpoint)"

echo "  ${DIM}Gemini key: https://aistudio.google.com/apikey${RESET}"
echo "  ${DIM}OpenRouter: https://openrouter.ai/keys${RESET}"
echo ""
select_menu "Provider:" \
    "Google Gemini API" \
    "OpenRouter"
PROVIDER_IDX=$SELECTED_INDEX
echo ""

if [ "$PROVIDER_IDX" -eq 0 ]; then
    echo "  ${DIM}(輸入時不顯示)${RESET}"
    read_secret_validated LLM_KEY \
        "Gemini API Key (AIza...)" \
        '^AIza[A-Za-z0-9_-]{30,}$' \
        "AIza 開頭 + 35 字元，從 Google AI Studio 取得"
    ok "API Key 格式 OK"

    echo ""
    echo "  ${DIM}Gemma 4 -> native endpoint, free tier${RESET}"
    echo "  ${DIM}Gemini 3.x / 2.5 -> OpenAI-compat endpoint${RESET}"
    GEMINI_MODELS=(
        "gemma-4-31b-it"
        "gemma-4-26b-a4b-it"
        "gemini-3.5-flash"
        "gemini-3.1-flash-lite"
        "gemini-3.1-pro-preview"
        "gemini-3-flash-preview"
        "gemini-2.5-flash"
        "gemini-2.5-flash-lite"
        "gemini-2.5-pro"
    )
    select_menu "選模型:" "${GEMINI_MODELS[@]}" "Other (manual input)"
    if [ "$SELECTED_INDEX" -lt "${#GEMINI_MODELS[@]}" ]; then
        LLM_MODEL="${GEMINI_MODELS[$SELECTED_INDEX]}"
    else
        echo ""
        read_with_default LLM_MODEL "Model id" "gemma-4-31b-it"
    fi

    # Gemma 模型只能透過 Gemini native endpoint, Gemini 系列走 OpenAI-compat
    if [[ "$LLM_MODEL" == gemma-* ]]; then
        LLM_ENDPOINT_STYLE="gemini-native"
        LLM_BASE_URL="https://generativelanguage.googleapis.com/v1beta"
    else
        LLM_ENDPOINT_STYLE="openai"
        LLM_BASE_URL="https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    fi
    ok "Model: ${GREEN}${LLM_MODEL}${RESET}  (${LLM_ENDPOINT_STYLE})"
else
    LLM_ENDPOINT_STYLE="openai"
    LLM_BASE_URL="https://openrouter.ai/api/v1/chat/completions"
    echo "  ${DIM}(輸入時不顯示)${RESET}"
    read_secret_validated LLM_KEY \
        "OpenRouter API Key (sk-or-v1-...)" \
        '^sk-(or-v1-)?[A-Za-z0-9_-]{20,}$' \
        "sk-or-v1- 開頭"
    ok "API Key 格式 OK"

    echo ""
    OR_MODELS=(
        "google/gemma-4-31b-it:free"
        "google/gemma-4-26b-a4b-it:free"
        "google/gemini-3.5-flash"
        "google/gemini-3.1-flash-lite"
        "google/gemini-3.1-pro-preview"
        "google/gemini-2.5-flash"
        "google/gemini-2.5-pro"
        "anthropic/claude-3.5-haiku"
        "deepseek/deepseek-chat"
    )
    select_menu "選模型:" "${OR_MODELS[@]}" "Other (manual input)"
    if [ "$SELECTED_INDEX" -lt "${#OR_MODELS[@]}" ]; then
        LLM_MODEL="${OR_MODELS[$SELECTED_INDEX]}"
    else
        echo ""
        read_with_default LLM_MODEL "Model id" "google/gemma-4-31b-it:free"
    fi
    ok "Model: ${GREEN}${LLM_MODEL}${RESET}"
fi

echo ""
read_with_default LLM_TEMP "Temperature (0.0–1.5)" "0.7"

echo ""
# Reasoning effort default — overridable at runtime via Telegram /effort
# Surface existing config.json value if re-running
SAVED_EFFORT=""
if [ -f config.json ]; then
    SAVED_EFFORT=$(grep -E '"effort"[[:space:]]*:' config.json | head -1 | sed -E 's/.*"effort"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/' || true)
fi
EFFORT_TITLE="Reasoning effort default (Gemini 3 thinkingBudget):"
if [ -n "$SAVED_EFFORT" ]; then
    EFFORT_TITLE="Reasoning effort default (目前: ${SAVED_EFFORT}):"
fi
select_menu "$EFFORT_TITLE" \
    "medium  - 預設，平衡品質與速度 (Gemini 3: thinkingBudget=1024)" \
    "low     - 直覺回覆，最快 (Gemini 3: thinkingBudget=0)" \
    "high    - 深度推理，較慢 (Gemini 3: thinkingBudget=8192)"
case "$SELECTED_INDEX" in
    0) LLM_EFFORT="medium" ;;
    1) LLM_EFFORT="low" ;;
    2) LLM_EFFORT="high" ;;
    *) LLM_EFFORT="medium" ;;
esac
ok "Effort default: ${GREEN}${LLM_EFFORT}${RESET}"

# ----- [4/5] Reminders -----
section 4 "Reminders & timezone" "用餐模式 + 時區（之後可在 Telegram 用 /reminders 改）"

select_menu "你的用餐模式:" \
    "3 餐 / 日   (07:30, 12:00, 18:00)" \
    "2 餐 / 日   (12:00, 18:30)" \
    "IF 一日一餐 (18:00)" \
    "不要用餐提醒" \
    "Custom (手動輸入時間)"
case "$SELECTED_INDEX" in
    0) MEALS="07:30,12:00,18:00" ;;
    1) MEALS="12:00,18:30" ;;
    2) MEALS="18:00" ;;
    3) MEALS="" ;;
    4)
        echo ""
        read_with_default MEALS "Meal reminders (HH:MM,HH:MM,HH:MM, 空白=無)" "07:30,12:00,18:00"
        ;;
esac
echo ""
read_with_default TZ_VAL "Timezone"                                  "Asia/Taipei"
read_with_default WORKOUT "Workout reminder (HH:MM, 空白=無)"        "20:00"
read_with_default WEEKLY  "Weekly summary (DOW HH:MM, 空白=無)"      "SUN 21:00"

# Convert CSV → JSON array (empty string → [])
if [ -z "$MEALS" ]; then
    MEALS_JSON="[]"
else
    MEALS_JSON=$(printf '%s' "$MEALS" | awk -F',' '{
        out=""
        for(i=1;i<=NF;i++){
            gsub(/^[ \t]+|[ \t]+$/, "", $i)
            if($i==""||$i=="") continue
            if(out!="") out=out","
            out=out"\""$i"\""
        }
        print "["out"]"
    }')
fi

# ----- [5/5] Write & build -----
section 5 "Write config & build" "產生 config.json、data/ 目錄、編譯 fat jar"

cat > config.json <<EOF
{
  "telegram": {
    "botToken": "${BOT_TOKEN}",
    "botUsername": "${BOT_USERNAME}",
    "allowedChatIds": []
  },
  "llm": {
    "apiKey": "${LLM_KEY}",
    "baseUrl": "${LLM_BASE_URL}",
    "model": "${LLM_MODEL}",
    "endpointStyle": "${LLM_ENDPOINT_STYLE}",
    "effort": "${LLM_EFFORT}",
    "maxTokens": 1000,
    "temperature": ${LLM_TEMP}
  },
  "dataDir": "./data"
}
EOF
ok "config.json saved (credentials, no schedule)"

# User-mutable runtime preferences (separate from secret config)
mkdir -p data
cat > data/preferences.json <<EOF
{
  "timezone": "${TZ_VAL}",
  "mealReminders": ${MEALS_JSON},
  "workoutReminder": "${WORKOUT}",
  "weeklySummary": "${WEEKLY}"
}
EOF
ok "data/preferences.json saved (mutable via /reminders)"

mkdir -p data/skills/nutrition-advice data/skills/workout-planning data/logs
if [ ! -f data/user_profile.json ]; then
    cat > data/user_profile.json <<'EOF'
{"name":"","heightCm":0,"weightKg":0,"age":0,"gender":"","activityLevel":"","goal":"","bmr":0,"tdee":0,"targetCalories":0,"targetProteinG":0,"targetCarbsG":0,"targetFatG":0,"dietaryRestrictions":[],"notes":"","updatedAt":""}
EOF
fi
if [ ! -f data/memory.json ]; then
    echo '{"entries":[],"maxEntries":20,"maxChars":2200}' > data/memory.json
fi
ok "data/ scaffolded"

echo ""
echo "  ${DIM}$MVN clean package -q  (請稍候)${RESET}"
if ! $MVN clean package -q; then
    err "Build failed."
    exit 1
fi
ok "Build complete"

echo ""
echo "${GREEN}${BOLD}==============================================${RESET}"
echo "${GREEN}${BOLD}   Setup 完成！${RESET}"
echo "${GREEN}${BOLD}==============================================${RESET}"
echo ""
echo "  下一步："
echo "    ${CYAN}java -jar target/health-coach-agent.jar${RESET}"
echo ""
echo "  ${DIM}到 Telegram 找你的 bot → /start 開始對話${RESET}"
echo ""
