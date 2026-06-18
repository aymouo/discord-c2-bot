import { EmbedBuilder, ButtonBuilder, ButtonStyle, ActionRowBuilder } from 'discord.js'
import { C, ts, randGif, ST_COL } from '../utils/index.js'
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

export function bloodEmbed(title, status, desc, opts = {}) {
  const gif = randGif()
  const gifEmbed = new EmbedBuilder().setColor(C.void)
  if (gif) gifEmbed.setImage(gif)
  const e = new EmbedBuilder()
    .setColor(ST_COL[status] || C.blood)
    .setTitle(title)
    .setDescription(desc)
    .setFooter({ text: opts.footer || `🌸  ${ts()}  ─────────────────`, iconURL: ICONS.footer || undefined })
  if (opts.thumb) e.setThumbnail(opts.thumb)
  if (opts.image) e.setImage(opts.image)
  if (opts.fields) e.addFields(opts.fields)
  if (opts.noImage) return { embeds: [e] }
  return { embeds: [gifEmbed, e] }
}
