import { sendCommand, commandSentEmbed, errorEmbed } from './_utils.js'

export const name = 'bypass'

export async function execute(message, _args, { supabase, getTarget }) {
  const deviceId = getTarget(message.guild.id, message.author.id)
  if (!deviceId) {
    return message.reply({ embeds: [errorEmbed('No active target. Use !target first.')] })
  }
  await message.channel.sendTyping()
  const cmd = await sendCommand(supabase, deviceId, 'bypass', {})
  if (!cmd) return message.reply('❌ Failed.')
  await message.reply({ embeds: [commandSentEmbed(deviceId, 'bypass', cmd.id)] })
}
