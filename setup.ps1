# Health Coach Agent — Windows interactive setup
# Run: .\setup.ps1
$ErrorActionPreference = 'Stop'

function Test-Cmd($name) {
    return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

if (-not (Test-Cmd 'java')) {
    Write-Host "X Java not found. Install JDK 17+ from https://adoptium.net/" -ForegroundColor Red
    exit 1
}

$mvn = $null
if (Test-Path '.\mvnw.cmd') {
    $mvn = '.\mvnw.cmd'
} elseif (Test-Cmd 'mvn') {
    $mvn = 'mvn'
} else {
    Write-Host "X Maven not found. Use mvnw.cmd or install Maven 3.6+." -ForegroundColor Red
    exit 1
}

Write-Host "OK Java and Maven detected." -ForegroundColor Green
Write-Host ""

$botToken    = Read-Host "Telegram Bot Token"
$botUsername = Read-Host "Telegram Bot Username"
$llmKey      = Read-Host "OpenRouter API Key"

$config = [ordered]@{
    telegram = [ordered]@{
        botToken        = $botToken
        botUsername     = $botUsername
        allowedChatIds  = @()
    }
    llm = [ordered]@{
        apiKey      = $llmKey
        baseUrl     = 'https://openrouter.ai/api/v1/chat/completions'
        model       = 'google/gemini-2.0-flash-lite-001'
        maxTokens   = 1000
        temperature = 0.7
    }
    schedule = [ordered]@{
        timezone        = 'Asia/Taipei'
        mealReminders   = @('07:30', '12:00', '18:00')
        workoutReminder = '20:00'
        weeklySummary   = 'SUN 21:00'
    }
    dataDir = './data'
}

$json = $config | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText((Join-Path (Get-Location) 'config.json'), $json, [System.Text.UTF8Encoding]::new($false))
Write-Host "OK config.json written." -ForegroundColor Green

# Seed data directories
$null = New-Item -ItemType Directory -Force -Path 'data\skills\nutrition-advice'
$null = New-Item -ItemType Directory -Force -Path 'data\skills\workout-planning'
$null = New-Item -ItemType Directory -Force -Path 'data\logs'

if (-not (Test-Path 'data\user_profile.json')) {
    $emptyProfile = '{"name":"","heightCm":0,"weightKg":0,"age":0,"gender":"","activityLevel":"","goal":"","bmr":0,"tdee":0,"targetCalories":0,"targetProteinG":0,"targetCarbsG":0,"targetFatG":0,"dietaryRestrictions":[],"notes":"","updatedAt":""}'
    [System.IO.File]::WriteAllText('data\user_profile.json', $emptyProfile, [System.Text.UTF8Encoding]::new($false))
}
if (-not (Test-Path 'data\memory.json')) {
    '{"entries":[],"maxEntries":20,"maxChars":2200}' | Set-Content -Path 'data\memory.json' -Encoding UTF8
}

Write-Host ""
Write-Host "Building..." -ForegroundColor Cyan
& $mvn clean package -q
if ($LASTEXITCODE -ne 0) {
    Write-Host "X Build failed." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "OK Build complete." -ForegroundColor Green
Write-Host "Run: java -jar target\health-coach-agent.jar" -ForegroundColor Yellow
