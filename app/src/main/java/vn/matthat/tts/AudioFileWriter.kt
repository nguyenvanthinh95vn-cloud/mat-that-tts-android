package vn.matthat.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

/**
 * Ghi file am thanh.
 *
 * Luan 2 buoc (giong ban Windows):
 *  1. Doc tung doan -> ghi PCM int16 tam vao file tam, dong thoi do dinh
 *     am luong lon nhat (peak).
 *  2. Chuan hoa dinh ve [peakLimit] roi ghi file dich: M4A (AAC qua
 *     MediaCodec, mac dinh) hoac WAV (neu AAC loi hoac nguoi dung chon WAV).
 *
 * Lam theo file tam de khong giu toan bo audio trong RAM (truyen 20 phut
 * ~ 60MB PCM — dien thoai yeu van chay duoc).
 */
object AudioFileWriter {
    private const val TAG = "MatThatTTS"

    /** File tam PCM int16 mono, ghi kieu streaming. */
    class PcmTempFile(val file: File, val sampleRate: Int) {
        private val out = FileOutputStream(file)
        var peak: Float = 0f
            private set
        var totalSamples: Long = 0
            private set

        /** Ghi 1 doan float [-1, 1] -> int16 LE. */
        fun append(samples: FloatArray) {
            val buf = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = samples[i].coerceIn(-1f, 1f)
                val a = abs(v)
                if (a > peak) peak = a
                val s = (v * 32767f).toInt()
                buf[i * 2] = (s and 0xFF).toByte()
                buf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            out.write(buf)
            totalSamples += samples.size
        }

        /** Ghi khoang lang (0) dai [seconds] giay. */
        fun appendSilence(seconds: Float) {
            if (seconds <= 0f) return
            val n = (seconds * sampleRate).toInt()
            if (n <= 0) return
            val buf = ByteArray(min(n, 22050) * 2) // viet theo khoi 1 giay
            var left = n
            while (left > 0) {
                val w = min(left, 22050)
                out.write(buf, 0, w * 2)
                left -= w
            }
            totalSamples += n
        }

        fun close() {
            out.flush()
            out.close()
        }
    }

    /** Ghi file WAV 16-bit mono tu file tam PCM (co chuan hoa dinh). */
    fun writeWav(temp: PcmTempFile, outFile: File, peakLimit: Float): Boolean {
        return try {
            val scale = if (temp.peak > peakLimit && temp.peak > 0f) peakLimit / temp.peak else 1f
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44)) // cho header, ghi lai sau
                var dataBytes = 0L
                FileInputStream(temp.file).use { input ->
                    val buf = ByteArray(65536)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        if (scale != 1f) {
                            var i = 0
                            while (i < read) {
                                var s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                                if (s > 32767) s -= 65536
                                var v = (s * scale).toInt().coerceIn(-32768, 32767)
                                buf[i] = (v and 0xFF).toByte()
                                buf[i + 1] = ((v shr 8) and 0xFF).toByte()
                                i += 2
                            }
                        }
                        raf.write(buf, 0, read)
                        dataBytes += read
                    }
                }
                writeWavHeader(raf, temp.sampleRate, dataBytes)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeWav failed: $e")
            false
        }
    }

    private fun writeWavHeader(raf: RandomAccessFile, sampleRate: Int, dataBytes: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2) // byte rate
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }

    /**
     * Ma hoa AAC (M4A) tu file tam PCM qua MediaCodec + MediaMuxer.
     * Tra ve false neu thiet bi khong ho tro -> goi writeWav() de fallback.
     */
    fun writeAac(temp: PcmTempFile, outFile: File, peakLimit: Float): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val scale = if (temp.peak > peakLimit && temp.peak > 0f) peakLimit / temp.peak else 1f
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, temp.sampleRate, 1
            )
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 96000)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            var totalSamplesFed = 0L
            var sawEos = false

            val input = FileInputStream(temp.file)
            val chunkSamples = 4096
            val chunkBytes = ByteArray(chunkSamples * 2)
            val info = MediaCodec.BufferInfo()

            fun drain(endOfStream: Boolean) {
                var tries = 0
                while (true) {
                    val outIndex = codec!!.dequeueOutputBuffer(info, 10000)
                    if (outIndex >= 0) {
                        tries = 0
                        val outBuf = codec.getOutputBuffer(outIndex)
                        if (outBuf == null) {
                            codec.releaseOutputBuffer(outIndex, false)
                            continue
                        }
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (!muxerStarted) {
                                throw IllegalStateException("Muxer chua co track")
                            }
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            muxer!!.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawEos = true
                            break
                        }
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer!!.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else {
                        // INFO_TRY_AGAIN_LATER: cho them neu dang doi EOS
                        if (endOfStream) {
                            tries++
                            if (tries >= 50) break
                            Thread.sleep(10)
                            continue
                        }
                        break
                    }
                }
            }

            while (true) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex < 0) {
                    drain(false)
                    continue
                }
                val inBuf = codec.getInputBuffer(inIndex)!!
                inBuf.clear()
                var read = 0
                while (read < chunkBytes.size) {
                    val r = input.read(chunkBytes, read, chunkBytes.size - read)
                    if (r == -1) break
                    read += r
                }
                if (read <= 0) {
                    // Het du lieu: gui EOS
                    val pts = totalSamplesFed * 1_000_000L / temp.sampleRate
                    codec.queueInputBuffer(
                        inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    break
                }
                val samples = read / 2
                if (scale != 1f) {
                    var i = 0
                    while (i < read) {
                        var s = (chunkBytes[i].toInt() and 0xFF) or (chunkBytes[i + 1].toInt() shl 8)
                        if (s > 32767) s -= 65536
                        val v = (s * scale).toInt().coerceIn(-32768, 32767)
                        chunkBytes[i] = (v and 0xFF).toByte()
                        chunkBytes[i + 1] = ((v shr 8) and 0xFF).toByte()
                        i += 2
                    }
                }
                val pts = totalSamplesFed * 1_000_000L / temp.sampleRate
                inBuf.put(chunkBytes, 0, read)
                codec.queueInputBuffer(inIndex, 0, read, pts, 0)
                totalSamplesFed += samples
                drain(false)
            }
            input.close()
            drain(true)
            if (!muxerStarted || !sawEos) {
                throw IllegalStateException("AAC encode khong hoan tat (muxer=$muxerStarted, eos=$sawEos)")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeAac failed: $e")
            try { outFile.delete() } catch (ignored: Exception) { }
            false
        } finally {
            try { codec?.stop() } catch (ignored: Exception) { }
            try { codec?.release() } catch (ignored: Exception) { }
            try { muxer?.stop() } catch (ignored: Exception) { }
            try { muxer?.release() } catch (ignored: Exception) { }
        }
    }
}
