import fs from 'fs'

const GIFS = fs.readFileSync(new URL('../gif.txt', import.meta.url), 'utf8').split('\n').filter(l => l.trim()).map(l => l.trim())

export function randGif() { return GIFS.length ? GIFS[Math.floor(Math.random() * GIFS.length)] : undefined }

export function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function ts() { return `<t:${Math.floor(Date.now() / 1000)}:R>` }

export function clockText() {
  const d = new Date()
  const days = ['SUN','MON','TUE','WED','THU','FRI','SAT']
  const months = ['JAN','FEB','MAR','APR','MAY','JUN','JUL','AUG','SEP','OCT','NOV','DEC']
  const h = String(d.getHours()).padStart(2,'0')
  const m = String(d.getMinutes()).padStart(2,'0')
  const s = String(d.getSeconds()).padStart(2,'0')
  return `${days[d.getDay()]} ${d.getDate()} ${months[d.getMonth()]} ${d.getFullYear()}  ${h}:${m}:${s}`
}

export function bold(s) {
  return s.replace(/[a-zA-Z]/g, c => {
    const n = c.charCodeAt(0)
    if (n >= 65 && n <= 90) return String.fromCodePoint(0x1D400 + n - 65)
    if (n >= 97 && n <= 122) return String.fromCodePoint(0x1D41A + n - 97)
    return c
  })
}

export function mono(s) {
  return s.replace(/[a-zA-Z0-9]/g, c => {
    const n = c.charCodeAt(0)
    if (n >= 65 && n <= 90) return String.fromCodePoint(0x1D670 + n - 65)
    if (n >= 97 && n <= 122) return String.fromCodePoint(0x1D68A + n - 97)
    if (n >= 48 && n <= 57) return String.fromCodePoint(0x1D7F6 + n - 48)
    return c
  })
}

export function smallCaps(s) {
  const map = { a:'ᴀ',b:'ʙ',c:'ᴄ',d:'ᴅ',e:'ᴇ',f:'ꜰ',g:'ɢ',h:'ʜ',i:'ɪ',j:'ᴊ',k:'ᴋ',l:'ʟ',m:'ᴍ',n:'ɴ',o:'ᴏ',p:'ᴘ',q:'ǫ',r:'ʀ',s:'ꜱ',t:'ᴛ',u:'ᴜ',v:'ᴠ',w:'ᴡ',x:'x',y:'ʏ',z:'ᴢ' }
  return s.toLowerCase().split('').map(c => map[c] || c).join('')
}

const BOX = {
  neon: { tl: '╭', tr: '╮', bl: '╰', br: '╯', h: '─', v: '│' },
  heavy: { tl: '┏', tr: '┓', bl: '┗', br: '┛', h: '━', v: '┃' },
  double: { tl: '╔', tr: '╗', bl: '╚', br: '╝', h: '═', v: '║' },
  skull: { tl: '💀', tr: '💀', bl: '💀', br: '💀', h: '═', v: '║' },
}

