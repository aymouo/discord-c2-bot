import express from 'express';
import { AttachmentBuilder } from 'discord.js';
import { joinVoiceChannel, entersState, VoiceConnectionStatus, getVoiceConnection } from '@discordjs/voice';

class VideoStreamManager {
  constructor() {
    this.client = null;
    this.streams = new Map();
    this.server = null;
    this.port = parseInt(process.env.PORT) || 8000;
    this.ready = false;
  }

  startServer() {
    this.server = express();
    this.server.use(express.json({ limit: '1mb' }));
    this.server.use(express.raw({ type: 'application/octet-stream', limit: '10mb' }));
    this.server.use(express.urlencoded({ extended: true }));

    this.server.get('/', (req, res) => {
      res.status(200).send('OK');
    });

    // Koyeb health check endpoint — returns bot status
    this.server.get('/health', (req, res) => {
      const mem = process.memoryUsage()
      const shard = this.client?.ws?.shards?.first()
      res.json({
        status: 'ok',
        uptime: process.uptime(),
        bot: this.ready,
        shard: shard ? { id: shard.id, status: shard.status, ping: this.client.ws.ping } : null,
        memory: { rss: Math.round(mem.rss / 1024 / 1024) + 'MB', heap: Math.round(mem.heapUsed / 1024 / 1024) + 'MB' },
        streams: this.streams.size
      })
    })

    this.server.post('/api/stream/:deviceId/frame', (req, res) => {
      const deviceId = req.params.deviceId;
      const stream = this.streams.get(deviceId);

      if (stream && stream.active) {
        stream.frameCount++;
        stream.lastFrameAt = Date.now();

        if (stream.channel && req.body && req.body.length > 0) {
          this.sendFrameToChannel(stream, req.body);
        }

        res.status(200).json({ status: 'ok', frames: stream.frameCount });
      } else {
        res.status(404).json({ status: 'no_active_stream' });
      }
    });

    this.server.post('/api/stream/:deviceId/start', async (req, res) => {
      const deviceId = req.params.deviceId;
      const { voiceChannelId, guildId, textChannelId, fps = 5, width = 640, height = 480 } = req.body;

      console.log(`[Stream] Start request: deviceId=${deviceId} textCh=${textChannelId} voiceCh=${voiceChannelId} guild=${guildId}`);

      if (!this.client || !this.ready) {
        console.error(`[Stream] Bot not ready: client=${!!this.client} ready=${this.ready}`);
        return res.status(503).json({ status: 'bot_not_ready' });
      }

      if (!textChannelId) {
        return res.status(400).json({ status: 'missing_params', required: ['textChannelId'] });
      }

      try {
        const started = await this.startStream(deviceId, {
          voiceChannelId, guildId, textChannelId, fps, width, height
        });
        if (started) {
          res.json({ status: 'started', deviceId, textChannelId });
        } else {
          res.status(500).json({ status: 'failed' });
        }
      } catch (err) {
        console.error(`[Stream] Start error:`, err.message);
        res.status(500).json({ status: 'error', message: err.message });
      }
    });

    this.server.post('/api/stream/:deviceId/stop', (req, res) => {
      const deviceId = req.params.deviceId;
      this.stopStream(deviceId);
      res.json({ status: 'stopped' });
    });

    this.server.get('/api/stream/health', (req, res) => {
      const streams = Array.from(this.streams.entries()).map(([id, s]) => ({
        deviceId: id,
        active: s.active,
        fps: s.config.fps,
        frames: s.frameCount,
        uptime: Date.now() - s.startTime
      }));

      res.json({ ready: this.ready, active: streams.length, streams });
    });

    this.server.listen(this.port, () => {
      console.log(`[Stream] HTTP server listening on port ${this.port}`);
      this.ready = true;
    });
  }

