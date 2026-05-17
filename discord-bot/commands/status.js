import { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } from 'discord.js'
import { errorEmbed, deviceEmbed, actionButtons } from './_utils.js'

export const name = 'status'

export async function execute(message, _args, { supabase, getTarget }) {
  const deviceId = getTarget(message.guild.id, message.author.id)

  if (!deviceId) {
    return message.reply({
      embeds: [errorEmbed('No active target. Use `!target <device_id>` first, or `!devices` to list.')],
    })
  }

  const { data: dev } = await supabase
    .from('devices')
    .select('*')
    .eq('device_id', deviceId)
    .single()

  if (!dev) {
    return message.reply({
      embeds: [errorEmbed(`Device \`${deviceId}\` not found in database.`)],
    })
  }

  await message.reply({ embeds: [deviceEmbed(dev)], components: [actionButtons(deviceId)] })
}
