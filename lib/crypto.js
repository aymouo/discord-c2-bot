import crypto from 'crypto'

const KEY_PASS = process.env.CRYPTO_KEY || 'PhantomC2!DefaultChangeMe_2026'
const SALT = 'PhantomC2Salt_v1'
const IV_BYTES = 12
const TAG_BITS = 128
const KEY_BITS = 256
const PBKDF2_ITER = 120000

function deriveKey() {
  return crypto.pbkdf2Sync(KEY_PASS, SALT, PBKDF2_ITER, KEY_BITS / 8, 'sha256')
}

export function encrypt(plaintext) {
  const key = deriveKey()
  const iv = crypto.randomBytes(IV_BYTES)
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv)
  const enc = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()])
  const tag = cipher.getAuthTag()
  return Buffer.concat([iv, enc, tag]).toString('base64')
}

export function decrypt(encoded) {
  const raw = Buffer.from(encoded, 'base64')
  const iv = raw.subarray(0, IV_BYTES)
  const tag = raw.subarray(raw.length - 16)
  const ct = raw.subarray(IV_BYTES, raw.length - 16)
  const key = deriveKey()
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv)
  decipher.setAuthTag(tag)
  return decipher.update(ct) + decipher.final('utf8')
}
