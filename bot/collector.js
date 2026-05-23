import { formatSize } from '../utils/index.js'

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
        if (!m.author.bot || !m.content) continue
        if (m.content.includes(':heartbeat:') || m.content.includes('**Alive**') || m.content.includes('**Device Online**') || m.content.includes('**Reconnected**')) continue
        if (m.createdTimestamp < before - 5000) continue
        if (m.content.startsWith('!') && m.createdTimestamp < before + 5000) continue
        const dedup = m.id
        if (seen.has(dedup)) continue
        seen.add(dedup)
        newCount++
        const attachments = m.attachments.map(a => `[FILE: ${a.name} (${formatSize(a.size)})]`).join(' ')
        parts.push((attachments ? m.content + ' ' + attachments : m.content).slice(0, 1500))
      }
      if (newCount === 0) {
        stableCount++
        if (stableCount >= 3) break // 3 cycles with no new messages = done
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
