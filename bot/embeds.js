import { EmbedBuilder, AttachmentBuilder, ButtonBuilder, ButtonStyle, ActionRowBuilder } from 'discord.js'
import { C, E, bold, ts, randGif } from '../utils/index.js'
import { ICONS } from '../icons.js'

export function btn(id, label, emoji, style = 'danger') {
  const STYLES = { primary: ButtonStyle.Primary, secondary: ButtonStyle.Secondary, success: ButtonStyle.Success, danger: ButtonStyle.Danger }
  return new ButtonBuilder().setCustomId(id).setEmoji(emoji).setLabel(label).setStyle(STYLES[style] || STYLES.danger)
}

export function actionRow(...btns) {
  const rows = []
  for (let i = 0; i < btns.length; i += 5) rows.push(new ActionRowBuilder().addComponents(...btns.slice(i, i + 5)))
  return rows
}

export function paginationRow(disabled = false) {
  return [new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('prev').setLabel('◀ PREV').setStyle(ButtonStyle.Primary).setDisabled(disabled),
    new ButtonBuilder().setCustomId('next').setLabel('NEXT ▶').setStyle(ButtonStyle.Primary).setDisabled(disabled),
  )]
}

const ST_COL = { online: C.neon, offline: C.void, warning: C.gold, danger: C.electric, info: C.purple }

export function bloodEmbed(title, status, desc, opts = {}) {
  const thumb = opts.thumb || randGif()
  const image = opts.image || randGif()
  const e = new EmbedBuilder()
    .setColor(ST_COL[status] || C.sharingan)
    .setTitle(title)
    .setDescription(desc)
    .setThumbnail(thumb)
    .setImage(image)
    .setFooter({ text: opts.footer || `${E.skull} PHANTOM UCHIHA ⚡ ${ts()}`, iconURL: ICONS?.footer || undefined })
  if (opts.fields) e.addFields(opts.fields)
  return { embeds: [e] }
}
