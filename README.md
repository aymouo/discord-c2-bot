<p align="center">
  <img src="https://media.tenor.com/0qHYA8Ol28I/gabimaru-ninpou.gif" width="100%" alt="Shinsenkyo Banner">
</p>

<h1 align="center">⚔️ SHINSENKYO ⚔️</h1>
<p align="center">
  <b>Discord C2 Bot — Android Command & Control</b>
</p>

<p align="center">
  <a href="https://nodejs.org/">
    <img src="https://img.shields.io/badge/node-%3E%3D18-8B0000?style=for-the-badge&logo=nodedotjs&logoColor=2FFFD4&labelColor=0D0D0D" alt="Node.js">
  </a>
  <a href="https://discord.js.org/">
    <img src="https://img.shields.io/badge/discord.js-v14-8B0000?style=for-the-badge&logo=discord&logoColor=2FFFD4&labelColor=0D0D0D" alt="Discord.js">
  </a>
  <a href="https://koyeb.com/">
    <img src="https://img.shields.io/badge/deploy-koyeb-8B0000?style=for-the-badge&logo=koyeb&logoColor=2FFFD4&labelColor=0D0D0D" alt="Koyeb">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-8B0000?style=for-the-badge&logo=openaccess&logoColor=2FFFD4&labelColor=0D0D0D" alt="License">
  </a>
</p>

<p align="center">
  <img src="https://img.shields.io/github/stars/aymouo/discord-c2-bot?style=social" alt="Stars">
  <img src="https://img.shields.io/github/last-commit/aymouo/discord-c2-bot?style=social" alt="Last commit">
  <img src="https://img.shields.io/github/repo-size/aymouo/discord-c2-bot?style=social" alt="Repo size">
</p>

<br>

---

<p align="center">
  <b>Communicate with and control remote Android devices through Discord channels.</b><br>
  Real-time device commands · Live screen streaming · AI co-pilot · Worm module deployment<br>
  Hell's Paradise (Jigokuraku) themed UI · 200+ commands · Multi-device management
</p>

---

<br>

## 📡 LIVE SCREEN STREAMING

<img src="https://media.tenor.com/1OqhJMiLTXc/hells-paradise-fight.gif" align="right" width="280" alt="Streaming Demo">

Stream your device screen live to a Discord text channel as JPEG frames. Optional Discord voice channel integration for real-time audio relay.

- Configurable FPS (1–10) and resolution
- Auto-cleans old frames
- REST API for device-side frame submission
- Voice channel support for audio streaming

<br clear="right"/>

---

## 🧠 AI CO-PILOT

<img src="https://media.tenor.com/0qHYA8Ol28I/gabimaru-ninpou.gif" align="left" width="280" alt="AI Co-pilot">

**FREE** — uses Google Gemini (60 req/min, no credit card needed). Multi-provider: Gemini, Ollama (local), Claude, NVIDIA.

- Context-aware — remembers recent device interactions per session
- Command suggestions & shell command generation
- Log analysis and campaign planning
- Swarm mode for multi-agent orchestration

<br clear="left"/>

---

## 🎮 DEVICE CONTROL

<img src="https://media.tenor.com/bvXGkfQ7zEI/tao-energy-aura.gif" align="right" width="280" alt="Device Control">

**200+ commands** across every category:
- Shell, file manager, screenshot, camera, mic
- SMS, contacts, location, clipboard, notifications
- Processes, network, battery, installed apps
- WiFi passwords, netstat, sysinfo, sysprop

Multi-device management with auto-targeting and broadcast mode — each device in its own `phantom-` channel.

<br clear="right"/>

---

## 📁 FILE MANAGEMENT

| Category | Commands |
|----------|----------|
| **Navigation** | `ls`, `cd`, `tree`, `find`, `pwd`, `stat` |
| **Read** | `cat`, `info`, `disk`, `recent`, `download` |
| **Write** | `rm`, `mv`, `cp`, `mkdir`, `upload` |
| **Storage** | Storage overview with usage stats |

---

## 🔔 NOTIFICATION INTERCEPTION

Real-time notification capture from target device. Auto-forwards to Discord alerts channel. OTP / SMS interception with auto-reply support.

---

## 📦 MODULE SYSTEM

<img src="https://media.tenor.com/kqYwvzN3p9I/shinsenkyo-flower.gif" align="left" width="280" alt="Modules">

Deploy encrypted modules (AES-256-GCM with IV-prepended format) from Supabase storage.

- Module lifecycle: load → start → stop → exec
- Worm module for propagation
- APK upload and sideload delivery

<br clear="left"/>

---

## ⛏️ CRYPTO MINING

Built-in Monero miner with pool management. Start/stop mining remotely. Mining status and statistics in real-time via embeds.

---

## 💥 EXPLOIT FRAMEWORK

CVE-based exploit delivery (Dirty Pipe, CVE-2019-2215, CVE-2023-0386). Auto-pwn and privilege escalation attempt commands built-in.

---

## 🔊 MONITORING & ALERTS

| Feature | Description |
|---------|-------------|
| **Health checks** | Every 5 minutes — heartbeat-based liveness detection |
| **Online/offline alerts** | Real-time Discord notifications for device status changes |
| **Status cards** | Rich embeds with online time, battery, IP, device model |
| **Connection watchdog** | 30s ping interval, 2min health check, auto-reconnect |

---

## 🎨 UI & PRESENTATION

Hell's Paradise (Jigokuraku) theme throughout:

- **Color palette:** `#0D0D0D` void · `#8B0000` blood · `#E8B4B8` sakura · `#2FFFD4` tao · `#C9A84C` gold
- Random anime GIFs from 10 curated Jigokuraku scenes on every embed
- Paginated device list with navigation buttons
- Rich embeds with inline status indicators
- ANSI-styled console banner and command output

