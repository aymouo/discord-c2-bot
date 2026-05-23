import crypto from 'crypto'

class AIContext {
  constructor() {
    this.sessions = new Map()
  }

  _key(guildId, userId) {
    return `${guildId}:${userId}`
  }

  getSession(guildId, userId) {
    const k = this._key(guildId, userId)
    if (!this.sessions.has(k)) {
      this.sessions.set(k, {
        guildId,
        userId,
        history: [],
        deviceKnowledge: {},
        pendingProposal: null,
        currentTarget: null,
        createdAt: Date.now(),
      })
    }
    return this.sessions.get(k)
  }

  addToHistory(session, role, content) {
    session.history.push({ role, content, ts: Date.now() })
    if (session.history.length > 100) session.history.splice(0, session.history.length - 100)
  }

  updateDeviceKnowledge(session, deviceId, key, value) {
    if (!session.deviceKnowledge[deviceId]) session.deviceKnowledge[deviceId] = {}
    session.deviceKnowledge[deviceId][key] = value
  }

  summarizeDeviceKnowledge(session) {
    const entries = Object.entries(session.deviceKnowledge)
    if (!entries.length) return 'No device knowledge yet.'
    return entries.map(([did, data]) => {
      const props = Object.entries(data).map(([k, v]) => `${k}=${v}`).join(', ')
      return `[${did}] ${props}`
    }).join('\n')
  }

  setPendingProposal(session, proposal) {
    session.pendingProposal = proposal
  }

  clearPendingProposal(session) {
    session.pendingProposal = null
  }
}

export const aiContext = new AIContext()
