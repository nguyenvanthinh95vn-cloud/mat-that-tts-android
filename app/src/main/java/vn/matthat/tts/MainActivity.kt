package vn.matthat.tts

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mật Thất TTS — app doc truyen offline bang giong Ngoc Huyen (Piper/VITS).
 *
 * 3 man hinh: Doc van ban | SRT -> Voice | Cai dat.
 * Giong doc chay bang sherpa-onnx, model trong assets/piper/.
 * Text dai tu chia doan (toi da 800 ky tu/doan nhu ban Windows) roi noi
 * thanh 1 file. Luu ra Music/Mat That TTS/ tren may (qua MediaStore).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: TtsEngine
    private lateinit var settings: AppSettings
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private var mediaPlayer: MediaPlayer? = null
    private var busy = false

    // File am thanh vua tao xong (ban trong cache) + URI MediaStore cua no.
    private var lastOutputFile: File? = null
    private var lastOutputUri: Uri? = null

    private var srtCues: List<SrtCue> = emptyList()
    private var srtFileName: String = ""

    // --- Views ---
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvLog: TextView
    private lateinit var screenText: View
    private lateinit var screenSrt: View
    private lateinit var screenSettings: View
    private lateinit var btnTabText: Button
    private lateinit var btnTabSrt: Button
    private lateinit var btnTabSettings: Button

    private lateinit var etText: EditText
    private lateinit var tvCharCount: TextView
    private lateinit var sbSpeed: SeekBar
    private lateinit var tvSpeed: TextView
    private lateinit var etFileName: EditText
    private lateinit var btnGenerate: Button
    private lateinit var btnPreview: Button
    private lateinit var btnStop: Button
    private lateinit var btnPlaySaved: Button
    private lateinit var btnShare: Button
    private lateinit var btnSaveAs: Button

    private lateinit var tvSrtFile: TextView
    private lateinit var rgSrtMode: RadioGroup
    private lateinit var rbTimeline: RadioButton
    private lateinit var etSrtFileName: EditText
    private lateinit var btnGenerateSrt: Button

    private lateinit var etPauseBreak: EditText
    private lateinit var etPauseChunk: EditText
    private lateinit var etPauseComma: EditText
    private lateinit var etPauseDot: EditText
    private lateinit var etPauseColon: EditText
    private lateinit var etPauseSemi: EditText
    private lateinit var etNoiseScale: EditText
    private lateinit var etNoiseW: EditText
    private lateinit var etPeak: EditText
    private lateinit var rgFormat: RadioGroup
    private lateinit var rbM4a: RadioButton
    private lateinit var rbWav: RadioButton

    // --- Activity result launchers ---
    private val openTxtLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) loadTextFile(uri) }

    private val openSrtLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) loadSrtFile(uri) }

    private val saveM4aLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")
    ) { uri -> if (uri != null) copyLastOutputTo(uri) }

    private val saveWavLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri -> if (uri != null) copyLastOutputTo(uri) }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            appendLog("Da cap quyen ghi bo nho.")
            val action = pendingStorageAction
            pendingStorageAction = null
            action?.invoke()
        } else {
            appendLog("Khong co quyen ghi bo nho (Android cu): file se luu trong thu muc rieng cua app.")
            pendingStorageAction = null
        }
    }

    // Hanh dong cho cap quyen (chi dung tren Android < 10)
    private var pendingStorageAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = AppSettings.load(getSharedPreferences("matthat_tts", MODE_PRIVATE))
        engine = TtsEngine(this)

        bindViews()
        bindTabs()
        bindTextScreen()
        bindSrtScreen()
        bindSettingsScreen()
        loadSettingsToUi()
        showScreen(0)
        updateSpeedLabel()
        updateCharCount()

        tvStatus.setOnClickListener {
            if (!engine.isReady) initTtsAsync()
        }
        initTtsAsync()
    }

    override fun onDestroy() {
        try { mediaPlayer?.release() } catch (ignored: Exception) { }
        mediaPlayer = null
        try { engine.free() } catch (ignored: Exception) { }
        executor.shutdownNow()
        super.onDestroy()
    }

    // ======================= Khoi tao TTS =======================

    private fun initTtsAsync() {
        if (engine.isReady) {
            tvStatus.text = "Giọng: Ngọc Huyền • Sẵn sàng"
            return
        }
        tvStatus.text = "Giọng: Ngọc Huyền • Đang chuẩn bị (lần đầu copy dữ liệu, chờ chút)..."
        appendLog("Dang khoi tao giong doc...")
        executor.execute {
            try {
                engine.init(settings)
                runOnUiThread {
                    tvStatus.text = "Giọng: Ngọc Huyền • Sẵn sàng"
                    appendLog("Giong doc san sang (22050 Hz).")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Giọng: Ngọc Huyền • LỖI khởi tạo — chạm vào đây để thử lại"
                    appendLog("Loi khoi tao giong doc: ${e.message}")
                }
            }
        }
    }

    // ======================= Views =======================

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvLog = findViewById(R.id.tvLog)
        screenText = findViewById(R.id.screenText)
        screenSrt = findViewById(R.id.screenSrt)
        screenSettings = findViewById(R.id.screenSettings)
        btnTabText = findViewById(R.id.btnTabText)
        btnTabSrt = findViewById(R.id.btnTabSrt)
        btnTabSettings = findViewById(R.id.btnTabSettings)

        etText = findViewById(R.id.etText)
        tvCharCount = findViewById(R.id.tvCharCount)
        sbSpeed = findViewById(R.id.sbSpeed)
        tvSpeed = findViewById(R.id.tvSpeed)
        etFileName = findViewById(R.id.etFileName)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnPreview = findViewById(R.id.btnPreview)
        btnStop = findViewById(R.id.btnStop)
        btnPlaySaved = findViewById(R.id.btnPlaySaved)
        btnShare = findViewById(R.id.btnShare)
        btnSaveAs = findViewById(R.id.btnSaveAs)

        tvSrtFile = findViewById(R.id.tvSrtFile)
        rgSrtMode = findViewById(R.id.rgSrtMode)
        rbTimeline = findViewById(R.id.rbTimeline)
        etSrtFileName = findViewById(R.id.etSrtFileName)
        btnGenerateSrt = findViewById(R.id.btnGenerateSrt)

        etPauseBreak = findViewById(R.id.etPauseBreak)
        etPauseChunk = findViewById(R.id.etPauseChunk)
        etPauseComma = findViewById(R.id.etPauseComma)
        etPauseDot = findViewById(R.id.etPauseDot)
        etPauseColon = findViewById(R.id.etPauseColon)
        etPauseSemi = findViewById(R.id.etPauseSemi)
        etNoiseScale = findViewById(R.id.etNoiseScale)
        etNoiseW = findViewById(R.id.etNoiseW)
        etPeak = findViewById(R.id.etPeak)
        rgFormat = findViewById(R.id.rgFormat)
        rbM4a = findViewById(R.id.rbM4a)
        rbWav = findViewById(R.id.rbWav)
    }

    private fun bindTabs() {
        btnTabText.setOnClickListener { showScreen(0) }
        btnTabSrt.setOnClickListener { showScreen(1) }
        btnTabSettings.setOnClickListener { showScreen(2) }
    }

    private fun showScreen(index: Int) {
        screenText.visibility = if (index == 0) View.VISIBLE else View.GONE
        screenSrt.visibility = if (index == 1) View.VISIBLE else View.GONE
        screenSettings.visibility = if (index == 2) View.VISIBLE else View.GONE
        val active = ContextCompat.getColor(this, R.color.matthat_red)
        val idle = ContextCompat.getColor(this, R.color.matthat_hint)
        btnTabText.setTextColor(if (index == 0) active else idle)
        btnTabSrt.setTextColor(if (index == 1) active else idle)
        btnTabSettings.setTextColor(if (index == 2) active else idle)
    }

    // ======================= Man hinh doc van ban =======================

    private fun bindTextScreen() {
        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateCharCount() }
        })
        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                updateSpeedLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        findViewById<Button>(R.id.btnOpenTxt).setOnClickListener {
            openTxtLauncher.launch("text/plain")
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            etText.setText("")
        }
        btnGenerate.setOnClickListener { ensureStoragePermission { runTextGeneration() } }
        btnPreview.setOnClickListener { runPreview() }
        btnStop.setOnClickListener { requestStop() }
        findViewById<Button>(R.id.btnStopSrt).setOnClickListener { requestStop() }
        btnPlaySaved.setOnClickListener { playLastOutput() }
        btnShare.setOnClickListener { shareLastOutput() }
        btnSaveAs.setOnClickListener {
            val file = lastOutputFile
            if (file == null || !file.exists()) {
                Toast.makeText(this, "Chưa có file nào để lưu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = file.nameWithoutExtension
            if (settings.formatM4a) saveM4aLauncher.launch("$name.m4a")
            else saveWavLauncher.launch("$name.wav")
        }
    }

    private fun updateCharCount() {
        tvCharCount.text = "${etText.text.length} ký tự"
    }

    private fun currentSpeed(): Float {
        return 0.5f + sbSpeed.progress / 100f
    }

    private fun updateSpeedLabel() {
        tvSpeed.text = String.format(Locale.US, "%.2fx", currentSpeed())
    }

    private fun loadTextFile(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }?.trimStart('\uFEFF') ?: ""
            etText.setText(text)
            appendLog("Da mo file text (${text.length} ky tu).")
        } catch (e: Exception) {
            appendLog("Mo file loi: ${e.message}")
            Toast.makeText(this, "Mở file lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================= Man hinh SRT =======================

    private fun bindSrtScreen() {
        findViewById<Button>(R.id.btnPickSrt).setOnClickListener {
            openSrtLauncher.launch("*/*")
        }
        btnGenerateSrt.setOnClickListener { ensureStoragePermission { runSrtGeneration() } }
    }

    private fun loadSrtFile(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }?.trimStart('\uFEFF') ?: ""
            srtCues = parseSrt(raw)
            srtFileName = uri.lastPathSegment ?: "srt"
            if (srtCues.isEmpty()) {
                tvSrtFile.text = "File khong co dong phu de nao doc duoc"
                appendLog("File SRT khong co cue nao.")
            } else {
                val last = srtCues.last()
                val mins = (last.end / 60).toInt()
                val secs = (last.end % 60).toInt()
                tvSrtFile.text = "$srtFileName — ${srtCues.size} dong, dai ~${mins}:${String.format("%02d", secs)}"
                appendLog("Da mo SRT: ${srtCues.size} dong.")
            }
        } catch (e: Exception) {
            appendLog("Mo SRT loi: ${e.message}")
            Toast.makeText(this, "Mở SRT lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================= Man hinh cai dat =======================

    private fun bindSettingsScreen() {
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            if (readSettingsFromUi()) {
                settings.save(getSharedPreferences("matthat_tts", MODE_PRIVATE))
                appendLog("Da luu cai dat. Giong doc se dung cai dat moi tu lan doc sau (can khoi dong lai app de doi noise).")
                Toast.makeText(this, "Đã lưu cài đặt", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnResetSettings).setOnClickListener {
            settings = AppSettings()
            settings.save(getSharedPreferences("matthat_tts", MODE_PRIVATE))
            loadSettingsToUi()
            appendLog("Da khoi phuc cai dat mac dinh.")
        }
    }

    private fun loadSettingsToUi() {
        etPauseBreak.setText(settings.pauseBreak.toString())
        etPauseChunk.setText(settings.pauseChunk.toString())
        etPauseComma.setText(settings.pauseComma.toString())
        etPauseDot.setText(settings.pauseDot.toString())
        etPauseColon.setText(settings.pauseColon.toString())
        etPauseSemi.setText(settings.pauseSemi.toString())
        etNoiseScale.setText(settings.noiseScale.toString())
        etNoiseW.setText(settings.noiseW.toString())
        etPeak.setText(settings.peak.toString())
        sbSpeed.progress = ((settings.speed - 0.5f) * 100).toInt().coerceIn(0, 150)
        if (settings.formatM4a) rbM4a.isChecked = true else rbWav.isChecked = true
    }

    /** Doc gia tri tu man hinh cai dat vao [settings]. Tra ve false neu so khong hop le. */
    private fun readSettingsFromUi(): Boolean {
        fun f(edit: EditText, name: String): Float? {
            val v = edit.text.toString().toFloatOrNull()
            if (v == null || v < 0f) {
                Toast.makeText(this, "Số không hợp lệ: $name", Toast.LENGTH_SHORT).show()
                return null
            }
            return v
        }
        val b = f(etPauseBreak, "ngat doan") ?: return false
        val c = f(etPauseChunk, "ngat chunk") ?: return false
        val co = f(etPauseComma, "dau phay") ?: return false
        val d = f(etPauseDot, "dau cham") ?: return false
        val cl = f(etPauseColon, "hai cham") ?: return false
        val s = f(etPauseSemi, "cham phay") ?: return false
        val ns = f(etNoiseScale, "noise_scale") ?: return false
        val nw = f(etNoiseW, "noise_w") ?: return false
        val pk = f(etPeak, "am luong dinh") ?: return false
        if (pk > 1f) {
            Toast.makeText(this, "Âm lượng đỉnh tối đa là 1.0", Toast.LENGTH_SHORT).show()
            return false
        }
        settings.pauseBreak = b
        settings.pauseChunk = c
        settings.pauseComma = co
        settings.pauseDot = d
        settings.pauseColon = cl
        settings.pauseSemi = s
        settings.noiseScale = ns
        settings.noiseW = nw
        settings.peak = pk
        settings.speed = currentSpeed()
        settings.formatM4a = rgFormat.checkedRadioButtonId == R.id.rbM4a
        return true
    }

    // ======================= Doc & luu =======================

    private fun requestStop() {
        stopRequested.set(true)
        try { mediaPlayer?.stop() } catch (ignored: Exception) { }
        appendLog("Da nhan Dung.")
    }

    private fun setBusy(b: Boolean) {
        busy = b
        runOnUiThread {
            progressBar.visibility = if (b) View.VISIBLE else View.GONE
            tvProgress.visibility = if (b) View.VISIBLE else View.GONE
            btnGenerate.isEnabled = !b
            btnPreview.isEnabled = !b
            btnGenerateSrt.isEnabled = !b
        }
    }

    private fun ensureStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageAction = action
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            Toast.makeText(this, "Cần quyền ghi bộ nhớ để lưu file", Toast.LENGTH_SHORT).show()
            return
        }
        action()
    }

    private fun autoFileName(): String {
        val typed = sanitizeFileName(etFileName.text.toString())
        if (typed.isNotEmpty()) return typed
        return "mat-that-tts-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    private fun autoSrtFileName(): String {
        val typed = sanitizeFileName(etSrtFileName.text.toString())
        if (typed.isNotEmpty()) return typed
        return "mat-that-tts-srt-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    private fun runTextGeneration() {
        if (busy) return
        if (!engine.isReady) {
            Toast.makeText(this, "Giọng đọc chưa sẵn sàng, chờ chút", Toast.LENGTH_SHORT).show()
            return
        }
        val text = etText.text.toString()
        if (cleanText(text).isEmpty()) {
            Toast.makeText(this, "Chưa có văn bản để đọc", Toast.LENGTH_SHORT).show()
            return
        }
        settings.speed = currentSpeed()
        val baseName = autoFileName()
        val speed = settings.speed
        val rules = settings.toPauseRules()
        stopRequested.set(false)
        setBusy(true)
        appendLog("Bat dau doc (${text.length} ky tu, toc do ${speed}x)...")
        executor.execute {
            try {
                val chunks = splitIntoChunks(text)
                appendLog("Chia thanh ${chunks.size} doan.")
                val temp = newTempPcm()
                var done = 0
                for ((index, chunk) in chunks.withIndex()) {
                    if (stopRequested.get()) {
                        appendLog("Da dung o doan ${index + 1}/${chunks.size}.")
                        break
                    }
                    appendTextWithPauses(temp, chunk, rules, speed)
                    if (index < chunks.size - 1) {
                        temp.appendSilence(rules.chunkPause)
                    }
                    done = index + 1
                    updateProgress(done, chunks.size, "Đang đọc đoạn $done/${chunks.size}...")
                }
                temp.close()
                if (stopRequested.get() && done == 0) {
                    appendLog("Da dung truoc khi doc xong.")
                    try { temp.file.delete() } catch (ignored: Exception) { }
                } else {
                    finishAndSave(temp, baseName)
                }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Loi khi doc: ${e.message}") }
            } finally {
                setBusy(false)
            }
        }
    }

    private fun runSrtGeneration() {
        if (busy) return
        if (!engine.isReady) {
            Toast.makeText(this, "Giọng đọc chưa sẵn sàng, chờ chút", Toast.LENGTH_SHORT).show()
            return
        }
        if (srtCues.isEmpty()) {
            Toast.makeText(this, "Chưa chọn file .srt", Toast.LENGTH_SHORT).show()
            return
        }
        settings.speed = currentSpeed()
        val baseName = autoSrtFileName()
        val speed = settings.speed
        val rules = settings.toPauseRules()
        val keepTimeline = rbTimeline.isChecked
        stopRequested.set(false)
        setBusy(true)
        appendLog("Bat dau doc SRT (${srtCues.size} dong, ${if (keepTimeline) "giu timeline" else "lien mach"})...")
        executor.execute {
            try {
                val temp = newTempPcm()
                if (keepTimeline) {
                    // Dat moi cue dung timestamp goc (nhu ban Windows).
                    var posSec = 0.0
                    var over = 0
                    for ((index, cue) in srtCues.withIndex()) {
                        if (stopRequested.get()) break
                        if (cue.start > posSec) {
                            temp.appendSilence((cue.start - posSec).toFloat())
                            posSec = cue.start
                        }
                        val before = temp.totalSamples
                        appendTextWithPauses(temp, cue.text, rules, speed)
                        val dur = (temp.totalSamples - before).toDouble() / temp.sampleRate
                        if (dur > cue.end - cue.start) over++
                        posSec = maxOf(posSec, cue.start + dur)
                        updateProgress(index + 1, srtCues.size, "Đang đọc dòng ${index + 1}/${srtCues.size}...")
                    }
                    if (over > 0) appendLog("Co $over dong doc tran gio cua no (de tran, khong doi dong sau).")
                } else {
                    val text = srtCues.joinToString(" ") { it.text }
                    val chunks = splitIntoChunks(text)
                    for ((index, chunk) in chunks.withIndex()) {
                        if (stopRequested.get()) break
                        appendTextWithPauses(temp, chunk, rules, speed)
                        if (index < chunks.size - 1) temp.appendSilence(rules.chunkPause)
                        updateProgress(index + 1, chunks.size, "Đang đọc đoạn ${index + 1}/${chunks.size}...")
                    }
                }
                temp.close()
                if (!stopRequested.get()) {
                    finishAndSave(temp, baseName)
                } else {
                    appendLog("Da dung doc SRT.")
                    try { temp.file.delete() } catch (ignored: Exception) { }
                }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Loi khi doc SRT: ${e.message}") }
            } finally {
                setBusy(false)
            }
        }
    }

    /** Doc 1 doan text (co chen khoang lang theo dau cau) vao file tam PCM. */
    private fun appendTextWithPauses(
        temp: AudioFileWriter.PcmTempFile,
        text: String,
        rules: PauseRules,
        speed: Float
    ) {
        for ((frag, pause) in splitFragments(text, rules)) {
            if (stopRequested.get()) return
            val samples = engine.generate(frag, speed)
            temp.append(samples)
            if (pause > 0f) temp.appendSilence(pause)
        }
    }

    private fun newTempPcm(): AudioFileWriter.PcmTempFile {
        val file = File(cacheDir, "temp-${System.currentTimeMillis()}.pcm")
        return AudioFileWriter.PcmTempFile(file, engine.sampleRate)
    }

    /** Ma hoa file tam -> file dich (M4A/WAV), luu vao MediaStore, cap nhat UI. */
    private fun finishAndSave(temp: AudioFileWriter.PcmTempFile, baseName: String) {
        val durationSec = temp.totalSamples.toDouble() / temp.sampleRate
        val mins = (durationSec / 60).toInt()
        val secs = (durationSec % 60).toInt()
        val durText = String.format(Locale.US, "%d:%02d", mins, secs)
        try {
            var ok: Boolean
            var ext: String
            var mime: String
            if (settings.formatM4a) {
                val m4a = File(cacheDir, "$baseName.m4a")
                ok = AudioFileWriter.writeAac(temp, m4a, settings.peak)
                if (ok) {
                    lastOutputFile = m4a
                    ext = "m4a"; mime = "audio/mp4"
                } else {
                    appendLog("Ma hoa AAC loi, luu file WAV thay the.")
                    val wav = File(cacheDir, "$baseName.wav")
                    ok = AudioFileWriter.writeWav(temp, wav, settings.peak)
                    lastOutputFile = wav
                    ext = "wav"; mime = "audio/wav"
                }
            } else {
                val wav = File(cacheDir, "$baseName.wav")
                ok = AudioFileWriter.writeWav(temp, wav, settings.peak)
                lastOutputFile = wav
                ext = "wav"; mime = "audio/wav"
            }
            if (!ok || lastOutputFile == null || !lastOutputFile!!.exists()) {
                runOnUiThread { appendLog("Loi: khong ghi duoc file am thanh.") }
                return
            }
            val displayName = "$baseName.$ext"
            lastOutputUri = saveToMediaStore(lastOutputFile!!, displayName, mime)
            runOnUiThread {
                if (lastOutputUri != null) {
                    appendLog("XONG: $displayName — dai $durText, da luu vao Music/Mật Thất TTS/.")
                    Toast.makeText(this, "Đã lưu: $displayName ($durText)", Toast.LENGTH_LONG).show()
                } else {
                    appendLog("XONG: $displayName — dai $durText, luu trong bo nho app (khong dua duoc ra Music).")
                    Toast.makeText(this, "Đã lưu trong app: $displayName", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread { appendLog("Loi khi luu file: ${e.message}") }
        } finally {
            try { temp.file.delete() } catch (ignored: Exception) { }
        }
    }

    private fun saveToMediaStore(file: File, displayName: String, mime: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Mật Thất TTS")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val uri = contentResolver.insert(collection, values) ?: return null
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, done, null, null)
            }
            uri
        } catch (e: Exception) {
            appendLog("Luu vao Music loi: ${e.message}")
            null
        }
    }

    // ======================= Nghe thu / nghe lai / chia se =======================

    private fun runPreview() {
        if (busy) return
        if (!engine.isReady) {
            Toast.makeText(this, "Giọng đọc chưa sẵn sàng, chờ chút", Toast.LENGTH_SHORT).show()
            return
        }
        val text = cleanText(etText.text.toString())
        if (text.isEmpty()) {
            Toast.makeText(this, "Chưa có văn bản để nghe thử", Toast.LENGTH_SHORT).show()
            return
        }
        val sample = splitIntoChunks(text).firstOrNull() ?: return
        stopRequested.set(false)
        setBusy(true)
        appendLog("Nghe thu doan dau...")
        executor.execute {
            try {
                val temp = newTempPcm()
                appendTextWithPauses(temp, sample, settings.toPauseRules(), currentSpeed())
                temp.close()
                val wav = File(cacheDir, "preview-${System.currentTimeMillis()}.wav")
                if (AudioFileWriter.writeWav(temp, wav, settings.peak)) {
                    try { temp.file.delete() } catch (ignored: Exception) { }
                    runOnUiThread { playFile(wav) }
                } else {
                    runOnUiThread { appendLog("Nghe thu loi: khong ghi duoc file tam.") }
                }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Nghe thu loi: ${e.message}") }
            } finally {
                setBusy(false)
            }
        }
    }

    private fun playLastOutput() {
        val file = lastOutputFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Chưa có file nào để nghe", Toast.LENGTH_SHORT).show()
            return
        }
        playFile(file)
    }

    private fun playFile(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
            appendLog("Dang phat: ${file.name}")
        } catch (e: Exception) {
            appendLog("Phat file loi: ${e.message}")
            Toast.makeText(this, "Phát lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLastOutput() {
        val file = lastOutputFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Chưa có file nào để chia sẻ", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                this, "vn.matthat.tts.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (settings.formatM4a) "audio/mp4" else "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Chia sẻ file âm thanh"))
        } catch (e: Exception) {
            appendLog("Chia se loi: ${e.message}")
        }
    }

    private fun copyLastOutputTo(uri: Uri) {
        val file = lastOutputFile
        if (file == null || !file.exists()) return
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            }
            appendLog("Da luu ra: ${file.name}")
            Toast.makeText(this, "Đã lưu ra chỗ bạn chọn", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            appendLog("Luu ra loi: ${e.message}")
        }
    }

    // ======================= Tien ich UI =======================

    private fun updateProgress(done: Int, total: Int, text: String) {
        runOnUiThread {
            val pct = if (total > 0) done * 100 / total else 0
            progressBar.progress = pct
            tvProgress.text = "$text $pct%"
        }
    }

    private fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            tvLog.append("[$time] $msg\n")
            (tvLog.parent as? ScrollView)?.post {
                (tvLog.parent as ScrollView).fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
