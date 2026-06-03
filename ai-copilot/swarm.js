const { AI_PROVIDER, GEMINI_API_KEY, GEMINI_MODEL, OLLAMA_BASE_URL, OLLAMA_MODEL, CLAUDE_API_KEY, CLAUDE_MODEL, NVIDIA_API_KEY, NVIDIA_MODEL } = process.env

const GEMINI_URL = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL || 'gemini-2.0-flash'}:generateContent`
const OLLAMA_URL = `${OLLAMA_BASE_URL || 'http://127.0.0.1:11434'}/api/chat`
const CLAUDE_URL = 'https://api.anthropic.com/v1/messages'
const NVIDIA_URL = 'https://integrate.api.nvidia.com/v1/chat/completions'
const OLLAMA_MODEL_NAME = OLLAMA_MODEL || 'qwen2.5:7b'
const CLAUDE_MODEL_NAME = CLAUDE_MODEL || 'claude-3-5-sonnet-20241022'
const NVIDIA_MODEL_NAME = NVIDIA_MODEL || 'meta/llama-3.1-8b-instruct'

const PROVIDERS = {
  nvidia: {
    available: !!NVIDIA_API_KEY,
    cost: 'free',
    tier: 'fast',
    maxTokens: 4096,
    capabilities: ['planning', 'analysis', 'reporting', 'classification'],
    failureThreshold: 0,
  },
  gemini: {
    available: !!GEMINI_API_KEY,
    cost: 'free',
    tier: 'fast',
    maxTokens: 8192,
    capabilities: ['planning', 'analysis', 'reporting', 'classification'],
    failureThreshold: 0,
  },
  ollama: {
    available: true,
    cost: 'free',
    tier: 'local',
    maxTokens: 4096,
    capabilities: ['sensitive_data', 'planning', 'analysis', 'reporting'],
    failureThreshold: 0,
  },
  claude: {
    available: !!CLAUDE_API_KEY,
    cost: 'paid',
    tier: 'premium',
    maxTokens: 8192,
    capabilities: ['complex_reasoning', 'code_generation', 'strategic_planning'],
    failureThreshold: 0,
  },
}

export function getAvailableProviders() {
  return Object.entries(PROVIDERS)
    .filter(([, p]) => p.available)
    .sort((a, b) => {
      const order = { fast: 0, local: 1, premium: 2 }
      return (order[a[1].tier] || 0) - (order[b[1].tier] || 0)
    })
    .map(([name]) => name)
}

export function getOptimalProvider(task, requiresLocal = false, complexity = 'low') {
  if (requiresLocal && PROVIDERS.ollama.available) return 'ollama'
  if (complexity === 'high' && PROVIDERS.claude.available) return 'claude'
  if (PROVIDERS.gemini.available) return 'gemini'
  if (PROVIDERS.ollama.available) return 'ollama'
  if (PROVIDERS.claude.available) return 'claude'
  return null
}

async function callProvider(provider, systemPrompt, messages, opts = {}) {
  const errors = []

  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      switch (provider) {
        case 'nvidia': return await callNvidia(systemPrompt, messages)
        case 'gemini': return await callGemini(systemPrompt, messages)
        case 'ollama': return await callOllama(systemPrompt, messages)
        case 'claude': return await callClaude(systemPrompt, messages)
      }
    } catch (e) {
      errors.push(`${provider}: ${e.message}`)
    }
  }
  throw new Error(`Provider ${provider} failed: ${errors.join('; ')}`)
}

export async function callAIWithFallback(systemPrompt, messages, opts = {}) {
  const { requiresLocal = false, complexity = 'low', providers = null } = opts
  const candidates = providers || getAvailableProviders()
  const preferred = providers
    ? providers
    : [getOptimalProvider('call', requiresLocal, complexity), ...candidates.filter(p => p !== getOptimalProvider('call', requiresLocal, complexity))]

  const errors = []
  for (const provider of preferred) {
    if (!PROVIDERS[provider]?.available) continue
    try {
      const result = await callProvider(provider, systemPrompt, messages, opts)
      return { provider, result }
    } catch (e) {
      errors.push(`${provider}: ${e.message}`)
      PROVIDERS[provider].failureThreshold++
      continue
    }
  }

  for (const [name, config] of Object.entries(PROVIDERS)) {
    if (preferred.includes(name) || !config.available) continue
    try {
      const result = await callProvider(name, systemPrompt, messages, opts)
      return { provider: name, result }
    } catch (e) {
      errors.push(`${name}: ${e.message}`)
    }
  }

  throw new Error(`All AI providers exhausted: ${errors.join('; ')}`)
}

async function callGemini(systemPrompt, messages) {
  const contents = messages.map(m => ({
    role: m.role === 'assistant' ? 'model' : 'user',
    parts: [{ text: m.content }],
  }))
  const resp = await fetch(`${GEMINI_URL}?key=${GEMINI_API_KEY}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      contents,
      systemInstruction: { parts: [{ text: systemPrompt }] },
      generationConfig: { temperature: 0.1, maxOutputTokens: 4096 },
    }),
    signal: AbortSignal.timeout(60000),
  })
  if (!resp.ok) throw new Error(`Gemini ${resp.status}: ${await resp.text().catch(() => '')}`)
  const data = await resp.json()
  return data.candidates?.[0]?.content?.parts?.[0]?.text || ''
}

async function callOllama(systemPrompt, messages) {
  const resp = await fetch(OLLAMA_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: OLLAMA_MODEL_NAME,
      messages: [{ role: 'system', content: systemPrompt }, ...messages],
      stream: false,
      options: { temperature: 0.1 },
    }),
    signal: AbortSignal.timeout(120000),
  })
  if (!resp.ok) throw new Error(`Ollama ${resp.status}: ${await resp.text().catch(() => '')}`)
  const data = await resp.json()
  return data.message?.content || ''
}

async function callClaude(systemPrompt, messages) {
  const resp = await fetch(CLAUDE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-api-key': CLAUDE_API_KEY, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify({ model: CLAUDE_MODEL_NAME, max_tokens: 4096, system: systemPrompt, messages }),
    signal: AbortSignal.timeout(60000),
  })
  if (!resp.ok) throw new Error(`Claude ${resp.status}: ${await resp.text().catch(() => '')}`)
  const data = await resp.json()
  return data.content?.[0]?.text || ''
}

async function callNvidia(systemPrompt, messages) {
  const allMessages = [{ role: 'system', content: systemPrompt }, ...messages]
  const resp = await fetch(NVIDIA_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${NVIDIA_API_KEY}` },
    body: JSON.stringify({ model: NVIDIA_MODEL_NAME, messages: allMessages, max_tokens: 4096, temperature: 0.1 }),
    signal: AbortSignal.timeout(60000),
  })
  if (!resp.ok) throw new Error(`NVIDIA ${resp.status}: ${await resp.text().catch(() => '')}`)
  const data = await resp.json()
  return data.choices?.[0]?.message?.content || ''
}

export function parseAIResponse(text) {
  const cleaned = text.replace(/^```(?:json)?\s*|\s*```$/g, '').trim()
  return JSON.parse(cleaned)
}

export function swarmStatus() {
  const status = {}
  for (const [name, config] of Object.entries(PROVIDERS)) {
    status[name] = {
      available: config.available,
      cost: config.cost,
      tier: config.tier,
      failures: config.failureThreshold,
    }
  }
  return status
}