  async startStream(deviceId, config) {
    this.stopStream(deviceId);

    try {
      console.log(`[Stream] Starting stream for ${deviceId}, textChannel=${config.textChannelId}, voiceChannel=${config.voiceChannelId}`);

      const textChannel = await this.client.channels.fetch(config.textChannelId).catch(e => {
        console.error(`[Stream] Text channel fetch failed: ${e.message}`);
        return null;
      });

      if (!textChannel) {
        console.error(`[Stream] Text channel not found: ${config.textChannelId}`);
        return false;
      }

      console.log(`[Stream] Text channel found: ${textChannel.name}`);

      let voiceConnection = null;

      if (config.voiceChannelId && config.guildId) {
        try {
          console.log(`[Stream] Fetching guild: ${config.guildId}`);
          const guild = await this.client.guilds.fetch(config.guildId).catch(e => {
            console.error(`[Stream] Guild fetch failed: ${e.message}`);
            return null;
          });

          if (!guild) {
            console.error(`[Stream] Guild not found: ${config.guildId}`);
          } else {
            console.log(`[Stream] Guild found: ${guild.name}`);

            console.log(`[Stream] Fetching voice channel: ${config.voiceChannelId}`);
            const voiceChannel = await guild.channels.fetch(config.voiceChannelId).catch(e => {
              console.error(`[Stream] Voice channel fetch failed: ${e.message}`);
              return null;
            });

            if (!voiceChannel) {
              console.error(`[Stream] Voice channel not found: ${config.voiceChannelId}`);
            } else if (!voiceChannel.isVoiceBased()) {
              console.error(`[Stream] Channel is not voice-based: type=${voiceChannel.type}`);
            } else {
              console.log(`[Stream] Voice channel found: ${voiceChannel.name}`);

              const existingConnection = getVoiceConnection(config.guildId);
              if (existingConnection) {
                console.log(`[Stream] Destroying existing connection`);
                existingConnection.destroy();
              }

              console.log(`[Stream] Calling joinVoiceChannel...`);
              voiceConnection = joinVoiceChannel({
                channelId: voiceChannel.id,
                guildId: guild.id,
                adapterCreator: guild.voiceAdapterCreator,
                selfDeaf: true,
                selfMute: true,
                debug: true
              });

              console.log(`[Stream] Waiting for Ready state (30s timeout)...`);
              await entersState(voiceConnection, VoiceConnectionStatus.Ready, 30000);
              console.log(`[Stream] Voice connection READY!`);

              voiceConnection.on('stateChange', (oldState, newState) => {
                console.log(`[Stream] Voice state: ${oldState.status} -> ${newState.status}`);
              });

              voiceConnection.on('debug', (msg) => {
                console.log(`[Stream] Voice debug: ${msg}`);
              });
            }
          }
        } catch (e) {
          console.error(`[Stream] Voice join failed: ${e.message}`);
          if (voiceConnection) {
            try { voiceConnection.destroy(); } catch (_) {}
            voiceConnection = null;
          }
        }
      }

      const stream = {
        active: true,
        connection: voiceConnection,
        channel: textChannel,
        frameCount: 0,
        lastFrameAt: 0,
        lastMsgId: null,
        config: {
          fps: Math.min(config.fps || 5, 10),
          width: config.width || 640,
          height: config.height || 480
        },
        startTime: Date.now(),
        sendInterval: null
      };

      stream.sendInterval = setInterval(() => {
        if (!stream.active) return;
        const timeSinceLastFrame = Date.now() - stream.lastFrameAt;
        if (timeSinceLastFrame > 60000 && stream.frameCount === 0) {
          console.log(`[Stream] ${deviceId}: waiting for device to start sending frames...`);
        }
      }, 30000);

      this.streams.set(deviceId, stream);
      console.log(`[Stream] Stream ready for ${deviceId} at ${stream.config.fps}fps, voice=${!!voiceConnection}`);
      return true;
    } catch (err) {
      console.error(`[Stream] Failed to start:`, err.message);
      return false;
    }
  }

  async sendFrameToChannel(stream, frameData) {
    const now = Date.now();
    const frameInterval = 1000 / stream.config.fps;
    if (now - stream.lastFrameAt < frameInterval) return;

    stream.lastFrameAt = now;

    try {
      const attachment = new AttachmentBuilder(frameData, { name: 'frame.jpg' });

      if (stream.lastMsgId) {
        try {
          const msg = await stream.channel.messages.fetch(stream.lastMsgId).catch(() => null);
          if (msg) {
            await msg.edit({ attachments: [attachment] }).catch(() => {});
            return;
          }
        } catch (_) {}
      }

      const newMsg = await stream.channel.send({ attachments: [attachment] }).catch(() => null);
      if (newMsg) {
        stream.lastMsgId = newMsg.id;
        if (stream.frameCount > 10) {
          const oldMsgId = stream.lastMsgId;
          setTimeout(async () => {
            try {
              const oldMsg = await stream.channel.messages.fetch(oldMsgId).catch(() => null);
              if (oldMsg) await oldMsg.delete().catch(() => {});
            } catch (_) {}
          }, 3000);
        }
      }
    } catch (e) {
      console.error(`[Stream] Send frame error:`, e.message);
    }
  }

  stopStream(deviceId) {
    const stream = this.streams.get(deviceId);
    if (!stream) return;

    console.log(`[Stream] Stopping stream for ${deviceId}`);
    stream.active = false;

    if (stream.sendInterval) {
      clearInterval(stream.sendInterval);
      stream.sendInterval = null;
    }

    if (stream.connection) {
      try { stream.connection.destroy(); } catch (_) {}
      stream.connection = null;
    }

    this.streams.delete(deviceId);
  }

  getStreamStatus() {
    if (this.streams.size === 0) return null;

    const streams = [];
    for (const [id, s] of this.streams) {
      streams.push({
        deviceId: id,
        active: s.active,
        fps: s.config.fps,
        resolution: `${s.config.width}x${s.config.height}`,
        frames: s.frameCount,
        connection: s.connection ? 'voice+text' : 'text-only',
        uptime: Math.floor((Date.now() - s.startTime) / 1000)
      });
    }

    return { total: streams.length, streams };
  }
}

const videoStream = new VideoStreamManager();

export { videoStream, VideoStreamManager };
