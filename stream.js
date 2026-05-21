import express from 'express';
import { joinVoiceChannel, createAudioPlayer, VoiceConnectionStatus, getVoiceConnection, entersState } from '@discordjs/voice';
import { spawn } from 'child_process';
import { EventEmitter } from 'events';

class VideoStreamManager extends EventEmitter {
  constructor() {
    super();
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

    // Root health check for Koyeb
    this.server.get('/', (req, res) => {
      res.status(200).send('OK');
    });

    // Receive H264 frames from Android
    this.server.post('/api/stream/:deviceId/frame', (req, res) => {
      const deviceId = req.params.deviceId;
      const stream = this.streams.get(deviceId);

      if (stream && stream.active) {
        stream.frameCount++;
        stream.lastFrameAt = Date.now();

        if (stream.ffmpeg && stream.ffmpeg.stdin && !stream.ffmpeg.stdin.destroyed) {
          try {
            stream.ffmpeg.stdin.write(req.body);
          } catch (e) {
            console.error(`[Stream] FFmpeg write error for ${deviceId}:`, e.message);
          }
        }

        if (stream.frameCount % 30 === 0) {
          console.log(`[Stream] ${deviceId}: ${stream.frameCount} frames, buffer: ${stream.frameBuffer.length}`);
        }

        res.status(200).json({ status: 'ok', frames: stream.frameCount });
      } else {
        res.status(404).json({ status: 'no_active_stream', hint: 'Send /api/stream/:deviceId/start first' });
      }
    });

    // Start stream command
    this.server.post('/api/stream/:deviceId/start', (req, res) => {
      const deviceId = req.params.deviceId;
      const { voiceChannelId, guildId, fps = 30, width = 480, height = 360 } = req.body;

      if (!this.client || !this.ready) {
        return res.status(503).json({ status: 'bot_not_ready' });
      }

      if (!voiceChannelId || !guildId) {
        return res.status(400).json({ status: 'missing_params', required: ['voiceChannelId', 'guildId'] });
      }

      this.startStream(deviceId, voiceChannelId, guildId, { fps, width, height })
        .then(ok => {
          if (ok) {
            res.json({ status: 'started', deviceId, voiceChannelId });
          } else {
            res.status(500).json({ status: 'failed', reason: 'could_not_join_channel' });
          }
        })
        .catch(err => {
          console.error(`[Stream] Start error for ${deviceId}:`, err.message);
          res.status(500).json({ status: 'error', message: err.message });
        });
    });

    // Stop stream command
    this.server.post('/api/stream/:deviceId/stop', (req, res) => {
      const deviceId = req.params.deviceId;
      this.stopStream(deviceId);
      res.json({ status: 'stopped' });
    });

    // Health check
    this.server.get('/api/stream/health', (req, res) => {
      const streams = Array.from(this.streams.entries()).map(([id, s]) => ({
        deviceId: id,
        active: s.active,
        fps: s.config.fps,
        frames: s.frameCount,
        connected: s.connectionStatus,
        uptime: Date.now() - s.startTime
      }));

      res.json({
        ready: this.ready,
        active: streams.length,
        streams
      });
    });

    this.server.listen(this.port, () => {
      console.log(`[Stream] HTTP server listening on port ${this.port}`);
      this.ready = true;
    });
  }

