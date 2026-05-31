# Health Coach Agent - Interactive Setup Wizard (PowerShell)
# Run: .\setup.ps1
$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch {}

# ----- Helpers -----
function Test-Cmd($name) {
    return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

function Write-Banner {
    Write-Host ""
    Write-Host "==============================================" -ForegroundColor Cyan
    Write-Host "   Health Coach Agent - Setup Wizard" -ForegroundColor Cyan
    Write-Host "==============================================" -ForegroundColor Cyan
}

function Write-Section($num, $title, $hint) {
    Write-Host ""
    Write-Host "[$num/6] " -ForegroundColor Blue -NoNewline
    Write-Host $title -ForegroundColor White
    if ($hint) {
        Write-Host "      $hint" -ForegroundColor DarkGray
    }
    Write-Host ""
}

function Write-Ok($msg)   { Write-Host "  OK  " -ForegroundColor Green  -NoNewline; Write-Host $msg }
function Write-Warn($msg) { Write-Host "  !!  " -ForegroundColor Yellow -NoNewline; Write-Host $msg }
function Write-Err($msg)  { Write-Host "  XX  " -ForegroundColor Red    -NoNewline; Write-Host $msg }

function Read-WithDefault($prompt, $default) {
    if ($default) {
        $val = Read-Host "  $prompt [$default]"
        if ([string]::IsNullOrWhiteSpace($val)) { return $default }
        return $val
    } else {
        return Read-Host "  $prompt"
    }
}

function Read-Validated($prompt, $regex, $hint) {
    while ($true) {
        $val = Read-Host "  $prompt"
        if ($val -match $regex) { return $val }
        Write-Err ("格式不對 (" + $hint + ")")
    }
}

# Like Read-Validated but hides input (for secrets like tokens and API keys)
function Read-SecretValidated($prompt, $regex, $hint) {
    Read-SecretValidatedOrKeep $prompt $regex $hint $null
}

# Read a secret with ZERO visual feedback (no asterisks, no length hint).
# Like Linux `stty -echo` / `read -s` behavior.
function Read-SecretSilent($displayPrompt) {
    Write-Host "  $displayPrompt" -NoNewline -ForegroundColor White
    $buf = [System.Text.StringBuilder]::new()
    [Console]::CursorVisible = $false
    try {
        while ($true) {
            $ki = [Console]::ReadKey($true)
            if ($ki.Key -eq 'Enter') { break }
            if ($ki.Key -eq 'Backspace') {
                if ($buf.Length -gt 0) { $null = $buf.Remove($buf.Length - 1, 1) }
                continue
            }
            if ($ki.Key -eq 'Escape') { $buf.Clear(); break }
            if ($ki.KeyChar -and $ki.KeyChar -ne [char]0) {
                $null = $buf.Append($ki.KeyChar)
            }
        }
    } finally {
        [Console]::CursorVisible = $true
    }
    Write-Host ""
    return $buf.ToString()
}

# Secret reader that respects a previously-saved value: Enter keeps saved, type new to overwrite.
function Read-SecretValidatedOrKeep($prompt, $regex, $hint, $savedValue) {
    $hasSaved = $savedValue -and ($savedValue -match $regex)
    while ($true) {
        if ($hasSaved) {
            $val = Read-SecretSilent ($prompt + " (Enter 保留已儲存): ")
        } else {
            $val = Read-SecretSilent ($prompt + ": ")
        }
        if ([string]::IsNullOrEmpty($val) -and $hasSaved) { return $savedValue }
        if ([string]::IsNullOrEmpty($val) -and -not $hasSaved) {
            Write-Err "不能為空"
            continue
        }
        if ($val -match $regex) { return $val }
        Write-Err ("格式不對 (" + $hint + ")")
    }
}

function Confirm-Action($prompt, $defaultYes) {
    if ($defaultYes) { $hintTxt = "[Y/n]" } else { $hintTxt = "[y/N]" }
    $reply = Read-Host "  $prompt $hintTxt"
    if ([string]::IsNullOrWhiteSpace($reply)) {
        return $defaultYes
    }
    return ($reply -match '^[Yy]')
}

# ----- Arrow-key menu (tracks actual cursor rows, handles text wrapping) -----
function Select-Menu {
    param(
        [string]$Title,
        [string[]]$Options
    )
    $sel = 0
    $n = $Options.Count

    $bw = [Console]::BufferWidth
    if ($bw -le 0) { $bw = 80 }
    $blankLine = ' ' * ($bw - 1)

    [Console]::CursorVisible = $false
    try {
        Write-Host "  (UP/DOWN 選擇, Enter 確認)" -ForegroundColor DarkGray
        Write-Host "  $Title" -ForegroundColor White

        # Reserve generous space to force any scroll before we capture startRow
        $reserve = $n * 2 + 1
        for ($i = 0; $i -lt $reserve; $i++) { Write-Host "" }
        $startRow = [Console]::CursorTop - $reserve
        $endRow = $startRow

        while ($true) {
            # Clear previous render area
            for ($r = $startRow; $r -lt $endRow; $r++) {
                [Console]::SetCursorPosition(0, $r)
                [Console]::Write($blankLine)
            }
            [Console]::SetCursorPosition(0, $startRow)

            for ($i = 0; $i -lt $n; $i++) {
                if ($i -eq $sel) {
                    Write-Host ("  > " + $Options[$i]) -ForegroundColor Green
                } else {
                    Write-Host ("    " + $Options[$i]) -ForegroundColor Gray
                }
            }
            $endRow = [Console]::CursorTop

            $key = [Console]::ReadKey($true)
            switch ($key.Key) {
                'UpArrow'   { $sel--; if ($sel -lt 0)  { $sel = $n - 1 } }
                'DownArrow' { $sel++; if ($sel -ge $n) { $sel = 0 } }
                'Enter'     {
                    [Console]::SetCursorPosition(0, $endRow)
                    Write-Host ""
                    return $sel
                }
            }
        }
    } finally {
        [Console]::CursorVisible = $true
    }
}

# ----- Start -----
Write-Banner

# ----- [1/5] Environment -----
Write-Section 1 "Environment check" "驗證 Java 17+ 與 Maven"

if (-not (Test-Cmd 'java')) {
    Write-Err "Java not found. 請安裝 JDK 17+: https://adoptium.net/"
    exit 1
}
# java -version writes to stderr; wrap in cmd to merge streams cleanly
$javaVer = (& cmd /c "java -version 2>&1" | Select-Object -First 1)
if (-not $javaVer) { $javaVer = "JDK 17+" }
Write-Ok ("Java detected: " + $javaVer)

$mvn = $null
if (Test-Path '.\mvnw.cmd') {
    $mvn = '.\mvnw.cmd'
    Write-Ok "Maven wrapper found (.\mvnw.cmd)"
} elseif (Test-Cmd 'mvn') {
    $mvn = 'mvn'
    Write-Ok "Maven detected on PATH"
} else {
    Write-Err "Maven not found. 安裝 Maven 3.6+ 或使用 mvnw.cmd"
    exit 1
}

# ----- Pin project root for all writes (avoid .NET CurrentDirectory drift) -----
$ProjectRoot = (Get-Location).Path
function Abs($rel) { return Join-Path $ProjectRoot $rel }
function WriteUtf8NoBom($relPath, $content) {
    $abs = Abs $relPath
    $dir = Split-Path $abs -Parent
    if ($dir -and -not (Test-Path $dir)) {
        $null = New-Item -ItemType Directory -Force -Path $dir
    }
    [System.IO.File]::WriteAllText($abs, $content, [System.Text.UTF8Encoding]::new($false))
}

# Batch files require CRLF — cmd.exe parses LF-only batch files wrong (`@REM` → `EM`, etc.)
function WriteBatchFile($relPath, $content) {
    $abs = Abs $relPath
    $dir = Split-Path $abs -Parent
    if ($dir -and -not (Test-Path $dir)) {
        $null = New-Item -ItemType Directory -Force -Path $dir
    }
    $normalized = $content -replace "`r`n", "`n" -replace "`r", "`n" -replace "`n", "`r`n"
    [System.IO.File]::WriteAllBytes($abs, [System.Text.Encoding]::ASCII.GetBytes($normalized))
}

# ----- Resume support: load existing config.json + preferences.json if any -----
$saved = @{
    channel = 'telegram'
    botToken = ''; botUsername = ''
    lineChannelSecret = ''; lineChannelAccessToken = ''; lineWebhookPort = 8080
    apiKey = ''; baseUrl = ''; model = ''; endpointStyle = ''; temperature = $null; effort = ''
    timezone = ''; mealReminders = @(); workoutReminder = ''; weeklySummary = ''
}

if (Test-Path (Abs 'config.json')) {
    try {
        $existing = Get-Content (Abs 'config.json') -Raw | ConvertFrom-Json
        if ($existing.channel) { $saved.channel = [string]$existing.channel }
        if ($existing.telegram) {
            $saved.botToken    = [string]$existing.telegram.botToken
            $saved.botUsername = [string]$existing.telegram.botUsername
        }
        if ($existing.line) {
            $saved.lineChannelSecret      = [string]$existing.line.channelSecret
            $saved.lineChannelAccessToken  = [string]$existing.line.channelAccessToken
            if ($existing.line.webhookPort) { $saved.lineWebhookPort = [int]$existing.line.webhookPort }
        }
        if ($existing.llm) {
            $saved.apiKey        = [string]$existing.llm.apiKey
            $saved.baseUrl       = [string]$existing.llm.baseUrl
            $saved.model         = [string]$existing.llm.model
            $saved.endpointStyle = [string]$existing.llm.endpointStyle
            if ($null -ne $existing.llm.temperature) { $saved.temperature = [double]$existing.llm.temperature }
            if ($existing.llm.effort) { $saved.effort = [string]$existing.llm.effort }
        }
    } catch {
        Write-Warn ("config.json 解析失敗, 從頭問: " + $_.Exception.Message)
    }
}
if (Test-Path (Abs 'data/preferences.json')) {
    try {
        $existingPref = Get-Content (Abs 'data/preferences.json') -Raw | ConvertFrom-Json
        if ($existingPref.timezone)        { $saved.timezone        = [string]$existingPref.timezone }
        if ($existingPref.mealReminders)   { $saved.mealReminders   = @($existingPref.mealReminders) }
        if ($existingPref.workoutReminder) { $saved.workoutReminder = [string]$existingPref.workoutReminder }
        if ($existingPref.weeklySummary)   { $saved.weeklySummary   = [string]$existingPref.weeklySummary }
    } catch { }
}

$hasPriorState = ($saved.botToken -or $saved.lineChannelAccessToken -or $saved.apiKey -or $saved.mealReminders.Count -gt 0)
if ($hasPriorState) {
    Write-Host ""
    Write-Host "  偵測到先前儲存的設定 (config.json)。" -ForegroundColor Yellow
    Write-Host "  接下來會重新走一次設定流程，每一格按 Enter 即可保留之前的值。" -ForegroundColor DarkGray
    Write-Host ""
}

# ----- [2/6] Channel selection -----
Write-Section 2 "Messaging channel" "選擇 bot 要透過哪個平台接收訊息"

$channelIdx = Select-Menu "選擇平台:" @(
    "Telegram (long polling, 無需公開 IP)",
    "LINE (webhook, 需要 HTTPS 公開 URL)"
)
$selectedChannel = if ($channelIdx -eq 0) { "telegram" } else { "line" }
Write-Ok ("Channel: " + $selectedChannel)

# ----- [3/6] Channel credentials -----
if ($selectedChannel -eq "telegram") {
    Write-Section 3 "Telegram credentials" "從 @BotFather 取得"

    Write-Host "  (輸入時不會顯示任何字元，直接打完按 Enter)" -ForegroundColor DarkGray
    $botToken = Read-SecretValidatedOrKeep `
        "Bot Token" `
        '^[0-9]+:[A-Za-z0-9_-]{20,}$' `
        "<數字>:<亂碼>, 至少 20 字" `
        $saved.botToken
    Write-Ok "Token 格式 OK"

    Write-Host ""
    $botUsername = Read-WithDefault "Bot Username (可空白)" $saved.botUsername
    if ($botUsername -and ($botUsername -notmatch '[Bb]ot$')) {
        Write-Warn "Username 通常以 'bot' 結尾, 仍照填入"
    }
} else {
    Write-Section 3 "LINE credentials" "從 LINE Developers Console 取得"

    Write-Host "  Channel Secret + Access Token: https://developers.line.biz/console/" -ForegroundColor DarkGray
    Write-Host "  (輸入時不會顯示任何字元，直接打完按 Enter)" -ForegroundColor DarkGray
    Write-Host ""
    $lineSecret = Read-SecretValidatedOrKeep `
        "Channel Secret" `
        '^[0-9a-f]{32}$' `
        "32 字 hex (小寫)" `
        $saved.lineChannelSecret
    Write-Ok "Channel Secret 格式 OK"

    Write-Host ""
    $lineToken = Read-SecretValidatedOrKeep `
        "Channel Access Token" `
        '^[A-Za-z0-9+/=]{100,}$' `
        "Base64 長字串, 從 Messaging API tab Issue" `
        $saved.lineChannelAccessToken
    Write-Ok "Access Token 格式 OK"

    Write-Host ""
    $linePortDefault = [string]$saved.lineWebhookPort
    $linePort = Read-WithDefault "Webhook port (本機 HTTP)" $linePortDefault
    Write-Ok ("Webhook will listen on :" + $linePort)
}

# Persist what we have so far so a crash later doesn't lose this
$earlyConfig = [ordered]@{
    channel = $selectedChannel
    llm     = [ordered]@{ apiKey = $saved.apiKey; baseUrl = $saved.baseUrl; model = $saved.model;
                            endpointStyle = $saved.endpointStyle;
                            effort = ($(if ($saved.effort) { $saved.effort } else { 'medium' }));
                            maxTokens = 1000;
                            temperature = ($(if ($saved.temperature) { $saved.temperature } else { 0.7 })) }
    dataDir = './data'
}
if ($selectedChannel -eq "telegram") {
    $earlyConfig["telegram"] = [ordered]@{ botToken = $botToken; botUsername = $botUsername; allowedChatIds = @() }
} else {
    $earlyConfig["line"] = [ordered]@{ channelSecret = $lineSecret; channelAccessToken = $lineToken; webhookPort = [int]$linePort }
}
WriteUtf8NoBom 'config.json' ($earlyConfig | ConvertTo-Json -Depth 5)

# ----- [4/6] LLM Provider + Model -----
Write-Section 4 "LLM provider" "預設 Google Gemini API (OpenAI-compat endpoint)"

Write-Host "  Gemini key: https://aistudio.google.com/apikey" -ForegroundColor DarkGray
Write-Host "  OpenRouter: https://openrouter.ai/keys"      -ForegroundColor DarkGray
Write-Host ""
$providerIdx = Select-Menu "Provider:" @(
    "Google Gemini API",
    "OpenRouter"
)
Write-Host ""

if ($providerIdx -eq 0) {
    Write-Host "  (輸入時不會顯示任何字元，直接打完按 Enter)" -ForegroundColor DarkGray
    $savedGeminiKey = if ($saved.apiKey -match '^(AIza|AQ\.)') { $saved.apiKey } else { $null }
    $llmKey = Read-SecretValidatedOrKeep `
        "Gemini API Key" `
        '^[A-Za-z0-9][A-Za-z0-9._-]{19,}$' `
        "從 Google AI Studio 取得, 至少 20 字" `
        $savedGeminiKey
    Write-Ok "API Key 格式 OK"

    Write-Host ""
    Write-Host "  Gemma 4 -> native endpoint, free tier" -ForegroundColor DarkGray
    Write-Host "  Gemini 3.x / 2.5 -> OpenAI-compat endpoint" -ForegroundColor DarkGray
    $geminiModels = @(
        "gemma-4-31b-it",
        "gemma-4-26b-a4b-it",
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite",
        "gemini-3.1-pro-preview",
        "gemini-3-flash-preview",
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-pro"
    )
    $modelOptions = $geminiModels + @("Other (manual input)")
    $modelIdx = Select-Menu "選模型:" $modelOptions
    if ($modelIdx -lt $geminiModels.Count) {
        $llmModel = $geminiModels[$modelIdx]
    } else {
        Write-Host ""
        $modelDefault = if ($saved.model) { $saved.model } else { "gemma-4-31b-it" }
        $llmModel = Read-WithDefault "Model id" $modelDefault
    }

    # Gemma 模型只能透過 Gemini native endpoint, Gemini 系列走 OpenAI-compat
    if ($llmModel -like "gemma-*") {
        $llmEndpointStyle = "gemini-native"
        $llmBaseUrl = "https://generativelanguage.googleapis.com/v1beta"
    } else {
        $llmEndpointStyle = "openai"
        $llmBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    }
    Write-Ok ("Model: " + $llmModel + "  (" + $llmEndpointStyle + ")")
} else {
    $llmEndpointStyle = "openai"
    $llmBaseUrl = "https://openrouter.ai/api/v1/chat/completions"
    Write-Host "  (輸入時不會顯示任何字元，直接打完按 Enter)" -ForegroundColor DarkGray
    $savedOrKey = if ($saved.apiKey -match '^sk-') { $saved.apiKey } else { $null }
    $llmKey = Read-SecretValidatedOrKeep `
        "OpenRouter API Key (sk-or-v1-...)" `
        '^sk-(or-v1-)?[A-Za-z0-9_-]{20,}$' `
        "sk-or-v1- 開頭" `
        $savedOrKey
    Write-Ok "API Key 格式 OK"

    Write-Host ""
    $orModels = @(
        "google/gemma-4-31b-it:free",
        "google/gemma-4-26b-a4b-it:free",
        "google/gemini-3.5-flash",
        "google/gemini-3.1-flash-lite",
        "google/gemini-3.1-pro-preview",
        "google/gemini-2.5-flash",
        "google/gemini-2.5-pro",
        "anthropic/claude-3.5-haiku",
        "deepseek/deepseek-chat"
    )
    $modelOptions = $orModels + @("Other (manual input)")
    $modelIdx = Select-Menu "選模型:" $modelOptions
    if ($modelIdx -lt $orModels.Count) {
        $llmModel = $orModels[$modelIdx]
    } else {
        Write-Host ""
        $modelDefault = if ($saved.model) { $saved.model } else { "google/gemma-4-31b-it:free" }
        $llmModel = Read-WithDefault "Model id" $modelDefault
    }
    Write-Ok ("Model: " + $llmModel)
}

Write-Host ""
$tempDefault = if ($null -ne $saved.temperature) { [string]$saved.temperature } else { "0.7" }
$llmTemp = Read-WithDefault "Temperature (0.0-1.5)" $tempDefault

Write-Host ""
# Reasoning effort default — overridable at runtime via Telegram /effort
$EffortOptions = @(
    "medium  - 預設, 平衡品質與速度 (Gemini 3: thinkingBudget=1024)",
    "low     - 直覺回覆, 最快 (Gemini 3: thinkingBudget=0)",
    "high    - 深度推理, 較慢 (Gemini 3: thinkingBudget=8192)"
)
# Pre-select the saved value if re-running setup
$effortPromptTitle = "Reasoning effort default (Gemini 3 thinkingBudget):"
if ($saved.effort) {
    $effortPromptTitle = "Reasoning effort default (目前: $($saved.effort)):"
}
$effortIdx = Select-Menu $effortPromptTitle $EffortOptions
switch ($effortIdx) {
    0 { $llmEffort = "medium" }
    1 { $llmEffort = "low" }
    2 { $llmEffort = "high" }
    default { $llmEffort = "medium" }
}
Write-Ok ("Effort default: " + $llmEffort)

# Persist credentials immediately so later failure doesn't lose what we have
$midConfig = [ordered]@{
    channel = $selectedChannel
    llm     = [ordered]@{ apiKey = $llmKey; baseUrl = $llmBaseUrl; model = $llmModel;
                            endpointStyle = $llmEndpointStyle; effort = $llmEffort;
                            maxTokens = 1000;
                            temperature = [double]$llmTemp }
    dataDir = './data'
}
if ($selectedChannel -eq "telegram") {
    $midConfig["telegram"] = [ordered]@{ botToken = $botToken; botUsername = $botUsername; allowedChatIds = @() }
} else {
    $midConfig["line"] = [ordered]@{ channelSecret = $lineSecret; channelAccessToken = $lineToken; webhookPort = [int]$linePort }
}
WriteUtf8NoBom 'config.json' ($midConfig | ConvertTo-Json -Depth 5)
Write-Ok "config.json saved (credentials)"

# ----- [5/6] Reminders -----
Write-Section 5 "Reminders and timezone" "用餐模式 + 時區 (之後可在 bot 用 /reminders 改)"

if ($saved.mealReminders -and $saved.mealReminders.Count -gt 0) {
    $current = ($saved.mealReminders -join ", ")
    Write-Host "  目前已儲存的用餐提醒: $current" -ForegroundColor DarkGray
    Write-Host "  Enter 保留, 或選 preset / Custom 覆蓋" -ForegroundColor DarkGray
    $mealPatternOptions = @(
        "保留目前設定 ($current)",
        "3 餐 / 日   (07:30, 12:00, 18:00)",
        "2 餐 / 日   (12:00, 18:30)",
        "IF 一日一餐 (18:00)",
        "不要用餐提醒",
        "Custom (手動輸入時間)"
    )
} else {
    $mealPatternOptions = @(
        "3 餐 / 日   (07:30, 12:00, 18:00)",
        "2 餐 / 日   (12:00, 18:30)",
        "IF 一日一餐 (18:00)",
        "不要用餐提醒",
        "Custom (手動輸入時間)"
    )
}
$mealPatternIdx = Select-Menu "你的用餐模式:" $mealPatternOptions

$hasKeepOption = $saved.mealReminders -and $saved.mealReminders.Count -gt 0
if ($hasKeepOption) {
    switch ($mealPatternIdx) {
        0 { $mealsArr = @($saved.mealReminders) }
        1 { $mealsArr = @("07:30", "12:00", "18:00") }
        2 { $mealsArr = @("12:00", "18:30") }
        3 { $mealsArr = @("18:00") }
        4 { $mealsArr = @() }
        5 {
            Write-Host ""
            $meals = Read-WithDefault "Meal reminders (HH:MM,HH:MM,HH:MM, 空白=無)" ($saved.mealReminders -join ",")
            if ([string]::IsNullOrWhiteSpace($meals)) { $mealsArr = @() }
            else { $mealsArr = $meals.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ } }
        }
    }
} else {
    switch ($mealPatternIdx) {
        0 { $mealsArr = @("07:30", "12:00", "18:00") }
        1 { $mealsArr = @("12:00", "18:30") }
        2 { $mealsArr = @("18:00") }
        3 { $mealsArr = @() }
        4 {
            Write-Host ""
            $meals = Read-WithDefault "Meal reminders (HH:MM,HH:MM,HH:MM, 空白=無)" "07:30,12:00,18:00"
            if ([string]::IsNullOrWhiteSpace($meals)) { $mealsArr = @() }
            else { $mealsArr = $meals.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ } }
        }
    }
}
Write-Host ""
$tzDefault   = if ($saved.timezone)        { $saved.timezone }        else { "Asia/Taipei" }
$wkDefault   = if ($saved.workoutReminder) { $saved.workoutReminder } else { "20:00" }
$wklyDefault = if ($saved.weeklySummary)   { $saved.weeklySummary }   else { "SUN 21:00" }
$tzVal   = Read-WithDefault "Timezone"                       $tzDefault
$workout = Read-WithDefault "Workout reminder (HH:MM, 空白=無)" $wkDefault
$weekly  = Read-WithDefault "Weekly summary (DOW HH:MM, 空白=無)" $wklyDefault

