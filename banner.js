import { smallCaps, mono, bold, createBox, A, C, E, ts, clockText } from './utils/index.js'
import { EmbedBuilder } from 'discord.js'

const VERSION = '3.1'
const ASCII_LOGO = [
  '',
  '      ███╗   ██╗ ██████╗ ██╗   ██╗ █████╗ ',
  '      ████╗  ██║██╔═══██╗██║   ██║██╔══██╗',
  '      ██╔██╗ ██║██║   ██║██║   ██║███████║',
  '      ██║╚██╗██║██║   ██║╚██╗ ██╔╝██╔══██║',
  '      ██║ ╚████║╚██████╔╝ ╚████╔╝ ██║  ██║',
  '      ╚═╝  ╚═══╝ ╚═════╝   ╚═══╝  ╚═╝  ╚═╝',
  '                  ██████╗██████╗ ',
  '                 ██╔════╝╚════██╗',
  '                 ██║      █████╔╝',
  '                 ██║     ██╔═══╝ ',
  '                 ╚██████╗███████╗',
  '                  ╚═════╝╚══════╝',
  ''
].join('\n')

const MSF_LINE = `=∴ ${mono('NOVA C2')}  v${VERSION}   ∴━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`
const MODULES = [
  '+ ── ──=[   worm      brain     inject    config    ai       ]',
  '+ ── ──=[   grabber   miner     stream    exploit   autopwn  ]',
  '+ ── ──=[   contacts  sms       calls     location  wifi     ]',
  '+ ── ──=[   screens   camera    mic       shell     files    ]',
  '+ ── ──=[   stealth   persist   admin     overlay   phish    ]',
  '+ ── ──=[   ${DEV_CMDS?.size ?? 130}+ commands  |  unlimited targets  |  24/7 C2  ]',
].join('\n')

export function bannerCard() {
  const ascii = `\`\`\`ansi\n${A.brightCyan}${ASCII_LOGO}${A.reset}\`\`\``
  const info = createBox(
    `${A.brightCyan}${smallCaps('nova command & control')}${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}VERSION${A.reset}    : ${A.brightRed}${VERSION}${A.reset} ─ ${A.grey}release${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}STATUS${A.reset}     : ${A.brightGreen}● ACTIVE${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}MODULES${A.reset}    : worm brain inject config ai grabber\n` +
    `${A.cyan}┃${A.reset} ${A.green}COMMANDS${A.reset}   : 130+  |  ${A.grey}type !help for full list${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.grey}${MSF_LINE}${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.grey}${MODULES.split('\n').join(`\n${A.cyan}┃${A.reset} ${A.grey}`)}${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.green}◈ ${clockText()}${A.reset}`,
    'neon', 64
  )
  return { ascii, box: info }
}

export function novaEmbed(title, status = 'info', desc = '', opts = {}) {
  const ST_COL = { online: C.neon, offline: C.void, warning: C.gold, danger: C.electric, info: C.purple, blood: C.sharingan, venom: C.venom, shadow: C.shadow }
  const color = ST_COL[status] || C.sharingan
  const e = new EmbedBuilder()
    .setColor(color)
    .setTitle(title)
    .setDescription(desc)
    .setFooter({ text: opts.footer || `${E.sharingan} NOVA C2  ∴  ${ts()}`, iconURL: opts.footerIcon || undefined })
  if (opts.thumb) e.setThumbnail(opts.thumb)
  if (opts.image) e.setImage(opts.image)
  if (opts.fields) e.addFields(opts.fields)
  if (opts.author) e.setAuthor(opts.author)
  if (opts.timestamp) e.setTimestamp()
  return { embeds: [e] }
}

export function novaLogoEmbed(status = 'online', extra = '') {
  const { ascii, box } = bannerCard()
  const total = extra || ''
  return novaEmbed(`${E.sharingan}  N O V A  C 2  ${E.sharingan}`, status,
    `${ascii}\`\`\`ansi\n${box}\n\`\`\`\n${total}`,
    { footer: `${E.skull} NOVA C2  v${VERSION}  ∴  ${ts()}`, thumb: undefined, noThumb: true })
}

export function consoleLine(cmd, args = '', desc = '', style = 'cyan') {
  const colors = { cyan: A.cyan, red: A.red, green: A.green, yellow: A.yellow, magenta: A.magenta, grey: A.grey }
  const c = colors[style] || A.cyan
  return `${A.brightGreen}msf${A.reset} > ${c}!${cmd}${args ? ' ' + args : ''}${A.reset}  ${A.grey}${desc}${A.reset}`
}