<p align="center">
  <img src="https://media.tenor.com/7fGkLWO6c5M/tensen-transform.gif" width="280" alt="Tensen">
  <img src="https://media.tenor.com/Zcz5MvXDXKM/sagiri-draw.gif" width="280" alt="Sagiri">
  <img src="https://media.tenor.com/tnmgsUEEUnG/sagiri-sword.gif" width="280" alt="Sagiri Sword">
</p>

---

## 📋 BOT COMMANDS

### Core Commands

| Command | Description |
|---------|-------------|
| `!help` | Command reference with categories |
| `!menu` | Control panel with action buttons |
| `!devices` | List all connected devices (paginated) |
| `!target <n>` | Select a device by index |
| `!untarget` | Clear current target |
| `!broadcast <cmd>` | Send command to all devices |
| `!health` | Check bot and connection health |
| `!setavatar` | Change bot avatar to random GIF |

### Feature Commands

| Command | Description |
|---------|-------------|
| `!ai <prompt>` | Ask the AI copilot |
| `!campaign` | Campaign management |
| `!analyze <log>` | Analyze logs with AI |
| `!stream` | Start screen stream to device |
| `!miner` | Crypto mining controls |
| `!upload` | Send files to a device |
| `!module` | Worm module management |

### Device Commands

```
ping    info     screenshot  camera    location    contacts
sms     call_log mic         clipboard shell       keylog
status  wifi     battery     processes installed   torch
vibrate uptime   notifications admin    overlay     click
input   open     screen      gesture   pin         ip
stream  netstat  sysinfo     sysprop   apps        storage
grabber record   dump        exploit   autopwn     phish
blockchain plugin worm       brain     inject      module
dir     ls       tree        find      cat         disk
download rm      mv          cp        mkdir       packages
accounts fcm     watchdog    keepalive restart     debug
payload … and 200+ more
```

---

## 🚀 QUICK START

### Prerequisites
- **Node.js 18+**
- **Discord bot token** — [Create one here](https://discord.com/developers/applications)
- **Discord server** with a channel for commands

### Setup

```bash
# Clone
git clone https://github.com/aymouo/discord-c2-bot.git
cd discord-c2-bot

# Configure
cp .env.example .env
# Edit .env: set DISCORD_TOKEN, ALLOWED_CHANNEL_ID, CRYPTO_KEY

# Install & run
npm install
npm start
```

### Docker

```bash
docker build -t shinsenkyo-bot .
docker run -d --env-file .env shinsenkyo-bot
```

### Deploy to Koyeb (Free)

Optimized for Koyeb free tier — connection watchdog pings every 30s, health check every 2min, auto-reconnect on stale/dropped connections. Compatible with Railway, Heroku, or any Node.js host.

---

## 🏗️ ARCHITECTURE

```
discord-c2-bot/
├── index.js              # Main bot — commands, events, state
├── stream.js             # Live screen streaming server (Express + Discord Voice)
├── banner.js             # Console ASCII startup banner
├── statusCard.js         # Device status card image generator
├── bot/
│   ├── embeds.js         # Embed builders (bloodEmbed, alertEmbed, etc.)
│   ├── formatter.js      # Device data formatters
│   ├── minerEmbed.js     # Mining stats embed builder
│   └── state.js          # Persistent state store (file-backed)
├── ai-copilot/
│   ├── index.js          # AI provider abstraction (Gemini/Ollama/Claude/NVIDIA)
│   ├── controller.js     # Campaign & command analysis
│   ├── relay.js          # Blockchain/IPFS relay
│   ├── context.js        # Session context manager
│   ├── commands.js       # AI command definitions
│   └── swarm.js          # Multi-agent swarm mode
├── lib/
│   └── crypto.js         # AES-256-GCM encryption/decryption
├── utils/
│   └── index.js          # Shared constants, helpers, color/emoji maps
├── tools/                # Utility scripts
├── .env.example          # Configuration template
├── gif.txt               # Random GIF pool (10 Hell's Paradise GIFs)
├── icons_gif.txt         # Icon GIFs for embeds
├── BOTMAP.md             # Internal code flow documentation
├── Dockerfile            # Container build
└── railway.json          # Railway deployment config
```

---

## ⚙️ CONFIGURATION

| Variable | Required | Description |
|----------|----------|-------------|
| `DISCORD_TOKEN` | ✅ | Bot token from Discord Developer Portal |
| `ALLOWED_CHANNEL_ID` | ✅ | Channel where bot responds to commands |
| `CRYPTO_KEY` | ✅ | Encryption key for C2 payloads |
| `GEMINI_API_KEY` | ❌ | Free Google Gemini key for AI features |
| `PORT` | ❌ | Express server port (default: 8000) |
| `ALERTS_CHANNEL_ID` | ❌ | Separate channel for online/offline alerts |

Full reference: [`.env.example`](.env.example)

---

## 🔗 RELATED

| Project | Description |
|---------|-------------|
| [phantom-c2-android](https://github.com/aymouo/phantom-c2-android) | Android implant paired with this bot |

---

<p align="center">
  <img src="https://i.pinimg.com/originals/49/89/50/49895086579ae12848a3b7bebd2732ae.gif" width="60%" alt="Shinsenkyo">
</p>

<p align="center">
  <b>SHINSENKYO</b> — C2 Bot<br>
  <sub>Built with 🩸 and ☯️</sub>
</p>

---

> **Disclaimer** — This project is for educational and authorized security testing purposes only.