# ----- [6/6] Write & build -----
Write-Section 6 "Write config and build" "產生 config.json, data/, 編譯 fat jar"

$config = [ordered]@{
    channel = $selectedChannel
    llm = [ordered]@{
        apiKey        = $llmKey
        baseUrl       = $llmBaseUrl
        model         = $llmModel
        endpointStyle = $llmEndpointStyle
        effort        = $llmEffort
        maxTokens     = 1000
        temperature   = [double]$llmTemp
    }
    dataDir = './data'
}
if ($selectedChannel -eq "telegram") {
    $config["telegram"] = [ordered]@{
        botToken       = $botToken
        botUsername    = $botUsername
        allowedChatIds = @()
    }
} else {
    $config["line"] = [ordered]@{
        channelSecret      = $lineSecret
        channelAccessToken = $lineToken
        webhookPort        = [int]$linePort
    }
}

WriteUtf8NoBom 'config.json' ($config | ConvertTo-Json -Depth 5)
Write-Ok "config.json saved"

# User-mutable runtime preferences (separate from secret config)
$prefs = [ordered]@{
    timezone        = $tzVal
    mealReminders   = @($mealsArr)
    workoutReminder = $workout
    weeklySummary   = $weekly
}
WriteUtf8NoBom 'data/preferences.json' ($prefs | ConvertTo-Json -Depth 5)
Write-Ok "data/preferences.json saved (mutable via /reminders)"

