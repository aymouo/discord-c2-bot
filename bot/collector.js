import { formatSize } from '../utils/index.js'

function getContent(m) {
  if (m.content) return m.content
  if (m.embeds?.length && m.embeds[0].description) {
    const desc = m.embeds[0].description
    const codeMatch = desc.match(/```([\s\S]*?)```/)
    return codeMatch ? codeMatch[1].trim() : desc
  }
  return ''
}

function isHeartbeat(text) {
  return text.includes(':heartbeat:') || text.includes('**Alive**') || text.includes('**Device Online**') || text.includes('**Reconnected**')
}

export async function collectChannelResponse(channel, cmdName, timeoutMs = 30000) {
  try {
    const before = Date.now()
    await new Promise(r => setTimeout(r, 2500))
    const parts = []
    const seen = new Set()
    let stableCount = 0
    while ((Date.now() - before) < timeoutMs) {
      const msgs = await channel.messages.fetch({ limit: 15 })
      let newCount = 0
      for (const [, m] of msgs) {
        if (!m.author.bot) continue
        const text = getContent(m)
        if (!text && !m.attachments.size) continue
        if (text && isHeartbeat(text)) continue
        if (m.createdTimestamp < before - 5000) continue
        if (text.startsWith('!') && m.createdTimestamp < before + 5000) continue
        const dedup = m.id
        if (seen.has(dedup)) continue
        seen.add(dedup)
        newCount++
        const attachments = m.attachments.map(a => `[FILE: ${a.name} (${formatSize(a.size)})]`).join(' ')
        parts.push((attachments ? text + ' ' + attachments : text).slice(0, 1900))
      }
      if (newCount === 0) {
        stableCount++
        if (stableCount >= 3) break
        await new Promise(r => setTimeout(r, 2000))
      } else {
        stableCount = 0
        await new Promise(r => setTimeout(r, 3000))
      }
    }
    const combined = parts.join('\n──────────────────\n').slice(0, 20000)
    return combined || null
  } catch { return null }
}
