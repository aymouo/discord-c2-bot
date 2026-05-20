import { ButtonBuilder, ButtonStyle, ActionRowBuilder, StringSelectMenuBuilder, StringSelectMenuOptionBuilder } from 'discord.js'
import { E } from '../utils/index.js'

const BTN = { primary: ButtonStyle.Primary, secondary: ButtonStyle.Secondary, success: ButtonStyle.Success, danger: ButtonStyle.Danger }

function btn(id, label, emoji, style = 'danger') {
  return new ButtonBuilder().setCustomId(id).setEmoji(emoji).setLabel(label).setStyle(BTN[style] || BTN.danger)
}

function actionRow(...btns) {
  const rows = []
  for (let i = 0; i < btns.length; i += 5) rows.push(new ActionRowBuilder().addComponents(...btns.slice(i, i + 5)))
  return rows
}

export const B = {
  victims:    btn('devices',   'VICTIMS',   E.ghost,   'primary'),
  screenshot: btn('screenshot','SCREENSHOT',E.eye,     'danger'),
  stream:     btn('stream',    'LIVE',      '📡',      'danger'),
  shell:      btn('shell_btn', 'SHELL',     '💻',      'primary'),
  target:     btn('target',    'TARGET',    E.target,  'success'),
  broadcast:  btn('broadcast', 'BROADCAST', E.bomb,    'danger'),
  info:       btn('info',      'INTEL',     E.bone,    'primary'),
  menu:       btn('menu',      'HOME',      '🏠',      'secondary'),
  help:       btn('help',      'COMMANDS',  E.web,     'secondary'),
}

export const MENU_BTNS = [
  ...actionRow(B.victims, B.screenshot, B.stream, B.shell),
  ...actionRow(B.target, B.broadcast, B.info),
  ...actionRow(B.menu, B.help),
].flat()

export const HELP_BTNS = actionRow(B.menu).flat()

export const RESULT_BTNS = [
  ...actionRow(B.screenshot, B.stream, B.shell),
  ...actionRow(B.victims, B.target, B.menu),
].flat()

export function alertBtnsOnline(chId) {
  return [
    new ActionRowBuilder().addComponents(
      btn('a_menu_' + chId, 'HOME', '🏠', 'secondary'),
      btn('a_victims_' + chId, 'VICTIMS', '👻', 'primary'),
      btn('a_ss_' + chId, 'SCREENSHOT', '📸', 'danger'),
      btn('a_stream_' + chId, 'LIVE', '📡', 'danger'),
      btn('a_cmd_' + chId, 'SHELL', '💻', 'primary'),
    ),
  ]
}

export function alertBtnsOffline(chId) {
  return [
    new ActionRowBuilder().addComponents(
      btn('a_menu_' + chId, 'HOME', '🏠', 'secondary'),
      btn('a_victims_' + chId, 'VICTIMS', '👻', 'primary'),
    ),
  ]
}

export function paginationRow(disabled = false) {
  return [new ActionRowBuilder().addComponents(
    new ButtonBuilder().setCustomId('prev').setLabel('◀ PREV').setStyle(ButtonStyle.Primary).setDisabled(disabled),
    new ButtonBuilder().setCustomId('next').setLabel('NEXT ▶').setStyle(ButtonStyle.Primary).setDisabled(disabled),
  )]
}

export function devSelectPages(channels) {
  const arr = [...channels.values()]
  const pages = []
  const perPage = 25
  for (let i = 0; i < arr.length; i += perPage) {
    const slice = arr.slice(i, i + perPage)
    const p = Math.floor(i / perPage) + 1
    const t = Math.ceil(arr.length / perPage)
    const menu = new StringSelectMenuBuilder()
      .setCustomId('sel')
      .setPlaceholder(`Select victim (page ${p}/${t})...`)
      .addOptions(slice.map(ch => new StringSelectMenuOptionBuilder().setLabel(ch.name).setDescription(`ID: ${ch.id}`).setValue(ch.id)))
    const comps = [new ActionRowBuilder().addComponents(menu)]
    if (t > 1) {
      comps.push(new ActionRowBuilder().addComponents(
        new ButtonBuilder().setCustomId('sel_prev').setLabel('◀ PREV').setStyle(ButtonStyle.Primary).setDisabled(p === 1),
        new ButtonBuilder().setCustomId('sel_next').setLabel('NEXT ▶').setStyle(ButtonStyle.Primary).setDisabled(p === t),
      ))
    }
    pages.push({ components: comps, page: p, total: t })
  }
  return pages
}