$null = New-Item -ItemType Directory -Force -Path (Abs 'data/skills/nutrition-advice')
$null = New-Item -ItemType Directory -Force -Path (Abs 'data/skills/workout-planning')
$null = New-Item -ItemType Directory -Force -Path (Abs 'data/logs')

if (-not (Test-Path (Abs 'data/user_profile.json'))) {
    $emptyProfile = '{"name":"","heightCm":0,"weightKg":0,"age":0,"gender":"","activityLevel":"","goal":"","bmr":0,"tdee":0,"targetCalories":0,"targetProteinG":0,"targetCarbsG":0,"targetFatG":0,"dietaryRestrictions":[],"notes":"","updatedAt":""}'
    WriteUtf8NoBom 'data/user_profile.json' $emptyProfile
}
if (-not (Test-Path (Abs 'data/memory.json'))) {
    WriteUtf8NoBom 'data/memory.json' '{"entries":[],"maxEntries":20,"maxChars":2200}'
}
Write-Ok "data/ scaffolded"

Write-Host ""
Write-Host ("  " + $mvn + " clean package -q  (請稍候)") -ForegroundColor DarkGray
& $mvn clean package -q
if ($LASTEXITCODE -ne 0) {
    Write-Warn "Build failed, retrying..."
    Start-Sleep -Seconds 3
    & $mvn clean package -q
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Build failed"
        exit $LASTEXITCODE
    }
}
Write-Ok "Build complete"

