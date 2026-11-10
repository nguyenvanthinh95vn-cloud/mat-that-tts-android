package vn.matthat.tts

import android.content.SharedPreferences

/**
 * Cai dat cua app — mac dinh giong het config.json cua ban Windows.
 * Luu trong SharedPreferences, sua o man hinh "Cai dat".
 */
data class AppSettings(
    var pauseBreak: Float = 1.0f,
    var pauseChunk: Float = 0.5f,
    var pauseComma: Float = 0.25f,
    var pauseDot: Float = 0.45f,
    var pauseColon: Float = 0.30f,
    var pauseSemi: Float = 0.40f,
    var noiseScale: Float = 0.667f,
    var noiseW: Float = 0.8f,
    var peak: Float = 0.99f,
    var speed: Float = 1.0f,
    var formatM4a: Boolean = true
) {
    fun toPauseRules(): PauseRules = PauseRules(
        breakPause = pauseBreak,
        chunkPause = pauseChunk,
        comma = pauseComma,
        dot = pauseDot,
        colon = pauseColon,
        semi = pauseSemi
    )

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putFloat("pause_break", pauseBreak)
            .putFloat("pause_chunk", pauseChunk)
            .putFloat("pause_comma", pauseComma)
            .putFloat("pause_dot", pauseDot)
            .putFloat("pause_colon", pauseColon)
            .putFloat("pause_semi", pauseSemi)
            .putFloat("noise_scale", noiseScale)
            .putFloat("noise_w", noiseW)
            .putFloat("peak", peak)
            .putFloat("speed", speed)
            .putBoolean("format_m4a", formatM4a)
            .apply()
    }

    companion object {
        fun load(prefs: SharedPreferences): AppSettings {
            val d = AppSettings()
            return AppSettings(
                pauseBreak = prefs.getFloat("pause_break", d.pauseBreak),
                pauseChunk = prefs.getFloat("pause_chunk", d.pauseChunk),
                pauseComma = prefs.getFloat("pause_comma", d.pauseComma),
                pauseDot = prefs.getFloat("pause_dot", d.pauseDot),
                pauseColon = prefs.getFloat("pause_colon", d.pauseColon),
                pauseSemi = prefs.getFloat("pause_semi", d.pauseSemi),
                noiseScale = prefs.getFloat("noise_scale", d.noiseScale),
                noiseW = prefs.getFloat("noise_w", d.noiseW),
                peak = prefs.getFloat("peak", d.peak),
                speed = prefs.getFloat("speed", d.speed),
                formatM4a = prefs.getBoolean("format_m4a", d.formatM4a)
            )
        }
    }
}
