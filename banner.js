import { C, E, ts, randGif } from './utils/index.js'
import { EmbedBuilder } from 'discord.js'

export function novaEmbed(title, status = 'info', desc = '', opts = {}) {
  const color = C[status] || C.blood
  const e = new EmbedBuilder()
    .setColor(color)
    .setTitle(title)
    .setDescription(desc)
    .setFooter({ text: opts.footer || `🌸  ${ts()}  ─────────────────`, iconURL: opts.footerIcon || undefined })
  if (opts.thumb) e.setThumbnail(opts.thumb)
  if (opts.image) e.setImage(opts.image)
  if (opts.fields) e.addFields(opts.fields)
  if (opts.author) e.setAuthor(opts.author)
  if (opts.timestamp) e.setTimestamp()
  return { embeds: [e] }
}

export function novaLogoEmbed(status = 'online', extra = '') {
  return novaEmbed(`${E.torii}  S H I N S E N K Y O  C 2  ${E.sakura}`, status,
    `${E.ghost} **${status === 'online' ? 'TAO FLOW' : 'STANDBY'}** ${E.sakura}\n\n${extra || ''}`,
    { footer: `🌸  ${ts()}  ─────────────────`, image: randGif() })
}
