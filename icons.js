import { readFileSync } from 'fs'

const ICONS = {}
const RAW = readFileSync(new URL('icons_gif.txt', import.meta.url), 'utf8')
for (const line of RAW.split('\n')) {
  const trimmed = line.trim()
  if (!trimmed || !trimmed.includes('=')) continue
  const [key, ...rest] = trimmed.split('=')
  ICONS[key.trim()] = rest.join('=').trim()
}

export { ICONS }
