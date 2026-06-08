import fs from 'fs'
import path from 'path'

const STATE_FILE = path.resolve('bot_state.json')
const AUTOSAVE_INTERVAL = 60000

export function createStateStore(initial = {}) {
  const store = { ...initial }

  // Load from disk on init
  try {
    if (fs.existsSync(STATE_FILE)) {
      const data = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'))
      Object.assign(store, data)
      console.log(`[State] Loaded from ${STATE_FILE}`)
    }
  } catch (e) {
    console.warn(`[State] Could not load: ${e.message}`)
  }

  function save() {
    try {
      const toSave = {}
      for (const [key, val] of Object.entries(store)) {
        if (val instanceof Map) {
          toSave[key] = Array.from(val.entries())
        } else {
          toSave[key] = val
        }
      }
      fs.writeFileSync(STATE_FILE, JSON.stringify(toSave, null, 2), 'utf8')
    } catch (e) {
      console.error(`[State] Save failed: ${e.message}`)
    }
  }

  // Autosave
  setInterval(save, AUTOSAVE_INTERVAL)

  // Save on exit — index.js handles shutdown, just save state here
  const onExit = () => { save() }
  process.on('SIGINT', onExit)
  process.on('SIGTERM', onExit)
  process.on('exit', onExit)

  return store
}

export function reviveMaps(data, mapKeys = []) {
  for (const key of mapKeys) {
    if (Array.isArray(data[key])) {
      data[key] = new Map(data[key])
    }
  }
  return data
}
