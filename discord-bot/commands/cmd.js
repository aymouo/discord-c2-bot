import { sendCommand, commandSentEmbed, errorEmbed } from './_utils.js'

export const name = 'cmd'

export async function execute(message, args, { supabase, getTarget }) {
  const deviceId = getTarget(message.guild.id, message.author.id)
  if (!deviceId) {
    return message.reply({ embeds: [errorEmbed('No active target. Use `!target <device_id>` first.')] })
  }

  const action = args[0]
  if (!action) {
    return message.reply('❌ Usage: `!cmd <action> [payload_json]`')
  }

  let payload = {}
  const payloadRaw = args.slice(1).join(' ')
  if (payloadRaw) {
    try {
      payload = JSON.parse(payloadRaw)
    } catch {
      return message.reply('❌ Invalid JSON payload.')
    }
  }

  await message.channel.sendTyping()

  const cmd = await sendCommand(supabase, deviceId, action, payload)
  if (!cmd) return message.reply('❌ Failed to dispatch command.')

  await message.reply({ embeds: [commandSentEmbed(deviceId, action, cmd.id)] })
}