# ----- Generate 'healthy' launcher -----
$healthyCmd = @"
@echo off
setlocal enabledelayedexpansion
cd /d "$ProjectRoot" || exit /b 1
set "CMD=%~1"
if "!CMD!"=="" goto :run
if /i "!CMD!"=="stop"   goto :stop
if /i "!CMD!"=="log"    goto :log
if /i "!CMD!"=="config" goto :config
if /i "!CMD!"=="status" goto :status
if /i "!CMD!"=="help"   goto :help
echo Unknown command: %1
echo Usage: healthy [stop^|log^|config^|status^|help]
exit /b 1

:stop
taskkill /fi "WINDOWTITLE eq HealthCoach" /f >nul 2>&1
echo Health Coach Agent stopped.
exit /b

:log
if exist data\bot.log (type data\bot.log) else (echo No log file found.)
exit /b

:config
if not exist config.json (echo config.json not found. Run setup first. & exit /b 1)
echo Opening config.json...
echo   (Bot Token, API Key, Chart API Key are here)
echo.
notepad config.json
exit /b

:status
tasklist /fi "WINDOWTITLE eq HealthCoach" 2>nul | find /i "java" >nul
if %errorlevel%==0 (echo Health Coach Agent is running.) else (echo Health Coach Agent is not running.)
exit /b