export function createBox(content, style = 'neon', width = 42) {
  const s = BOX[style]
  const lines = content.split('\n')
  const mL = Math.max(...lines.map(l => l.replace(/\u001b\[.*?m/g, '').length), width)
  let r = s.tl + s.h.repeat(mL + 2) + s.tr + '\n'
  for (const l of lines) { const cl = l.replace(/\u001b\[.*?m/g, ''); r += s.v + ' ' + l + ' '.repeat(mL - cl.length) + ' ' + s.v + '\n' }
  r += s.bl + s.h.repeat(mL + 2) + s.br
  return r
}

export function barAnim(current, total, length = 10) {
  const fill = Math.round((current / total) * length)
  const empty = length - fill
  return '█'.repeat(fill) + '░'.repeat(empty)
}

export const C = {
  neon: 0xFF003C, electric: 0x00F0FF, purple: 0x9B30FF,
  blood: 0x8A0303, venom: 0x39FF14, gold: 0xFFD700,
  dark: 0x0A0A0A, void: 0x1A0A0A, ash: 0x2D2D2D,
  sharingan: 0xCC0000, mangekyo: 0xFF0000, eternal: 0x8B0000,
  amaterasu: 0xFF4500, itachi: 0xDC143C, sasuke: 0xFF6347,
  madara: 0x800000, obito: 0xA52A2A, fire: 0xFF7F50,
  shadow: 0x2F4F4F, curse: 0x8B008B, sealing: 0x4B0082,
  nova: 0xDC143C, crimson: 0x8B0000, bloodred: 0x660000,
}

export const E = {
  skull: '💀', knife: '🔪', heart: '🫀', bone: '🦴',
  coffin: '⚰️', ghost: '👻', spider: '🕷️', web: '🕸️',
  bomb: '💣', warning: '⚠️', syringe: '💉',
  microbe: '🦠', brain: '🧠', eye: '👁️', target: '🎯',
  zap: '⚡', chain: '⛓️', crown: '👑', diamond: '💎',
  clock: '🕐', pick: '⛏️',
  sharingan: '👁️‍🗨️', flame: '🔥', kunai: '🗡️', shuriken: '⭐',
  scroll: '📜', mask: '🎭', cat: '🐱', fox: '🦊',
  demon: '👹', angel: '😇', sword: '⚔️', shield: '🛡️',
  book: '📖', star: '🌟', moon: '🌙', sun: '☀️',
  check: '✅', cross: '❌', warning2: '⚠️', info: 'ℹ️',
  arrow: '➡️', up: '⬆️', down: '⬇️', left: '⬅️',
  online: '🟢', offline: '🔴', loading: '⏳', sparkles: '✨',
  rocket: '🚀', pulse: '💫', ring: '💍', hourglass: '⏱️',
  satellite: '📡', tools: '🔧',
}

export const A = { reset: '\u001b[0m', grey: '\u001b[90m', red: '\u001b[31m', cyan: '\u001b[36m', green: '\u001b[32m', yellow: '\u001b[33m', magenta: '\u001b[35m', brightRed: '\u001b[91m', brightCyan: '\u001b[96m' }

export const ST_COL = { online: C.neon, offline: C.void, warning: C.gold, danger: C.electric, info: C.purple }

export const DEV_CMDS = new Set([
  'ping', 'info', 'screenshot', 'camera', 'location', 'contacts', 'sms', 'call_log',
  'mic', 'clipboard', 'persist', 'shell', 'keylog', 'status', 'debug', 'restart',
  'wifi', 'battery', 'processes', 'installed', 'torch', 'vibrate', 'uptime', 'notifications',
  'admin', 'overlay', 'click', 'input', 'open', 'screen', 'gesture', 'pin', 'ip', 'stream',
  'update', 'config', 'grabber',
  'wifipass', 'netstat', 'sysinfo', 'antidetect', 'sysprop', 'services', 'apps', 'storage',
  'miner', 'upload',
  'dir', 'ls', 'tree', 'find', 'cat', 'info', 'disk', 'recent', 'ext', 'download',
  'rm', 'mv', 'cp', 'mkdir',
])

export const BOT_CMDS = ['!help', '!menu', '!devices', '!broadcast', '!target', '!untarget', '!history', '!search', '!miner', '!upload', '!stream', '!ai', '!campaign', '!analyze']

export const VALID_CMDS = new Set([...DEV_CMDS])

export const ALERT_CMD_MAP = { ss: 'screenshot', cmd: 'shell', stream: 'stream', miner: 'miner', grabber: 'grabber', persist: 'persist', files: 'dir' }
export const BTN_ACTIONS = { screenshot: 'screenshot', stream: 'stream start', shell_btn: 'shell', grabber: 'grabber', dir: 'dir', tree: 'tree', disk: 'disk', contacts: 'contacts', sms: 'sms', location: 'location', camera: 'camera', broadcast: 'broadcast' }
