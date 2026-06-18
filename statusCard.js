import { createCanvas } from '@napi-rs/canvas'

const THEMES = {
  blood: {
    bg1: '#1A0000', bg2: '#0A0000', accent: '#DC143C',
    text: '#FFFFFF', textSec: '#E5E7EB', textMuted: '#9CA3AF',
    cardBg: 'rgba(0, 0, 0, 0.35)', glow: '#DC143C'
  },
  nova: {
    bg1: '#0D0000', bg2: '#050000', accent: '#FF1A1A',
    text: '#FFFFFF', textSec: '#FFCCCC', textMuted: '#FF6666',
    cardBg: 'rgba(80, 0, 0, 0.3)', glow: '#FF1A1A'
  },
  dark: {
    bg1: '#0A0A0A', bg2: '#050505', accent: '#FF003C',
    text: '#FFFFFF', textSec: '#D1D5DB', textMuted: '#6B7280',
    cardBg: 'rgba(0, 0, 0, 0.4)', glow: '#8A0303'
  },
  neon: {
    bg1: '#0A0A1A', bg2: '#050510', accent: '#00F0FF',
    text: '#FFFFFF', textSec: '#E0E0E0', textMuted: '#B0B0B0',
    cardBg: 'rgba(0, 0, 0, 0.6)', glow: '#00F0FF'
  },
  shinsenkyo: {
    bg1: '#0D0D0D', bg2: '#1A0A0A', accent: '#8B0000',
    text: '#F5F0E8', textSec: '#E8B4B8', textMuted: '#8A7A6A',
    cardBg: 'rgba(0, 0, 0, 0.45)', glow: '#2FFFD4'
  }
}

const NOISE_CACHE = new Map()

function getNoiseBuffer(key) {
  if (NOISE_CACHE.has(key)) return NOISE_CACHE.get(key)
  const [w, h] = key.split('x').map(Number)
  const buf = new Uint8ClampedArray(w * h * 4)
  for (let i = 0; i < buf.length; i += 4) {
    const n = Math.random() * 10
    buf[i] = buf[i + 1] = buf[i + 2] = n
    buf[i + 3] = 5
  }
  NOISE_CACHE.set(key, buf)
  if (NOISE_CACHE.size > 5) { const first = NOISE_CACHE.keys().next().value; NOISE_CACHE.delete(first) }
  return buf
}

function drawBg(ctx, w, h, theme) {
  const grad = ctx.createLinearGradient(0, 0, w, h)
  grad.addColorStop(0, theme.bg1)
  grad.addColorStop(0.5, theme.bg2)
  grad.addColorStop(1, theme.bg1)
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, w, h)

  const rad = ctx.createRadialGradient(w / 2, h / 2, 0, w / 2, h / 2, Math.max(w, h) / 2)
  rad.addColorStop(0, 'rgba(255,255,255,0.04)')
  rad.addColorStop(1, 'rgba(0,0,0,0.3)')
  ctx.fillStyle = rad
  ctx.fillRect(0, 0, w, h)

  const noiseKey = `${w}x${h}`
  const noiseData = getNoiseBuffer(noiseKey)
  const img = ctx.createImageData(noiseData, w, h)
  ctx.save()
  ctx.globalCompositeOperation = 'soft-light'
  ctx.putImageData(img, 0, 0)
  ctx.restore()
}

function drawCard(ctx, w, h, theme) {
  const m = 15, r = 16
  ctx.save()
  ctx.beginPath()
  ctx.roundRect(m, m, w - m * 2, h - m * 2, r)
  const cg = ctx.createLinearGradient(0, m, 0, h - m)
  cg.addColorStop(0, 'rgba(255,255,255,0.08)')
  cg.addColorStop(1, 'rgba(255,255,255,0.02)')
  ctx.fillStyle = cg
  ctx.fill()
  ctx.strokeStyle = theme.accent + '40'
  ctx.lineWidth = 1.5
  ctx.stroke()
  ctx.restore()
}

function drawVisualizer(ctx, w, h, theme, online) {
  const bars = 24, bw = 6, sp = 3, maxH = 50, minH = 10
  const sx = 25, by = h - 25
  ctx.save()
  for (let i = 0; i < bars; i++) {
    const wave = Math.sin((i / bars) * Math.PI * 4) * 0.5 + Math.sin((i / bars) * Math.PI * 2.5) * 0.3
    const h2 = minH + (maxH - minH) * ((wave + 1) / 2) * (online ? 1 : 0.3)
    const x = sx + i * (bw + sp)
    const bg = ctx.createLinearGradient(0, by - h2, 0, by)
    bg.addColorStop(0, theme.accent)
    bg.addColorStop(1, theme.glow + '40')
    ctx.fillStyle = bg
    ctx.beginPath()
    ctx.roundRect(x, by - h2, bw, h2, bw / 2)
    ctx.fill()
    if (online) {
      ctx.save()
      ctx.shadowColor = theme.accent
      ctx.shadowBlur = 8
      ctx.globalCompositeOperation = 'screen'
      ctx.fill()
      ctx.restore()
    }
  }
  ctx.restore()
}

