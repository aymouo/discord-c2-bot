import { encrypt, decrypt } from '../lib/crypto.js'

const [,, mode, ...rest] = process.argv
const input = rest.join(' ')

if (!mode || !input) {
  console.log('Usage:')
  console.log('  node tools/crypt.js encrypt "<text>"')
  console.log('  node tools/crypt.js decrypt "<base64>"')
  console.log('  node tools/crypt.js c2 "<command>"')
  process.exit(1)
}

try {
  if (mode === 'encrypt') {
    console.log(encrypt(input))
  } else if (mode === 'decrypt') {
    console.log(decrypt(input))
  } else if (mode === 'c2') {
    const enc = encrypt(input)
    console.log(`!c2 ${enc}`)
  } else {
    console.error('Unknown mode. Use encrypt, decrypt, or c2.')
    process.exit(1)
  }
} catch (e) {
  console.error(`Error: ${e.message}`)
  process.exit(1)
}
