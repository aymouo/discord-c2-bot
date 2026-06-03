import { ChannelType } from 'discord.js'

export function getPhantomChannels(guild) {
  return guild.channels.cache.filter(c => c.type === ChannelType.GuildText && c.name.startsWith('device-'))
}

export function findPhantomChannel(guild, name) {
  const prefix = name.startsWith('device-') ? name : 'device-' + name
  return guild.channels.cache.find(c => c.type === ChannelType.GuildText && c.name === prefix)
}

export function resolveTarget(guild, targetsMap, uid) {
  const data = targetsMap.get(uid)
  if (!data) return { channel: null, err: 'no_target' }
  const chId = typeof data === 'object' ? data.chId : data
  const channel = guild.channels.cache.get(chId)
  if (!channel) {
    targetsMap.delete(uid)
    return { channel: null, err: 'gone' }
  }
  return { channel, name: channel.name, err: null }
}

export async function requireTarget(guild, targetsMap, uid, options = {}) {
  const { allowAutoSelect = true } = options
  const existing = resolveTarget(guild, targetsMap, uid)
  if (existing.channel) return existing

  await guild.channels.fetch()
  const channels = getPhantomChannels(guild)
  if (!channels.size) return { channel: null, err: 'no_devices' }
  if (channels.size === 1 && allowAutoSelect) {
    const ch = channels.first()
    targetsMap.set(uid, { chId: ch.id, ts: Date.now() })
    return { channel: ch, name: ch.name, err: null }
  }
  if (channels.size > 1 && allowAutoSelect) {
    return { channel: null, err: 'multi_device', channels }
  }
  return { channel: null, err: 'no_target' }
}
