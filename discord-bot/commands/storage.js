import { EmbedBuilder } from 'discord.js'

export const name = 'storage'

export async function execute(message, _args, { supabase, getTarget }) {
  const deviceId = getTarget(message.guild.id, message.author.id)
  const prefix = deviceId ? `devices/${deviceId}/` : 'devices/'

  const { data: files, error } = await supabase.storage
    .from('incident-artifacts')
    .list(prefix, { limit: 25, sortBy: { column: 'created_at', order: 'desc' } })

  if (error) {
    return message.reply({
      embeds: [
        new EmbedBuilder()
          .setColor(0xff3355)
          .setTitle('❌ Storage Error')
          .setDescription(`\`\`\`${error.message}\`\`\``)
          .setTimestamp(),
      ],
    })
  }

  if (!files || files.length === 0) {
    return message.reply({
      embeds: [
        new EmbedBuilder()
          .setColor(0x2a2a7a)
          .setTitle('📁 Artifact Storage')
          .setDescription(deviceId ? `No artifacts for \`${deviceId.slice(0, 16)}…\`` : 'No artifacts found.')
          .setTimestamp(),
      ],
    })
  }

  const formatSize = (bytes) => {
    if (!bytes) return '—'
    if (bytes < 1024) return `${bytes}B`
    if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)}KB`
    return `${(bytes / 1048576).toFixed(1)}MB`
  }

  const now = Date.now()
  const embed = new EmbedBuilder()
    .setColor(0x3498db)
    .setTitle(`📁 Artifacts (${files.length})`)
    .setDescription(
      files.map(f => {
        const url = `${supabase.supabaseUrl}/storage/v1/object/public/incident-artifacts/${prefix}${f.name}`
        const timeAgo = f.created_at
          ? Math.floor((now - new Date(f.created_at).getTime()) / 3600000)
          : '?'
        const icon = f.name.endsWith('.zip') ? '📦' : f.name.endsWith('.png') || f.name.endsWith('.jpg') ? '🖼' : f.name.endsWith('.mp4') ? '🎥' : '📄'
        return `${icon} \`${f.name}\` ${formatSize(f.metadata?.size)} · ${timeAgo}h ago — [link](${url})`
      }).join('\n')
    )
    .setTimestamp()

  await message.reply({ embeds: [embed] })
}