  async startStream(deviceId, voiceChannelId, guildId, config = {}) {
    this.stopStream(deviceId);

    try {
      const guild = await this.client.guilds.fetch(guildId).catch(() => null);
      if (!guild) {
        console.error(`[Stream] Guild not found: ${guildId}`);
        return false;
      }

      const voiceChannel = await guild.channels.fetch(voiceChannelId).catch(() => null);
      if (!voiceChannel || !voiceChannel.isVoiceBased()) {
        console.error(`[Stream] Invalid voice channel: ${voiceChannelId}`);
        return false;
      }

      console.log(`[Stream] Joining voice channel: ${voiceChannel.name} (${voiceChannelId})`);

      const connection = joinVoiceChannel({
        channelId: voiceChannel.id,
        guildId: guild.id,
        adapterCreator: guild.voiceAdapterCreator,
        selfDeaf: true,
        selfMute: false
      });

      const stream = {
        active: true,
        connection,
        connectionStatus: 'connecting',
        voiceChannel,
        frameBuffer: [],
        frameCount: 0,
        lastFrameAt: 0,
        config: {
          fps: config.fps || 30,
          width: config.width || 480,
          height: config.height || 360,
          bitrate: 1000000
        },
        ffmpeg: null,
        startTime: Date.now()
      };

      this.streams.set(deviceId, stream);

      try {
        await entersState(connection, VoiceConnectionStatus.Ready, 20000);
        stream.connectionStatus = 'ready';
        console.log(`[Stream] Voice connection ready for ${deviceId}`);
      } catch (e) {
        console.error(`[Stream] Connection timeout for ${deviceId}:`, e.message);
        stream.connectionStatus = 'timeout';
        if (connection.state.status !== VoiceConnectionStatus.Destroyed) {
          connection.destroy();
        }
        this.streams.delete(deviceId);
        return false;
      }

      connection.on(VoiceConnectionStatus.Disconnected, () => {
        console.log(`[Stream] Disconnected for ${deviceId}, attempting reconnect...`);
        stream.connectionStatus = 'reconnecting';
        try {
          setTimeout(() => {
            connection.rejoin({ channelId: voiceChannel.id });
          }, 5000);
        } catch (e) {
          this.stopStream(deviceId);
        }
      });

      connection.on(VoiceConnectionStatus.Destroyed, () => {
        console.log(`[Stream] Connection destroyed for ${deviceId}`);
        stream.active = false;
        stream.connectionStatus = 'destroyed';
      });

      this.startFFmpegStream(stream, deviceId);
      return true;
    } catch (err) {
      console.error(`[Stream] Failed to start stream for ${deviceId}:`, err.message);
      return false;
    }
  }

  startFFmpegStream(stream, deviceId) {
    const { fps, width, height } = stream.config;

    const ffmpegArgs = [
      '-f', 'h264',
      '-framerate', String(fps),
      '-i', 'pipe:0',
      '-f', 'rawvideo',
      '-pix_fmt', 'yuv420p',
      '-s', `${width}x${height}`,
      '-r', String(fps),
      'pipe:1'
    ];

    console.log(`[Stream] Starting FFmpeg: ${ffmpegArgs.join(' ')}`);

    stream.ffmpeg = spawn('ffmpeg', ffmpegArgs, {
      stdio: ['pipe', 'pipe', 'pipe']
    });

    stream.ffmpeg.on('error', (err) => {
      console.error(`[Stream] FFmpeg process error for ${deviceId}:`, err.message);
    });

    stream.ffmpeg.on('close', (code) => {
      console.log(`[Stream] FFmpeg exited for ${deviceId} with code ${code}`);
      if (stream.active) {
        console.log(`[Stream] Restarting FFmpeg for ${deviceId}`);
        this.startFFmpegStream(stream, deviceId);
      }
    });

    stream.ffmpeg.stderr.on('data', (data) => {
      const msg = data.toString();
      if (msg.includes('Error') || msg.includes('error') || msg.includes('Invalid')) {
        console.error(`[Stream] FFmpeg stderr: ${msg.trim()}`);
      }
    });

    console.log(`[Stream] FFmpeg ready for ${deviceId} at ${fps}fps ${width}x${height}`);
  }

  stopStream(deviceId) {
    const stream = this.streams.get(deviceId);
    if (!stream) return;

    console.log(`[Stream] Stopping stream for ${deviceId}`);
    stream.active = false;

    if (stream.ffmpeg) {
      try {
        stream.ffmpeg.stdin?.end();
        stream.ffmpeg.kill('SIGTERM');
      } catch (e) {}
      stream.ffmpeg = null;
    }

    if (stream.connection) {
      try {
        stream.connection.destroy();
      } catch (e) {}
    }

    stream.frameBuffer = [];
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
        connection: s.connectionStatus,
        uptime: Math.floor((Date.now() - s.startTime) / 1000)
      });
    }

    return {
      total: streams.length,
      streams
    };
  }
}

const videoStream = new VideoStreamManager();

export { videoStream, VideoStreamManager };
