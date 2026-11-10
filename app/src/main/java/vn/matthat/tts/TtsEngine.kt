package vn.matthat.tts

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream

/**
 * Lop boc OfflineTts cua sherpa-onnx (chay model Piper/VITS offline).
 *
 * Cach doc file (theo dung mau cua app demo SherpaOnnxTts cua upstream,
 * sherpa-onnx v1.13.8):
 *  - Model .onnx + tokens.txt: doc TRUC TIEP tu assets qua AssetManager
 *    (duong dan tuong doi trong assets: "piper/ngochuyen.onnx").
 *  - espeak-ng-data: espeak can doc file that tren o dia, nen lan dau
 *    chay app se copy thu muc nay tu assets ra bo nho trong cua app
 *    (filesDir/piper/espeak-ng-data, ~19MB), cac lan sau dung lai.
 *
 * Model da duoc chuan bi dung chuan sherpa-onnx (xem tools/convert_model.py):
 *  - Them metadata vao file .onnx: model_type=vits, comment=piper,
 *    language=Vietnamese, voice=vi, has_espeak=1, n_speakers=1,
 *    sample_rate=22050.
 *  - tokens.txt sinh tu phoneme_id_map trong file .onnx.json goc.
 * Da kiem chung tren may Linux bang sherpa-onnx Python 1.13.8: doc duoc
 * tieng Viet that (466 chu -> 18.5s audio, khong loi).
 */
class TtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "MatThatTTS"
        private const val ASSET_MODEL = "piper/ngochuyen.onnx"
        private const val ASSET_TOKENS = "piper/tokens.txt"
        private const val ASSET_DATA_DIR = "piper/espeak-ng-data"
        private const val DATA_VERSION = "espeak-ng-data-v1"
        const val MODEL_SAMPLE_RATE = 22050
    }

    @Volatile
    private var tts: OfflineTts? = null

    val isReady: Boolean get() = tts != null

    val sampleRate: Int
        get() = try {
            tts?.sampleRate() ?: MODEL_SAMPLE_RATE
        } catch (e: Exception) {
            MODEL_SAMPLE_RATE
        }

    /**
     * Khoi tao engine. Gọi 1 lan (tren luong nen) truoc khi doc.
     * Nem Exception neu khoi tao loi (vd: thieu file model).
     */
    @Throws(Exception::class)
    fun init(settings: AppSettings) {
        if (tts != null) return
        val dataDir = prepareDataDir()
        val vits = OfflineTtsVitsModelConfig(
            model = ASSET_MODEL,
            tokens = ASSET_TOKENS,
            dataDir = dataDir.absolutePath,
            noiseScale = settings.noiseScale,
            noiseScaleW = settings.noiseW,
            lengthScale = 1.0f
        )
        val model = OfflineTtsModelConfig(
            vits = vits,
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        val config = OfflineTtsConfig(
            model = model,
            ruleFsts = "",
            ruleFars = "",
            maxNumSentences = 1
        )
        val engine = OfflineTts(assetManager = context.assets, config = config)
        tts = engine
        Log.i(TAG, "TTS ready, sampleRate=${sampleRate}, speakers=${engine.numSpeakers()}")
    }

    /** Doc 1 doan text ngan -> mang float [-1, 1]. Goi tren luong nen. */
    fun generate(text: String, speed: Float): FloatArray {
        val engine = tts ?: throw IllegalStateException("TTS chua khoi tao xong")
        return engine.generate(text, 0, speed).samples
    }

    fun free() {
        try {
            tts?.release()
        } catch (e: Exception) {
            Log.e(TAG, "free failed: $e")
        }
        tts = null
    }

    /** Copy espeak-ng-data tu assets ra filesDir (chi lam 1 lan). */
    private fun prepareDataDir(): File {
        val dest = File(context.filesDir, "piper/espeak-ng-data")
        val marker = File(context.filesDir, "piper/$DATA_VERSION.ready")
        if (marker.exists() && dest.isDirectory) {
            return dest
        }
        copyAssetDir(context.assets, ASSET_DATA_DIR, dest)
        marker.parentFile?.mkdirs()
        marker.writeText("ok")
        return dest
    }

    private fun copyAssetDir(am: AssetManager, assetPath: String, dest: File) {
        val list = am.list(assetPath) ?: return
        if (list.isEmpty()) {
            // Day la file, khong phai thu muc
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input ->
                FileOutputStream(dest).use { out ->
                    input.copyTo(out)
                }
            }
            return
        }
        dest.mkdirs()
        for (name in list) {
            copyAssetDir(am, "$assetPath/$name", File(dest, name))
        }
    }
}
