import { EmbedBuilder, ActionRowBuilder, ButtonBuilder, ButtonStyle } from 'discord.js'
import { randomGif } from './_utils.js'

const COMMANDS = [
  ['🎯 target <id>', 'Set active target device'],
  ['📡 devices', 'List all registered implants'],
  ['📊 status', 'Show active target status'],
  ['💻 shell <cmd>', 'Execute shell command on target'],
  ['⚡ cmd <action> [json]', 'Send any C2 action'],
  ['📦 deploy', 'Deploy all kernel modules'],
  ['🔓 bypass', 'Load signature bypass module'],
  ['⌨ keylog_dump', 'Dump captured keystrokes'],
  ['⌨ keylogs [app] [n]', 'Browse recent keylogs'],
  ['💉 inject <pid>', 'Trigger process injection'],
  ['🔍 diagnostic', 'Run device diagnostic'],
  ['🚨 panic', 'Load panic-safe modules'],
  ['📸 screenshot', 'Request screenshot'],
  ['📁 storage', 'List storage artifacts'],
  ['🏠 menu', 'Show dashboard hub'],
  ['ℹ help', 'Show this help'],
]

export const name = 'help'
export async function execute(message, _args) {
  const embed = new EmbedBuilder()
    .setColor(0x00f0ff)
    .setTitle('ℹ OpenAccess C2 — Commands')
    .setImage(randomGif('dashboard'))
    .setDescription([
      '```',
      'All commands use the ! prefix.',
      'Use !target <id> first, then the rest.',
      'Buttons and menus are also available.',
      '```',
      '',
      COMMANDS.map(([cmd, desc]) => `**${cmd}  —  ${desc}**`).join('\n'),
    ].join('\n'))
    .setFooter({ text: 'DESTOPIA C2 • 16 commands' })
    .setTimestamp()

  const row = new ActionRowBuilder()
    .addComponents(
      new ButtonBuilder().setCustomId('c2:menu:show').setLabel('🏠 Dashboard').setStyle(ButtonStyle.Primary),
      new ButtonBuilder().setCustomId('c2:devices:list').setLabel('📡 Devices').setStyle(ButtonStyle.Secondary),
      new ButtonBuilder().setCustomId('c2:keylogs:list').setLabel('⌨ Keylogs').setStyle(ButtonStyle.Secondary),
    )

  await message.reply({ embeds: [embed], components: [row] })
}

export async function handleButton(interaction) {
  const embed = new EmbedBuilder()
    .setColor(0x00f0ff)
    .setTitle('ℹ OpenAccess C2 — Commands')
    .setImage(randomGif('dashboard'))
    .setDescription([
      '```',
      'All commands use the ! prefix.',
      'Use !target <id> first, then the rest.',
      'Buttons and menus are also available.',
      '```',
      '',
      COMMANDS.map(([cmd, desc]) => `**${cmd}  —  ${desc}**`).join('\n'),
    ].join('\n'))
    .setFooter({ text: 'DESTOPIA C2 • 16 commands' })
    .setTimestamp()

  const row = new ActionRowBuilder()
    .addComponents(
      new ButtonBuilder().setCustomId('c2:menu:show').setLabel('🏠 Dashboard').setStyle(ButtonStyle.Primary),
      new ButtonBuilder().setCustomId('c2:devices:list').setLabel('📡 Devices').setStyle(ButtonStyle.Secondary),
      new ButtonBuilder().setCustomId('c2:keylogs:list').setLabel('⌨ Keylogs').setStyle(ButtonStyle.Secondary),
    )

  await interaction.update({ embeds: [embed], components: [row] })
}