:help
echo Usage: healthy [command]
echo.
echo Commands:
echo   (none)    Start the bot in background
echo   stop      Stop the bot
echo   log       Show recent log
echo   config    Edit config.json (API keys, tokens, services)
echo   status    Check if bot is running
echo   help      Show this message
exit /b

:run
start "HealthCoach" /min cmd /c "java -jar target\health-coach-agent.jar > data\bot.log 2>&1"
echo Health Coach Agent started in background.
echo   healthy stop    — stop the bot
echo   healthy log     — show log
echo   healthy config  — edit API keys
echo   healthy status  — check if running"
"@
WriteBatchFile 'healthy.cmd' $healthyCmd
Write-Ok "healthy.cmd created (CRLF)"

$userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
if (-not $userPath) { $userPath = '' }
if ($userPath -notlike "*$ProjectRoot*") {
    [Environment]::SetEnvironmentVariable('PATH', ($userPath.TrimEnd(';') + ";$ProjectRoot"), 'User')
    Write-Ok "已寫入 User PATH（下次開新視窗即可直接用 healthy）"
}
# Always update current session regardless
if ($env:PATH -notlike "*$ProjectRoot*") { $env:PATH += ";$ProjectRoot" }

Write-Host ""
Write-Host "==============================================" -ForegroundColor Green
Write-Host "   Setup 完成" -ForegroundColor Green
Write-Host "==============================================" -ForegroundColor Green
Write-Host ""
Write-Host "  啟動 bot：" -ForegroundColor White
Write-Host "    healthy          — 啟動（背景執行）" -ForegroundColor Cyan
Write-Host "    healthy stop     — 停止 bot" -ForegroundColor Cyan
Write-Host "    healthy log      — 查看 log" -ForegroundColor Cyan
Write-Host "    healthy config   — 修改 API Key / Token" -ForegroundColor Cyan
Write-Host ""
Write-Host "  此視窗可直接輸入 healthy 啟動，或開新 PowerShell 視窗都可使用。" -ForegroundColor Yellow
Write-Host ""
if ($selectedChannel -eq "telegram") {
    Write-Host "  到 Telegram 找你的 bot, /start 開始對話" -ForegroundColor DarkGray
} else {
    Write-Host "  LINE webhook 會在 http://localhost:$linePort/callback 監聽" -ForegroundColor DarkGray
    Write-Host "  用 ngrok/cloudflared 暴露後, 到 LINE Developers Console 設定 Webhook URL" -ForegroundColor DarkGray
}
Write-Host ""
