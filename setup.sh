#!/bin/bash
set -e

# 1. Check java
if ! command -v java &> /dev/null; then
    echo "❌ Java not found. Please install JDK 17+."
    exit 1
fi

# 2. Check mvn or mvnw
if [ -f "./mvnw" ]; then
    MVN="./mvnw"
elif command -v mvn &> /dev/null; then
    MVN="mvn"
else
    echo "❌ Maven not found. Please install Maven 3.6+ or use mvnw."
    exit 1
fi

echo "✅ Java and Maven detected."

# 3. Prompt for credentials
read -p "Telegram Bot Token: " BOT_TOKEN
read -p "Telegram Bot Username: " BOT_USERNAME
read -p "OpenRouter API Key: " LLM_KEY

# 4. Write config.json
cat > config.json <<EOF
{
  "telegram": {
    "botToken": "${BOT_TOKEN}",
    "botUsername": "${BOT_USERNAME}",
    "allowedChatIds": []
  },
  "llm": {
    "apiKey": "${LLM_KEY}",
    "baseUrl": "https://openrouter.ai/api/v1/chat/completions",
    "model": "google/gemini-2.0-flash-lite-001",
    "maxTokens": 1000,
    "temperature": 0.7
  },
  "schedule": {
    "timezone": "Asia/Taipei",
    "mealReminders": ["07:30", "12:00", "18:00"],
    "workoutReminder": "20:00",
    "weeklySummary": "SUN 21:00"
  },
  "dataDir": "./data"
}
EOF
echo "✅ config.json written."

# 5. Ensure data dirs
mkdir -p data/skills/nutrition-advice data/skills/workout-planning data/logs

# 6. Seed user_profile.json and memory.json if missing
if [ ! -f data/user_profile.json ]; then
    cat > data/user_profile.json <<'EOF'
{"name":"","heightCm":0,"weightKg":0,"age":0,"gender":"","activityLevel":"","goal":"","bmr":0,"tdee":0,"targetCalories":0,"targetProteinG":0,"targetCarbsG":0,"targetFatG":0,"dietaryRestrictions":[],"notes":"","updatedAt":""}
EOF
fi
if [ ! -f data/memory.json ]; then
    echo '{"entries":[],"maxEntries":20,"maxChars":2200}' > data/memory.json
fi

# 7. Build
$MVN clean package -q

echo ""
echo "✅ Build complete."
echo "Run: java -jar target/health-coach-agent.jar"
