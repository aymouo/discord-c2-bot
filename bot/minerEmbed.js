import { EmbedBuilder } from 'discord.js'
import { C, E, ts } from '../utils/index.js'
import { ICONS } from '../icons.js'
import { randGif } from '../utils/index.js'

const XMR_BLOCK_REWARD = 0.6
const XMR_BLOCKS_PER_DAY = 720
const DIFFICULTY_ESTIMATE = 300_000_000

function estimateDailyXmr(hashrate) {
  const netHashrate = DIFFICULTY_ESTIMATE / 120
  const share = netHashrate > 0 ? hashrate / (netHashrate * 4) : 0
  return share * XMR_BLOCKS_PER_DAY * XMR_BLOCK_REWARD * 0.99
}

function formatHashrate(hr) {
  if (hr >= 1e12) return `${(hr / 1e12).toFixed(2)} TH/s`
  if (hr >= 1e9) return `${(hr / 1e9).toFixed(2)} GH/s`
  if (hr >= 1e6) return `${(hr / 1e6).toFixed(2)} MH/s`
  if (hr >= 1e3) return `${(hr / 1e3).toFixed(2)} kH/s`
  return `${hr.toFixed(0)} H/s`
}

function hashbar(hr, max = 5000) {
  const pct = Math.min(hr / max, 1)
  const filled = Math.round(pct * 10)
  return '█'.repeat(filled) + '░'.repeat(10 - filled)
}

export function buildMinerEmbed(text, deviceName) {
  const fields = {}
  for (const line of text.split('\n')) {
    const m = line.match(/^\*\*(.+?)\*\*\s*`(.+?)`/)
    if (m) { fields.serviceState = m[2]; continue }
    const kv = line.match(/^([A-Za-z0-9][A-Za-z0-9\s()/._-]{1,25}?):\s(.+)/)
    if (kv) fields[kv[1].trim().toLowerCase()] = kv[2].trim()
  }

  const status = fields.status || 'Idle'
  const isRunning = status.toLowerCase() === 'running'
  const rawHr = parseFloat((fields.hashrate || '').replace(/[^0-9.]/g, '')) || 0
  const hrUnit = (fields.hashrate || '').includes('kH') ? 1000 : (fields.hashrate || '').includes('MH') ? 1000000 : (fields.hashrate || '').includes('GH') ? 1000000000 : 1
  const hashrate = rawHr * hrUnit
  const sharesAccepted = parseInt((fields.shares || '').match(/(\d+)\s*accepted/)?.[1]) || 0
  const sharesRejected = parseInt((fields.shares || '').match(/(\d+)\s*rejected/)?.[1]) || 0
  const totalShares = sharesAccepted + sharesRejected
  const shareRate = totalShares > 0 ? (sharesAccepted / totalShares * 100).toFixed(1) : 0
  const difficulty = parseInt((fields.difficulty || '').replace(/[^0-9]/g, '')) || 0
  const poolOk = (fields.pool || '').toLowerCase().includes('connected')
  const threads = fields.threads || '?'
  const uptime = fields.uptime || '0m'
  const wallet = fields.wallet || '?'
  const poolAddr = fields.pool || '?'
  const daily = estimateDailyXmr(hashrate)
  const weekly = daily * 7
  const monthly = daily * 30

  const color = isRunning ? C.neon : C.void
  const statusEmoji = isRunning ? '🟢' : '🔴'
  const poolEmoji = poolOk ? '🌐' : '⏳'

  const embed = new EmbedBuilder()
    .setColor(color)
    .setTitle(`${E.pick} MINER STATUS — ${deviceName}`)
    .setThumbnail(randGif())
    .addFields(
      { name: `${statusEmoji} Status`, value: `\`${status}\` • ${uptime}`, inline: true },
      { name: '🧵 Threads', value: `\`${threads}\``, inline: true },
      { name: `${poolEmoji} Pool`, value: poolAddr.length > 30 ? `\`${poolAddr.slice(0, 28)}...\`` : `\`${poolAddr}\``, inline: true },
    )

  if (isRunning) {
    const hrStr = formatHashrate(hashrate)
    const bar = hashbar(hashrate)
    embed.addFields(
      { name: `⚡ Hashrate`, value: `\`${hrStr}\`\n\`${bar}\``, inline: false },
      { name: '✅ Shares', value: `\`${sharesAccepted}\` accepted / \`${sharesRejected}\` rejected (${shareRate}%)`, inline: true },
    )
    if (difficulty > 0) {
      embed.addFields({ name: '🎯 Difficulty', value: `\`${difficulty.toLocaleString()}\``, inline: true })
    }
    if (daily > 0) {
      embed.addFields({
        name: '💰 Estimated Earnings',
        value: `Daily: \`${daily < 0.0001 ? '< 0.0001' : daily.toFixed(6)}\` XMR\nWeekly: \`${weekly < 0.001 ? '< 0.001' : weekly.toFixed(6)}\` XMR\nMonthly: \`${monthly < 0.01 ? '< 0.01' : monthly.toFixed(6)}\` XMR`,
        inline: false,
      })
    }
  }

  if (wallet.length > 8) {
    embed.addFields({ name: '💳 Wallet', value: `\`${wallet}\``, inline: false })
  }

  embed.setFooter({ text: `${E.skull} NOVA-C2 ⚡ ${ts()}`, iconURL: ICONS.footer || undefined })
    .setTimestamp()

  return { embed, isRunning }
}