function drawGlowLine(ctx, w, h, theme) {
  ctx.save()
  ctx.shadowColor = theme.accent
  ctx.shadowBlur = 12
  ctx.strokeStyle = theme.accent
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(30, 65)
  ctx.lineTo(w - 30, 65)
  ctx.stroke()
  ctx.restore()
}

function truncate(ctx, text, max) {
  if (ctx.measureText(text).width <= max) return text
  const avgW = ctx.measureText('abcdefghijklmnopqrstuvwxyz').width / 26
  let lo = 0, hi = Math.min(text.length, Math.ceil(max / avgW))
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (ctx.measureText(text.slice(0, mid) + '...').width <= max) lo = mid
    else hi = mid - 1
  }
  return text.slice(0, lo) + '...'
}

function drawNovaLogo(ctx, x, y, size, theme) {
  ctx.save()
  const orig = ctx.textAlign
  ctx.textAlign = 'right'
  ctx.font = `bold ${size}px Arial`
  ctx.fillStyle = theme.accent
  ctx.shadowColor = theme.glow
  ctx.shadowBlur = 15
  ctx.fillText('SHINSE', x, y)
  ctx.shadowBlur = 8
  ctx.fillStyle = theme.text
  ctx.fillText('NKYO', x + ctx.measureText('SHINSE').width, y)
  ctx.restore()
}

export async function statusCard(opts = {}) {
  const {
    deviceName = 'Unknown', status = 'offline',
    model = '?', android = '?', ip = '?',
    lastSeen = 'never', theme = 'blood',
    wormActive = false, brainActive = false, injectCount = 0
  } = opts
  const w = 700, h = 200
  const canvas = createCanvas(w, h)
  const ctx = canvas.getContext('2d')
  const t = THEMES[theme] || THEMES.blood
  const isOnline = status === 'online'

  drawBg(ctx, w, h, t)
  drawCard(ctx, w, h, t)
  drawVisualizer(ctx, w, h, t, isOnline)
  drawGlowLine(ctx, w, h, t)

  // Status badge
  ctx.save()
  ctx.fillStyle = isOnline ? '#10B981' : '#EF4444'
  ctx.shadowColor = isOnline ? '#10B981' : '#EF4444'
  ctx.shadowBlur = 10
  ctx.beginPath()
  ctx.roundRect(30, 30, 90, 28, 14)
  ctx.fill()
  ctx.fillStyle = '#FFF'
  ctx.font = 'bold 14px Arial'
  ctx.textAlign = 'center'
  ctx.shadowBlur = 0
  ctx.fillText(isOnline ? '● ALIVE' : '● DEAD', 75, 49)
  ctx.restore()

  // NOVA-C2 logo (top right)
  drawNovaLogo(ctx, w - 30, 49, 16, t)

  // Device name
  ctx.save()
  ctx.fillStyle = t.text
  ctx.font = 'bold 26px Arial'
  ctx.textAlign = 'left'
  ctx.textBaseline = 'top'
  ctx.shadowColor = 'rgba(0,0,0,0.5)'
  ctx.shadowBlur = 4
  ctx.fillText(truncate(ctx, deviceName, 350), 30, 72)
  ctx.restore()

  // Model
  ctx.save()
  ctx.fillStyle = t.textSec
  ctx.font = '16px Arial'
  ctx.shadowBlur = 2
  ctx.fillText(`Model: ${model}`, 30, 106)
  ctx.restore()

  // Info line
  ctx.save()
  ctx.fillStyle = t.textMuted
  ctx.font = '13px Arial'
  ctx.fillText(`IP: ${ip}   ·   Android: ${android}   ·   Last: ${lastSeen}`, 30, 132)
  ctx.restore()

  // Module indicators
  ctx.save()
  ctx.font = '12px Arial'
  ctx.textAlign = 'right'
  const modY = 106
  if (wormActive) {
    ctx.fillStyle = '#00ff41'
    ctx.fillText('[WORM]', w - 200, modY)
  }
  if (brainActive) {
    ctx.fillStyle = '#00ccff'
    ctx.fillText('[BRAIN]', w - 140, modY)
  }
  if (injectCount > 0) {
    ctx.fillStyle = '#ff4444'
    ctx.fillText(`[INJECT x${injectCount}]`, w - 80, modY)
  }
  ctx.restore()

  // Bottom version
  ctx.save()
  ctx.fillStyle = t.accent + '15'
  ctx.font = 'bold 12px Arial'
  ctx.textAlign = 'right'
  ctx.fillText('SHINSENKYO v3.1', w - 30, h - 15)
  ctx.restore()

  return canvas.toBuffer('image/png')
}
