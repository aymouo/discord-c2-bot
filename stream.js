import express from 'express';

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

      if (!this.client || !this.ready) {
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
      console.log(`[Stream] Starting text stream for ${deviceId}`);

      const textChannel = await this.client.channels.fetch(config.textChannelId).catch(e => {
        console.error(`[Stream] Text channel fetch failed: ${e.message}`);
        return null;
      });

      if (!textChannel) {
        console.error(`[Stream] Text channel not found: ${config.textChannelId}`);
        return false;
      }

      let voiceConnection = null;
      if (config.voiceChannelId && config.guildId) {
        try {
          const guild = await this.client.guilds.fetch(config.guildId).catch(() => null);
          if (guild) {
            const voiceChannel = await guild.channels.fetch(config.voiceChannelId).catch(() => null);
            if (voiceChannel && voiceChannel.isVoiceBased()) {
              const { joinVoiceChannel, entersState, VoiceConnectionStatus } = await import('@discordjs/voice');
              voiceConnection = joinVoiceChannel({
                channelId: voiceChannel.id,
                guildId: guild.id,
                adapterCreator: guild.voiceAdapterCreator,
                selfDeaf: true,
                selfMute: true
              });
              await entersState(voiceConnection, VoiceConnectionStatus.Ready, 15000).catch(() => {
                console.log(`[Stream] Voice join timeout, continuing without voice`);
                voiceConnection = null;
              });
            }
          }
        } catch (e) {
          console.log(`[Stream] Voice join skipped: ${e.message}`);
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
        if (timeSinceLastFrame > 10000) {
          console.log(`[Stream] ${deviceId}: no frames for 10s, stopping`);
          this.stopStream(deviceId);
        }
      }, 10000);

      this.streams.set(deviceId, stream);
      console.log(`[Stream] Text stream ready for ${deviceId} at ${stream.config.fps}fps`);
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
      const { AttachmentBuilder } = await import('discord.js');
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
        if (stream.frameCount > 5) {
          const oldMsgId = stream.lastMsgId;
          setTimeout(async () => {
            try {
              const oldMsg = await stream.channel.messages.fetch(oldMsgId).catch(() => null);
              if (oldMsg) await oldMsg.delete().catch(() => {});
            } catch (_) {}
          }, 2000);
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
