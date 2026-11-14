package vn.matthat.tts

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Keo / gian am thanh theo thoi gian ma KHONG doi cao do — giong ffmpeg atempo.
 *
 * Thuat toan WSOLA (Waveform Similarity Overlap-Add): cat audio thanh cac khung
 * cua so Hann, voi moi khung moi tim trong audio goc doan khop nhat (tuong quan
 * lon nhat) roi noi chong len nhau. Giong doc nhanh len nhung cao do van nhu cu.
 *
 * Vi sao can cai nay: tang toc bang giong doc (length_scale / speed cua Piper)
 * chi ngan audio duoc ~20% du dat 2,5x — do du doan thoi luong cua VITS co gioi
 * han duoi. File SRT can nhanh trung binh 1,77x (co cau can toi 2,87x) nen kieu
 * tang toc bang giong doc KHONG BAO GIO vua slot. Chi co keo gian audio sau khi
 * doc xong (nhu atempo cua tool ProTTS) moi vua duoc.
 */
object Wsola {
    private const val FRAME_LEN = 1024 // ~46ms o 22050 Hz
    private const val HOP = 256        // buoc nhay cua khung tong hop
    private const val TOLERANCE = 256  // sai so tim doan khop quanh vi tri du kien

    /**
     * Keo / gian [input] ve dung [targetSamples] mau.
     * targetSamples < input.size = doc nhanh len; targetSamples > input.size
     * thi tra ve ban goc (khong lam cham). Luon tra ve dung targetSamples mau.
     */
    fun timeStretch(input: FloatArray, targetSamples: Int): FloatArray {
        val n = input.size
        if (targetSamples <= 0) return FloatArray(0)
        if (targetSamples >= n) return input.copyOf()
        // Doan qua ngan thi dung noi suy tuyen tinh cho don gian.
        if (n < FRAME_LEN || targetSamples < FRAME_LEN) {
            return resampleLinear(input, targetSamples)
        }
        val win = FloatArray(FRAME_LEN) { i ->
            (0.5f * (1f - cos(2f * PI.toFloat() * i / (FRAME_LEN - 1)))).toFloat()
        }
        val outLen = targetSamples
        val y = FloatArray(outLen + FRAME_LEN)
        val wsum = FloatArray(outLen + FRAME_LEN)
        val rate = n.toDouble() / targetSamples.toDouble()
        // Tong binh phuong tich luy de tinh nhanh norm cua moi doan ung vien.
        val psq = FloatArray(n + 1)
        for (i in 0 until n) psq[i + 1] = psq[i] + input[i] * input[i]

        // Khung dau: lay thang khong can tim.
        for (i in 0 until FRAME_LEN) {
            y[i] += input[i] * win[i]
            wsum[i] += win[i]
        }
        val ov = FRAME_LEN - HOP
        var k = 1
        while (true) {
            val p = k * HOP
            if (p > outLen) break
            val q = (k * HOP * rate).toInt()
            val lo = maxOf(0, q - TOLERANCE)
            val hi = minOf(n - ov, q + TOLERANCE)
            val qs: Int
            if (hi <= lo) {
                qs = q.coerceIn(0, n - FRAME_LEN)
            } else {
                // Norm cua doan chong lap da co trong y (tinh 1 lan / khung).
                var eNorm = 0f
                for (i in 0 until ov) eNorm += y[p + i] * y[p + i]
                eNorm = sqrt(eNorm)
                var bestDelta = 0
                var bestCorr = -1e18f
                for (d in -TOLERANCE..TOLERANCE) {
                    val cand = q + d
                    if (cand < lo || cand > hi) continue
                    var dot = 0f
                    for (i in 0 until ov) dot += input[cand + i] * y[p + i]
                    val cNorm = sqrt(maxOf(psq[cand + ov] - psq[cand], 0f))
                    val corr = dot / (cNorm * eNorm + 1e-12f)
                    if (corr > bestCorr) {
                        bestCorr = corr
                        bestDelta = d
                    }
                }
                qs = q + bestDelta
            }
            // Chen khung (cat bot neu vuot cuoi input).
            val avail = minOf(FRAME_LEN, n - qs)
            if (avail <= 0) break
            for (i in 0 until avail) {
                y[p + i] += input[qs + i] * win[i]
                wsum[p + i] += win[i]
            }
            k++
        }
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            out[i] = if (wsum[i] > 1e-6f) y[i] / wsum[i] else 0f
        }
        return out
    }

    /** Noi suy tuyen tinh — chi dung cho doan rat ngan, doi cao do chut it. */
    private fun resampleLinear(input: FloatArray, targetSamples: Int): FloatArray {
        if (targetSamples <= 0) return FloatArray(0)
        if (input.isEmpty()) return FloatArray(targetSamples)
        val out = FloatArray(targetSamples)
        for (i in 0 until targetSamples) {
            val pos = i.toDouble() * (input.size - 1).toDouble() / targetSamples.toDouble()
            val j = pos.toInt().coerceIn(0, input.size - 2)
            val frac = (pos - j).toFloat()
            out[i] = input[j] * (1f - frac) + input[j + 1] * frac
        }
        return out
    }
}
