package com.google.system

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.IOException

class AnimatedGifEncoder {
    private var width = 0
    private var height = 0
    private var repeat = -1
    private var delay = 0
    private var transparent = -1
    private var quality = 10
    private var started = false
    private val frames = mutableListOf<Frame>()

    private data class Frame(
        val image: Bitmap,
        val delay: Int
    )

    fun setSize(w: Int, h: Int) {
        width = w
        height = h
    }

    fun setRepeat(repeat: Int) { this.repeat = repeat }
    fun setDelay(ms: Int) { delay = ms / 10 }
    fun setQuality(q: Int) { quality = q.coerceIn(1, 30) }
    fun setTransparent(color: Int) { transparent = color }

    fun addFrame(image: Bitmap): Boolean {
        if (image.width != width || image.height != height) return false
        frames.add(Frame(image.copy(Bitmap.Config.ARGB_8888, false), delay))
        return true
    }

    fun finish(): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            out.write("GIF89a".toByteArray())
            writeLSD(out)
            writePalette(out)
            if (repeat >= 0) writeNetscapeExt(out)
            for (frame in frames) {
                writeGraphicControlExt(out)
                writeImageDescriptor(out)
                writePixels(out, frame.image)
            }
            out.write(0x3B)
            out.flush()
        } catch (e: IOException) {
            throw RuntimeException(e)
        } finally {
            for (frame in frames) frame.image.recycle()
            frames.clear()
        }
        return out.toByteArray()
    }

    private fun writeLSD(out: ByteArrayOutputStream) {
        writeShort(width, out)
        writeShort(height, out)
        out.write(0x80 or 0x70 or 0x00 or 7)
        out.write(0)
        out.write(0)
    }

    private fun writePalette(out: ByteArrayOutputStream) {
        val palette = IntArray(256)
        val used = mutableSetOf<Int>()
        for (frame in frames) {
            if (used.size >= 256) break
            val pixels = IntArray(frame.image.width * frame.image.height)
            frame.image.getPixels(pixels, 0, frame.image.width, 0, 0, frame.image.width, frame.image.height)
            for (p in pixels) {
                if (used.size >= 256) break
                val key = p and 0x00F8F8F8
                if (!used.contains(key)) {
                    used.add(key)
                    palette[used.size - 1] = key
                }
            }
        }
        for (i in 0 until 256) {
            val c = palette[i]
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
    }

    private fun writeNetscapeExt(out: ByteArrayOutputStream) {
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        out.write("NETSCAPE2.0".toByteArray())
        out.write(0x03)
        out.write(0x01)
        writeShort(repeat, out)
        out.write(0x00)
    }

    private fun writeGraphicControlExt(out: ByteArrayOutputStream) {
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        out.write(if (transparent >= 0) 0x09 else 0x08)
        writeShort(delay, out)
        out.write(if (transparent >= 0) transparent else 0)
        out.write(0x00)
    }

    private fun writeImageDescriptor(out: ByteArrayOutputStream) {
        out.write(0x2C)
        writeShort(0, out)
        writeShort(0, out)
        writeShort(width, out)
        writeShort(height, out)
        out.write(0x80 or 0)
    }

    private fun writePixels(out: ByteArrayOutputStream, image: Bitmap) {
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)
        val indices = ByteArray(width * height)
        val palette = IntArray(256)
        val used = mutableSetOf<Int>()
        for (p in pixels) {
            val key = p and 0x00F8F8F8
            if (!used.contains(key)) {
                used.add(key)
                palette[used.size - 1] = key
            }
        }
        for (i in pixels.indices) {
            val key = pixels[i] and 0x00F8F8F8
            for (j in 0 until used.size) {
                if (palette[j] == key) { indices[i] = j.toByte(); break }
            }
        }
        out.write(8)
        encodeLZW(indices, out)
    }

    private fun encodeLZW(data: ByteArray, out: ByteArrayOutputStream) {
        val clearCode = 256
        val eoiCode = 257
        var codeSize = 9
        var nextCode = eoiCode + 1
        val table = mutableMapOf<String, Int>()
        for (i in 0..255) table[(i.toByte().toInt() and 0xFF).toString()] = i

        fun resetTable() {
            table.clear()
            for (i in 0..255) table[(i.toByte().toInt() and 0xFF).toString()] = i
            nextCode = eoiCode + 1
            codeSize = 9
        }

        resetTable()
        val bb = BitBuffer(out)
        bb.write(clearCode, codeSize)

        if (data.isEmpty()) {
            bb.write(eoiCode, codeSize)
            bb.flush()
            return
        }

        var prefix = (data[0].toInt() and 0xFF).toString()

        for (i in 1 until data.size) {
            val c = (data[i].toInt() and 0xFF).toString()
            val key = "$prefix$c"
            if (table.containsKey(key)) {
                prefix = key
            } else {
                bb.write(table[prefix]!!, codeSize)
                if (nextCode < 4096) {
                    table[key] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                } else {
                    bb.write(clearCode, codeSize)
                    resetTable()
                    codeSize = 9
                }
                prefix = c
            }
        }
        bb.write(table[prefix]!!, codeSize)
        bb.write(eoiCode, codeSize)
        bb.flush()
    }

    private class BitBuffer(private val out: ByteArrayOutputStream) {
        private var buffer = 0
        private var bits = 0

        fun write(code: Int, size: Int) {
            buffer = buffer or (code shl bits)
            bits += size
            while (bits >= 8) {
                out.write(buffer and 0xFF)
                buffer = buffer ushr 8
                bits -= 8
            }
        }

        fun flush() {
            if (bits > 0) {
                out.write(buffer and 0xFF)
            }
            out.write(0)
        }
    }

    private fun writeShort(value: Int, out: ByteArrayOutputStream) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }
}
