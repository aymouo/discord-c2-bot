import express from 'express';
import { joinVoiceChannel, createAudioPlayer, VoiceConnectionStatus, getVoiceConnection } from '@discordjs/voice';
import { Client } from 'discord.js';
import { spawn } from 'child_process';
import { EventEmitter } from 'events';

class VideoStreamManager extends EventEmitter {
  constructor(client) {
    super();
    this.client = client;
    this.streams = new Map(); // deviceId -> stream info
    this.server = null;
    this.port = 3000;
  }

  startServer() {
    this.server = express();
    this.server.use(express.raw({ type: 'application/octet-stream', limit: '10mb' }));

    // Receive H264 frames from Android
    this.server.post('/api/stream/:deviceId/frame', (req, res) => {
      const deviceId = req.params.deviceId;
      const stream = this.streams.get(deviceId);
      
      if (stream && stream.active) {
        // Buffer the frame
        stream.frameBuffer.push({
          data: req.body,
          timestamp: Date.now()
        });
        
        // Keep buffer size manageable (max 60 frames)
        if (stream.frameBuffer.length > 60) {
          stream.frameBuffer.shift();
        }
        
        res.status(200).json({ status: 'ok', buffer: stream.frameBuffer.length });
      } else {
        res.status(404).json({ status: 'no_active_stream' });
      }
    });

    // Start stream command
    this.server.post('/api/stream/:deviceId/start', (req, res) => {
      const deviceId = req.params.deviceId;
      const { voiceChannelId, guildId, fps = 30, width = 480, height = 360 } = req.body;
      
      this.startStream(deviceId, voiceChannelId, guildId, { fps, width, height });
      res.json({ status: 'starting' });
    });

    // Stop stream command
    this.server.post('/api/stream/:deviceId/stop', (req, res) => {
      const deviceId = req.params.deviceId;
      this.stopStream(deviceId);
      res.json({ status: 'stopped' });
    });

    // Health check
    this.server.get('/api/stream/health', (req, res) => {
      res.json({
        active: this.streams.size,
        streams: Array.from(this.streams.entries()).map(([id, s]) => ({
          deviceId: id,
          active: s.active,
          fps: s.config.fps,
          buffer: s.frameBuffer.length
        }))
      });
    });

    // Root health check for Koyeb
    this.server.get('/', (req, res) => {
      res.status(200).send('OK');
    });

    this.port = parseInt(process.env.PORT) || 8000;
    this.server.listen(this.port, () => {
      console.log(`[Stream] HTTP server listening on port ${this.port}`);
    });
  }

  async startStream(deviceId, voiceChannelId, guildId, config = {}) {
    // Stop existing stream if any
    this.stopStream(deviceId);

    const guild = await this.client.guilds.fetch(guildId);
    const voiceChannel = await guild.channels.fetch(voiceChannelId);

    if (!voiceChannel || !voiceChannel.isVoiceBased()) {
      console.error(`[Stream] Invalid voice channel: ${voiceChannelId}`);
      return false;
    }

    console.log(`[Stream] Joining voice channel: ${voiceChannel.name}`);

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
      voiceChannel,
      frameBuffer: [],
      config: {
        fps: config.fps || 30,
        width: config.width || 480,
        height: config.height || 360,
        bitrate: 1000000 // 1 Mbps
      },
      ffmpeg: null,
      startTime: Date.now()
    };

    this.streams.set(deviceId, stream);

    // Wait for connection to be ready
    connection.on(VoiceConnectionStatus.Ready, () => {
      console.log(`[Stream] Voice connection ready for ${deviceId}`);
      this.startFFmpegStream(stream);
    });

    connection.on(VoiceConnectionStatus.Disconnected, () => {
      console.log(`[Stream] Disconnected for ${deviceId}`);
      stream.active = false;
    });

    return true;
  }

  startFFmpegStream(stream) {
    const { fps, width, height, bitrate } = stream.config;
    const frameInterval = 1000 / fps;

    // Spawn ffmpeg to convert H264 to VP8
    const ffmpegArgs = [
      '-f', 'h264',
      '-framerate', String(fps),
      '-i', 'pipe:0',
      '-f', 'rawvideo',
      '-pix_fmt', 'yuv420p',
      '-s', `${width}x${height}`,
      '-r', String(fps),
      '-vf', `scale=${width}:${height}`,
      'pipe:1'
    ];

    stream.ffmpeg = spawn('ffmpeg', ffmpegArgs, {
      stdio: ['pipe', 'pipe', 'pipe']
    });

    let ffmpegInput = stream.ffmpeg.stdin;
    let ffmpegOutput = stream.ffmpeg.stdout;

    // Feed frames from buffer to ffmpeg
    const feedInterval = setInterval(() => {
      if (!stream.active || stream.frameBuffer.length === 0) return;

      // Get next frame
      const frame = stream.frameBuffer.shift();
      if (frame && frame.data) {
        ffmpegInput.write(frame.data);
      }
    }, frameInterval);

    stream.feedInterval = feedInterval;

    // Read VP8 output from ffmpeg and send to Discord
    // Note: Discord.js voice doesn't support video natively yet
    // We'll need to use a workaround or wait for video support
    
    // For now, we'll log that we're receiving frames
    console.log(`[Stream] FFmpeg started for ${stream.config.fps}fps ${width}x${height}`);
    
    // Handle ffmpeg errors
    stream.ffmpeg.stderr.on('data', (data) => {
      const msg = data.toString();
      if (msg.includes('Error') || msg.includes('error')) {
        console.error(`[Stream] FFmpeg error: ${msg}`);
      }
    });
  }

  stopStream(deviceId) {
    const stream = this.streams.get(deviceId);
    if (!stream) return;

    stream.active = false;
    
    if (stream.feedInterval) {
      clearInterval(stream.feedInterval);
    }
    
    if (stream.ffmpeg) {
      stream.ffmpeg.kill();
      stream.ffmpeg = null;
    }
    
    if (stream.connection) {
      stream.connection.destroy();
    }
    
    stream.frameBuffer = [];
    this.streams.delete(deviceId);
    
    console.log(`[Stream] Stopped stream for ${deviceId}`);
  }

  getStreamStatus(deviceId) {
    const stream = this.streams.get(deviceId);
    if (!stream) return null;
    
    return {
      active: stream.active,
      fps: stream.config.fps,
      resolution: `${stream.config.width}x${stream.config.height}`,
      buffer: stream.frameBuffer.length,
      uptime: Date.now() - stream.startTime
    };
  }
}

export { VideoStreamManager };
