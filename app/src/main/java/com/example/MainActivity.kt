package com.example.simultrans

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * App de traducción simultánea multi-idioma + asistente IA con prompt
 * libre (texto o voz), usando Gemma 4 E2B a través de LiteRT-LM, 100%
 * on-device.
 *
 * Tiene 2 pestañas:
 * - "Traductor": 2 idiomas, un botón por idioma para grabar y traducir.
 * - "Asistente IA": prompt libre por texto o dictado por voz (botón 🎤),
 *   con opción de adjuntar imagen/PDF/txt, y lectura opcional de la
 *   respuesta en voz alta (checkbox 🔊).
 *
 * RECONOCIMIENTO DE VOZ: en vez de usar el SpeechRecognizer del sistema
 * operativo (que en algunos fabricantes, como Samsung/One UI, no respeta
 * los paquetes de idioma offline instalados y obliga a tener conexión),
 * se graba el audio con MediaRecorder y se transcribe directamente con
 * Gemma 4 E2B, que reconoce voz de forma nativa. Esto hace que TODO el
 * reconocimiento de voz sea 100% on-device, sin depender de qué motor de
 * voz haya elegido el fabricante del móvil. A cambio, el control pasa de
 * "habla y para sola" a "toca para grabar, toca otra vez para parar".
 *
 * CAPTURA DE ERRORES: si la app se cierra por un fallo inesperado, el
 * stack trace se guarda en SharedPreferences y se muestra en un diálogo
 * copiable la próxima vez que se abre la app.
 *
 * El modelo NO se incluye en el APK (pesa varios GB). El botón "Descargar e
 * instalar el modelo" lo baja de Hugging Face (repositorio público, licencia
 * Apache 2.0, sin cuenta) con barra de progreso y lo activa al terminar; el
 * botón "Ya lo tengo" permite elegir un archivo ya descargado.
 */

/**
 * Idiomas soportados. displayName se usa tanto en la UI como en el prompt
 * de traducción/transcripción. ttsLocale es el Locale que espera
 * TextToSpeech. colorHex se usa para el botón y, en versión clara, para
 * el fondo de la burbuja de ese idioma. speechLocale ya no se usa para
 * pedirle nada a Android (no hay SpeechRecognizer), pero se conserva por
 * si se quiere mostrar o depurar en el futuro.
 */
enum class Idioma(
    val displayName: String,
    val speechLocale: String,
    val ttsLocale: Locale,
    val colorHex: String,
    val bandera: String
) {
    ESPANOL("Español", "es-ES", Locale("es", "ES"), "#C60B1E", "🇪🇸"),
    INGLES("Inglés", "en-US", Locale.US, "#00247D", "🇬🇧"),
    FRANCES("Francés", "fr-FR", Locale.FRANCE, "#0055A4", "🇫🇷"),
    ITALIANO("Italiano", "it-IT", Locale.ITALY, "#008C45", "🇮🇹"),
    CHINO("Chino", "zh-CN", Locale.SIMPLIFIED_CHINESE, "#DE2910", "🇨🇳"),
    TURCO("Turco", "tr-TR", Locale("tr", "TR"), "#E30A17", "🇹🇷"),
    ARABE("Árabe", "ar-SA", Locale("ar", "SA"), "#006C35", "🇸🇦"),
    ALEMAN("Alemán", "de-DE", Locale.GERMANY, "#FFCE00", "🇩🇪"),
    PORTUGUES("Portugués", "pt-PT", Locale("pt", "PT"), "#046A38", "🇵🇹"),
    RUMANO("Rumano", "ro-RO", Locale("ro", "RO"), "#002B7F", "🇷🇴"),
    POLACO("Polaco", "pl-PL", Locale("pl", "PL"), "#DC143C", "🇵🇱");

    /** Texto para la interfaz (bandera + nombre). El prompt usa displayName. */
    val etiqueta: String get() = "$bandera $displayName"

    override fun toString(): String = etiqueta
}

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnStopSpeech: Button
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var spinnerLangA: Spinner
    private lateinit var spinnerLangB: Spinner
    private lateinit var btnLangA: Button
    private lateinit var btnLangB: Button
    private lateinit var btnPickModel: Button
    private lateinit var btnDownloadModel: Button
    private lateinit var btnClearHistory: TextView
    private lateinit var footerBanner: LinearLayout

    private lateinit var tabTranslator: Button
    private lateinit var tabAssistant: Button
    private lateinit var translatorContainer: LinearLayout
    private lateinit var assistantContainer: LinearLayout
    private lateinit var assistantTranscript: LinearLayout
    private lateinit var assistantScrollView: ScrollView
    private lateinit var spinnerAssistantLang: Spinner
    private lateinit var btnClearAssistantHistory: TextView
    private lateinit var promptInput: EditText
    private lateinit var btnAttach: Button
    private lateinit var btnMic: Button
    private lateinit var chkSpeakResponses: CheckBox
    private lateinit var chkWebSearch: CheckBox
    private lateinit var progressDownload: ProgressBar
    private var descargaJob: Job? = null
    private var ultimaPreguntaAsistente: String? = null
    private var ultimaConsultaUsada: String? = null
    private var ultimaReciente: Boolean = false
    private val versionApp: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
    }
    private lateinit var btnSendPrompt: Button
    private lateinit var txtAttachment: TextView

    private lateinit var translationEngine: TranslationEngine
    private lateinit var tts: TextToSpeech
    private lateinit var modelFile: File
    private lateinit var prefs: SharedPreferences

    // Pareja de idiomas activa en la conversación del Traductor.
    private var langA: Idioma = Idioma.ESPANOL
    private var langB: Idioma = Idioma.INGLES

    // Idioma de voz del Asistente IA (dictado y lectura de respuestas).
    private var assistantVoiceLang: Idioma = Idioma.ESPANOL

    private var isBusy = false
    private var modelReady = false

    // Estado de grabación de audio, compartido por las 2 pestañas.
    private var grabandoTraductor = false
    private var idiomaGrabandoTraductor: Idioma? = null
    private var grabandoAsistente = false

    // Adjunto pendiente de enviar en la pestaña Asistente IA.
    private var pendingImages: List<File> = emptyList()
    private var pendingTextContext: String? = null
    private var pendingAttachmentLabel: String? = null

    /**
     * Transcripción de la conversación del Asistente IA que había quedado
     * guardada de una sesión anterior (se reconstruye en [restoreAssistantHistory]).
     * Como la "memoria" del modelo vive solo en memoria RAM, al reabrir la app
     * el modelo no recuerda nada aunque las burbujas sigan en pantalla; por
     * eso se envía como contexto junto al primer mensaje nuevo que se mande,
     * y luego se descarta (a partir de ahí la conversación en memoria ya
     * mantiene el hilo por sí sola).
     */
    private var pendingAssistantContext: String? = null

    private val pickModelFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) copyModelFromUri(uri)
    }

    private val pickAttachment = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) handleAttachmentPicked(uri)
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            statusText.text = "Se necesita permiso de micrófono para funcionar."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashHandler()
        super.onCreate(savedInstanceState)

        // Se comprueba y muestra el error ANTES de tocar el layout ni las
        // vistas, para que el diálogo aparezca incluso si el fallo está en
        // el propio inflado de activity_main.xml o en algún findViewById.
        prefs = getSharedPreferences("simultrans_prefs", MODE_PRIVATE)
        mostrarUltimoCrashSiExiste()

        setContentView(R.layout.activity_main)

        // Con targetSdk 35+ Android 15 dibuja la app de borde a borde: sin
        // esto la barra de estado tapa el texto de arriba y la barra de
        // navegación tapa el banner de abajo. Se añade el relleno necesario
        // (y el del teclado, que ya no redimensiona la ventana por sí solo).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(barras.left, barras.top, barras.right, maxOf(barras.bottom, teclado.bottom))
            WindowInsetsCompat.CONSUMED
        }

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        btnStopSpeech = findViewById(R.id.btnStopSpeech)
        transcriptContainer = findViewById(R.id.transcriptContainer)
        scrollView = findViewById(R.id.scrollView)
        spinnerLangA = findViewById(R.id.spinnerLangA)
        spinnerLangB = findViewById(R.id.spinnerLangB)
        btnLangA = findViewById(R.id.btnLangA)
        btnLangB = findViewById(R.id.btnLangB)
        btnPickModel = findViewById(R.id.btnPickModel)
        btnDownloadModel = findViewById(R.id.btnDownloadModel)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        footerBanner = findViewById(R.id.footerBanner)

        tabTranslator = findViewById(R.id.tabTranslator)
        tabAssistant = findViewById(R.id.tabAssistant)
        translatorContainer = findViewById(R.id.translatorContainer)
        assistantContainer = findViewById(R.id.assistantContainer)
        assistantTranscript = findViewById(R.id.assistantTranscript)
        assistantScrollView = findViewById(R.id.assistantScrollView)
        spinnerAssistantLang = findViewById(R.id.spinnerAssistantLang)
        btnClearAssistantHistory = findViewById(R.id.btnClearAssistantHistory)
        promptInput = findViewById(R.id.promptInput)
        btnAttach = findViewById(R.id.btnAttach)
        btnMic = findViewById(R.id.btnMic)
        chkSpeakResponses = findViewById(R.id.chkSpeakResponses)
        chkWebSearch = findViewById(R.id.chkWebSearch)
        progressDownload = findViewById(R.id.progressDownload)
        chkWebSearch.isChecked = prefs.getBoolean("busqueda_web", true)
        chkWebSearch.setOnCheckedChangeListener { _, marcado ->
            prefs.edit().putBoolean("busqueda_web", marcado).apply()
        }
        btnSendPrompt = findViewById(R.id.btnSendPrompt)
        txtAttachment = findViewById(R.id.txtAttachment)

        ensureMicPermission()
        setupLanguageSpinners()
        setupAssistantLanguageSpinner()
        updateLanguageButtons()
        restoreHistory()
        restoreAssistantHistory()
        setupTabs()

        btnClearHistory.setOnClickListener { clearHistory() }
        footerBanner.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://maropal.es"))
            startActivity(intent)
        }
        btnClearAssistantHistory.setOnClickListener { clearAssistantHistory() }
        btnStopSpeech.setOnClickListener { tts.stop() }
        btnAttach.setOnClickListener {
            pickAttachment.launch(arrayOf("image/*", "application/pdf", "text/plain"))
        }
        btnMic.setOnClickListener { onMicAssistantClick() }
        btnSendPrompt.setOnClickListener { sendPrompt() }

        tts = TextToSpeech(this) { }

        val modelDir = File(filesDir, "models")
        modelDir.mkdirs()
        modelFile = File(modelDir, "gemma-4-E2B-it.litertlm")
        translationEngine = TranslationEngine(modelFile)

        btnLangA.setOnClickListener { onLangButtonClick(langA) }
        btnLangB.setOnClickListener { onLangButtonClick(langB) }
        btnPickModel.setOnClickListener {
            // "*/*" porque .litertlm no tiene un tipo MIME reconocido por Android
            pickModelFile.launch(arrayOf("*/*"))
        }
        btnDownloadModel.setOnClickListener { onDescargarModeloClick() }

        loadModel(translationEngine)
    }

    /**
     * Instala un manejador global de errores no capturados: guarda el
     * stack trace en SharedPreferences antes de que la app se cierre, para
     * poder mostrarlo la próxima vez que se abra. Debe llamarse ANTES de
     * super.onCreate() para capturar también fallos muy tempranos.
     */
    private fun installCrashHandler() {
        val manejadorPrevio = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                getSharedPreferences("simultrans_prefs", MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, trace)
                    .apply()
            } catch (e: Exception) {
                // Si ni siquiera se puede guardar el error, seguimos con el cierre normal.
            }
            manejadorPrevio?.uncaughtException(thread, throwable)
                ?: run {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(10)
                }
        }
    }

    /**
     * Si en el arranque anterior la app se cerró por un error, lo muestra
     * en un diálogo con el texto completo y seleccionable, más un botón
     * "Copiar" que lo pone en el portapapeles.
     */
    private fun mostrarUltimoCrashSiExiste() {
        val texto = prefs.getString(KEY_LAST_CRASH, null) ?: return
        prefs.edit().remove(KEY_LAST_CRASH).apply()

        try {
            val textView = TextView(this).apply {
                text = texto
                setPadding(32, 32, 32, 32)
                setTextIsSelectable(true)
                textSize = 12f
            }
            val scroll = ScrollView(this).apply { addView(textView) }

            AlertDialog.Builder(this)
                .setTitle("La app se cerró por un error")
                .setView(scroll)
                .setPositiveButton("Copiar") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Error SimulTrans", texto))
                    Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cerrar", null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error guardado pero no se pudo mostrar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Alterna entre la pestaña Traductor y la pestaña Asistente IA. */
    private fun setupTabs() {
        tabTranslator.setOnClickListener {
            translatorContainer.visibility = View.VISIBLE
            assistantContainer.visibility = View.GONE
        }
        tabAssistant.setOnClickListener {
            translatorContainer.visibility = View.GONE
            assistantContainer.visibility = View.VISIBLE
        }
    }

    // ==================== GRABACIÓN DE AUDIO (compartida) ====================

    /**
     * Inicia una grabación de audio en crudo con AudioRecord. No se usa
     * MediaRecorder porque genera .m4a (AAC), un formato que el
     * decodificador interno de LiteRT-LM (miniaudio) NO soporta —
     * solo entiende WAV, FLAC, MP3 y OGG. Aquí se capturan muestras PCM
     * de 16 bits a 16 kHz en un hilo de fondo mientras grabandoActiva sea
     * true, y al parar se empaquetan en un .wav válido (ver escribirWav).
     */
    private var audioRecord: android.media.AudioRecord? = null
    private var grabacionJob: kotlinx.coroutines.Job? = null
    private var grabacionActiva = false
    private val bufferGrabacion = java.io.ByteArrayOutputStream()

    private fun iniciarGrabacion(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "Falta el permiso de micrófono."
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        return try {
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE_GRABACION,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) throw IllegalStateException("Parámetros de audio no soportados en este dispositivo")

            val recorder = android.media.AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_GRABACION,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException("No se pudo inicializar el micrófono")
            }

            bufferGrabacion.reset()
            grabacionActiva = true
            recorder.startRecording()
            audioRecord = recorder

            grabacionJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(2048)
                while (grabacionActiva) {
                    val leidos = recorder.read(buffer, 0, buffer.size)
                    if (leidos > 0) bufferGrabacion.write(buffer, 0, leidos)
                }
            }
            true
        } catch (e: Exception) {
            statusText.text = "No se pudo iniciar la grabación: ${e.message}"
            audioRecord = null
            false
        }
    }

    /**
     * Detiene la grabación en curso, espera a que el hilo de captura
     * termine de cerrar el AudioRecord (para no leer/soltar recursos a la
     * vez desde dos sitios), y empaqueta el audio capturado en un archivo
     * .wav. Es suspend porque necesita esperar (join) a ese hilo.
     */
    private suspend fun detenerGrabacionYObtenerArchivo(): File? {
        grabacionActiva = false
        grabacionJob?.join()
        grabacionJob = null

        val recorder = audioRecord
        audioRecord = null
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Puede lanzar si se detiene demasiado rápido tras iniciar; se ignora.
        }
        recorder?.release()

        val pcm = bufferGrabacion.toByteArray()
        // Menos de ~0.1s de audio: probablemente un toque accidental.
        if (pcm.size < 3200) return null

        return try {
            val archivo = File(cacheDir, "grabacion_voz.wav")
            escribirWav(archivo, pcm, SAMPLE_RATE_GRABACION)
            archivo
        } catch (e: Exception) {
            null
        }
    }

    /** Construye un archivo .wav (PCM 16-bit mono) a partir de las muestras en crudo. */
    private fun escribirWav(archivo: File, pcmData: ByteArray, sampleRate: Int) {
        val byteRate = sampleRate * 2 // 16-bit mono = 2 bytes por muestra
        val totalDataLen = pcmData.size + 36
        val header = ByteArray(44)

        fun putStr(offset: Int, s: String) {
            s.forEachIndexed { i, c -> header[offset + i] = c.code.toByte() }
        }
        fun putIntLE(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
            header[offset + 2] = ((value shr 16) and 0xff).toByte()
            header[offset + 3] = ((value shr 24) and 0xff).toByte()
        }
        fun putShortLE(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
        }

        putStr(0, "RIFF")
        putIntLE(4, totalDataLen)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putIntLE(16, 16)       // tamaño del sub-bloque fmt
        putShortLE(20, 1)      // formato PCM
        putShortLE(22, 1)      // 1 canal (mono)
        putIntLE(24, sampleRate)
        putIntLE(28, byteRate)
        putShortLE(32, 2)      // block align
        putShortLE(34, 16)     // bits por muestra
        putStr(36, "data")
        putIntLE(40, pcmData.size)

        archivo.outputStream().use { out ->
            out.write(header)
            out.write(pcmData)
        }
    }

    // ==================== TRADUCTOR: grabar y traducir ====================

    /** Toca un botón de idioma: si ya está grabando ese idioma, para y traduce; si no, empieza a grabar. */
    private fun onLangButtonClick(idioma: Idioma) {
        if (grabandoTraductor && idiomaGrabandoTraductor == idioma) {
            detenerGrabacionTraductor()
        } else if (!grabandoTraductor && !grabandoAsistente && !isBusy) {
            iniciarGrabacionTraductor(idioma)
        }
    }

    private fun iniciarGrabacionTraductor(idioma: Idioma) {
        if (!iniciarGrabacion()) return
        grabandoTraductor = true
        idiomaGrabandoTraductor = idioma
        val boton = if (idioma == langA) btnLangA else btnLangB
        boton.text = "🔴 Toca para parar"
        statusText.text = "Grabando en ${idioma.displayName}..."
    }

    private fun detenerGrabacionTraductor() {
        val idioma = idiomaGrabandoTraductor ?: return
        grabandoTraductor = false
        idiomaGrabandoTraductor = null
        updateLanguageButtons()

        isBusy = true
        progressBar.visibility = View.VISIBLE
        statusText.text = "Transcribiendo..."

        lifecycleScope.launch {
            val archivo = detenerGrabacionYObtenerArchivo()
            if (archivo == null) {
                isBusy = false
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.status_ready)
                return@launch
            }
            try {
                val otherIdioma = if (idioma == langA) langB else langA
                statusText.text = getString(R.string.translating)
                // Una sola llamada al modelo: transcribe y traduce a la vez.
                val resultado = translationEngine.transcribeAndTranslate(
                    archivo,
                    idioma.displayName.lowercase(),
                    otherIdioma.displayName.lowercase()
                )
                if (resultado != null) {
                    val (original, traduccion) = resultado
                    if (original.isBlank() || traduccion.isBlank()) {
                        isBusy = false
                        progressBar.visibility = View.GONE
                        statusText.text = getString(R.string.status_ready)
                        return@launch
                    }
                    addBubble(original, idioma)
                    addBubble(traduccion, otherIdioma)
                    speak(traduccion, otherIdioma)
                    isBusy = false
                    progressBar.visibility = View.GONE
                    statusText.text = getString(R.string.status_ready)
                    return@launch
                }
                // Plan B: el modelo no respetó el formato; método clásico en dos pasos.
                val texto = translationEngine.transcribe(archivo, idioma.displayName.lowercase())
                if (texto.isBlank()) {
                    isBusy = false
                    progressBar.visibility = View.GONE
                    statusText.text = getString(R.string.status_ready)
                    return@launch
                }
                translateAndShow(texto, idioma, otherIdioma)
            } catch (e: Exception) {
                isBusy = false
                progressBar.visibility = View.GONE
                statusText.text = "Error al transcribir: ${e.message}"
            }
        }
    }

    // ==================== ASISTENTE IA: grabar y dictar ====================

    private fun onMicAssistantClick() {
        if (grabandoAsistente) {
            detenerGrabacionAsistente()
        } else if (!grabandoTraductor && !grabandoAsistente && !isBusy) {
            iniciarGrabacionAsistente()
        }
    }

    private fun iniciarGrabacionAsistente() {
        if (!iniciarGrabacion()) return
        grabandoAsistente = true
        btnMic.text = "🔴"
        statusText.text = "Grabando en ${assistantVoiceLang.displayName}..."
    }

    private fun detenerGrabacionAsistente() {
        grabandoAsistente = false
        btnMic.text = "🎤"

        isBusy = true
        progressBar.visibility = View.VISIBLE
        statusText.text = "Transcribiendo..."

        lifecycleScope.launch {
            val archivo = detenerGrabacionYObtenerArchivo()
            if (archivo == null) {
                isBusy = false
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.status_ready)
                return@launch
            }
            try {
                val texto = translationEngine.transcribe(archivo, assistantVoiceLang.displayName.lowercase())
                isBusy = false
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.status_ready)
                if (texto.isNotBlank()) {
                    val actual = promptInput.text.toString()
                    val nuevoTexto = if (actual.isBlank()) texto else "$actual $texto"
                    promptInput.setText(nuevoTexto)
                    promptInput.setSelection(nuevoTexto.length)
                    // Envío automático: en cuanto termina de dictar, se manda
                    // la pregunta sin necesidad de tocar el botón "Enviar".
                    sendPrompt()
                }
            } catch (e: Exception) {
                isBusy = false
                progressBar.visibility = View.GONE
                statusText.text = "Error al transcribir: ${e.message}"
            }
        }
    }

    /** Desplegable de idioma de voz del Asistente IA (dictado + lectura). Por defecto Español. */
    private fun setupAssistantLanguageSpinner() {
        val nombres = Idioma.values().map { it.etiqueta }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, nombres)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        spinnerAssistantLang.adapter = adapter
        spinnerAssistantLang.setSelection(Idioma.ESPANOL.ordinal)

        spinnerAssistantLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                assistantVoiceLang = Idioma.values()[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Copia el archivo elegido (imagen, PDF o texto) a un archivo temporal
     * propio de la app, porque LiteRT-LM necesita rutas de archivo reales,
     * no content:// Uris. Si es PDF, rasteriza sus páginas a imágenes; si
     * es texto, lo lee como contexto para el prompt.
     */
    private fun handleAttachmentPicked(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: ""
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when {
                        mimeType.startsWith("image/") -> {
                            val imgFile = File(cacheDir, "attachment_image.jpg")
                            contentResolver.openInputStream(uri)?.use { input ->
                                imgFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            pendingImages = listOf(imgFile)
                            pendingTextContext = null
                            pendingAttachmentLabel = "📎 Imagen adjunta"
                        }
                        mimeType == "application/pdf" -> {
                            val pdfFile = File(cacheDir, "attachment.pdf")
                            contentResolver.openInputStream(uri)?.use { input ->
                                pdfFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            val paginas = rasterizePdf(pdfFile, maxPages = 5)
                            pendingImages = paginas
                            pendingTextContext = null
                            pendingAttachmentLabel = "📎 PDF adjunto (${paginas.size} página/s)"
                        }
                        mimeType == "text/plain" -> {
                            val texto = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                            pendingImages = emptyList()
                            pendingTextContext = texto
                            pendingAttachmentLabel = "📎 Documento de texto adjunto"
                        }
                        else -> {
                            pendingAttachmentLabel = null
                        }
                    }
                }
                txtAttachment.text = pendingAttachmentLabel
                txtAttachment.visibility = if (pendingAttachmentLabel != null) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No se pudo leer el archivo: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Convierte cada página de un PDF en una imagen JPEG, usando el
     * PdfRenderer del propio Android (sin librerías externas). Se limita
     * a maxPages para no disparar el tiempo de proceso ni la memoria en
     * documentos largos.
     */
    private fun rasterizePdf(pdfFile: File, maxPages: Int): List<File> {
        val resultado = mutableListOf<File>()
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { renderer ->
                val totalPaginas = minOf(renderer.pageCount, maxPages)
                for (i in 0 until totalPaginas) {
                    renderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val outFile = File(cacheDir, "pdf_page_$i.jpg")
                        outFile.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                        bitmap.recycle()
                        resultado.add(outFile)
                    }
                }
            }
        } finally {
            pfd.close()
        }
        return resultado
    }

    /** Envía el prompt del Asistente IA (con el adjunto pendiente, si lo hay). */
    private fun sendPrompt() {
        if (isBusy || !modelReady) return
        val texto = promptInput.text.toString().trim()
        if (texto.isBlank() && pendingImages.isEmpty() && pendingTextContext == null) return

        val etiquetaUsuario = if (pendingAttachmentLabel != null) {
            "$texto\n$pendingAttachmentLabel".trim()
        } else {
            texto
        }
        addAssistantBubble(etiquetaUsuario.ifBlank { pendingAttachmentLabel ?: "" }, alignLeft = false, colorHex = "#607D8B")

        val contextoPrevio = pendingAssistantContext?.takeLast(1500)
        val promptFinal = when {
            contextoPrevio != null && pendingTextContext != null ->
                "Esta es la conversación que tuvimos antes de cerrar la app; tenla en cuenta para entender referencias a lo ya hablado:\n\n$contextoPrevio\n\nContenido del documento adjunto:\n\n$pendingTextContext\n\nPregunta del usuario: $texto"
            contextoPrevio != null ->
                "Esta es la conversación que tuvimos antes de cerrar la app; tenla en cuenta para entender referencias a lo ya hablado:\n\n$contextoPrevio\n\nPregunta del usuario: $texto"
            pendingTextContext != null ->
                "Contenido del documento adjunto:\n\n$pendingTextContext\n\nPregunta del usuario: $texto"
            else -> texto
        }
        // Solo hace falta inyectar la conversación anterior una vez: a partir de
        // aquí la Conversation en memoria del motor ya mantiene el hilo sola.
        pendingAssistantContext = null
        val imagenesParaEnviar = pendingImages
        val contextoDocumento = pendingTextContext
        val leerRespuesta = chkSpeakResponses.isChecked

        promptInput.setText("")
        pendingImages = emptyList()
        pendingTextContext = null
        pendingAttachmentLabel = null
        txtAttachment.visibility = View.GONE

        isBusy = true
        progressBar.visibility = View.VISIBLE
        btnSendPrompt.isEnabled = false

        val buscarEnInternet = chkWebSearch.isChecked && imagenesParaEnviar.isEmpty() &&
            texto.length >= 8 && contextoDocumento == null

        lifecycleScope.launch {
            try {
                var promptConBusqueda = promptFinal
                var huboBusqueda = false
                var textoFuentes = ""
                var titularesFuentes = ""
                val preguntaPrevia = ultimaPreguntaAsistente
                ultimaPreguntaAsistente = texto
                var fuentes = ""
                var avisoSinRed = ""
                if (buscarEnInternet) {
                    val estadoPrevio = statusText.text
                    statusText.text = getString(R.string.status_searching)
                    try {
                        // Las palabras clave se sacan sin el modelo (es lento y a veces se niega a buscar).
                        val claves = BuscadorWeb.palabrasClave(texto)
                        // Pregunta corta o orden de buscar ("búscala por internet"): se suma el tema
                        // anterior. Si así no hay resultados, se reintenta solo con la pregunta nueva.
                        val esOrden = Regex("b[uú]sca(la|lo|me)?\\b|internet|mira en|consulta", RegexOption.IGNORE_CASE).containsMatchIn(texto) && texto.length < 60
                        val pocasClaves = claves.split(" ").count { it.lowercase() != "resultado" && it.lowercase() != "goles" } <= 3
                        val esSeguimiento = ultimaConsultaUsada != null && (esOrden || (texto.length < 60 && pocasClaves))
                        val reciente = Regex("ayer|hoy|anoche|esta semana|últim|reciente|ahora mismo|actual", RegexOption.IGNORE_CASE).containsMatchIn(texto) ||
                            (esSeguimiento && ultimaReciente)
                        val consulta = when {
                            esOrden -> ultimaConsultaUsada!!
                            esSeguimiento -> (ultimaConsultaUsada!!.split(" ").filter { it.lowercase() != "resultado" } + claves.split(" ")).distinctBy { it.lowercase() }.joinToString(" ").take(100)
                            else -> claves
                        }
                        val localeBusqueda = assistantVoiceLang.speechLocale
                        var usada = consulta
                        var busqueda = BuscadorWeb.buscar(consulta, localeBusqueda, reciente)
                        if (busqueda == null && consulta != claves) {
                            // Segundo intento solo con las palabras de la pregunta nueva.
                            usada = claves
                            busqueda = BuscadorWeb.buscar(usada, localeBusqueda, reciente)
                        }
                        if (busqueda != null) {
                            promptConBusqueda = busqueda.contexto + "\n\n" + promptFinal
                            huboBusqueda = true
                            textoFuentes = busqueda.contexto
                            titularesFuentes = busqueda.fuentes
                            if (!esSeguimiento || usada == claves) ultimaConsultaUsada = usada
                            ultimaReciente = reciente
                            Log.d("SimulTrans", "Búsqueda «$usada» [v$versionApp · ${busqueda.diag}]\n${busqueda.fuentes}")
                        } else {
                            Log.d("SimulTrans", "Búsqueda «$usada» sin resultados: ${BuscadorWeb.ultimoError}")
                            avisoSinRed = "\n\n(No he encontrado nada en Internet sobre esto; respondo con lo que sé, que puede estar desactualizado.)"
                        }
                    } catch (e: Exception) {
                        Log.d("SimulTrans", "Búsqueda fallida: ${e.javaClass.simpleName} ${e.message}")
                        avisoSinRed = "\n\n(No he podido consultar Internet; respondo con lo que sé.)"
                    }
                    statusText.text = estadoPrevio
                }
                var (respuesta, reiniciada) = responderAsistente(
                    promptConBusqueda, imagenesParaEnviar, huboBusqueda, contextoPrevio != null
                )
                if (huboBusqueda) {
                    // Control de calidad: lo que diga la respuesta tiene que estar en los textos encontrados.
                    var dudosos = datosNoRespaldados(respuesta, "$textoFuentes $texto")
                    if (dudosos.isNotEmpty()) {
                        Log.d("SimulTrans", "Datos no respaldados: $dudosos; reintento")
                        respuesta = translationEngine.askOneShot(
                            promptConBusqueda + "\n\nATENCIÓN: una respuesta anterior tuya incluía datos que NO aparecen en los textos (" +
                                dudosos.joinToString(", ") + "). Responde de nuevo usando únicamente datos que aparezcan " +
                                "literalmente en los textos. Si los textos no contienen la respuesta, di que no la has encontrado.",
                            emptyList()
                        )
                        translationEngine.resetAssistantConversation()
                        reiniciada = true
                        dudosos = datosNoRespaldados(respuesta, "$textoFuentes $texto")
                        val hayNumero = dudosos.any { d -> d.all { it.isDigit() } }
                        if (dudosos.isNotEmpty() && (hayNumero || dudosos.size >= 2)) {
                            respuesta = "No estoy seguro de la respuesta: los textos que he encontrado no la confirman con claridad. " +
                                "Esto es lo que dicen los titulares:\n" + titularesFuentes.lines().take(4).joinToString("\n")
                        }
                    }
                }
                addAssistantBubble(respuesta + fuentes + avisoSinRed, alignLeft = true, colorHex = "#4A90D9")
                // La conversación viva se descartó: el próximo mensaje recibe un resumen de lo hablado.
                if (reiniciada) pendingAssistantContext = resumenReciente(excluirUltima = false)
                if (leerRespuesta) {
                    speak(respuesta, assistantVoiceLang)
                }
            } catch (e: Exception) {
                addAssistantBubble("(Error: ${e.message})", alignLeft = true, colorHex = "#4A90D9")
            } finally {
                isBusy = false
                progressBar.visibility = View.GONE
                btnSendPrompt.isEnabled = modelReady
            }
        }
    }

    /**
     * Responde en la conversación viva del asistente, salvo que el mensaje sea muy largo
     * (resultados de Internet) o la memoria del modelo se haya llenado: entonces usa una
     * conversación puntual con un resumen de lo hablado y descarta la viva.
     * Devuelve la respuesta y si la conversación viva se ha reiniciado.
     */
    private suspend fun responderAsistente(
        prompt: String,
        imagenes: List<File>,
        conBusqueda: Boolean,
        yaTieneContexto: Boolean,
    ): Pair<String, Boolean> {
        suspend fun puntual(): Pair<String, Boolean> {
            val resumen = if (yaTieneContexto) "" else resumenReciente(excluirUltima = true)
            val completo = if (resumen.isNotBlank()) "Conversación reciente:\n$resumen\n\n$prompt" else prompt
            val r = translationEngine.askOneShot(completo, imagenes)
            translationEngine.resetAssistantConversation()
            return r to true
        }
        if (conBusqueda) return puntual()
        return try {
            translationEngine.askWithContext(prompt, imagenes) to false
        } catch (e: Exception) {
            val m = e.message ?: ""
            if (m.contains("Prefill") || m.contains("FAILED_PRECONDITION") || m.contains("capacity")) puntual() else throw e
        }
    }

    private fun normalizarTexto(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    /**
     * Datos de la respuesta (cifras y nombres propios) que no aparecen en los textos de los
     * que debía salir: señal de que el modelo se los ha inventado o los ha mezclado.
     */
    private fun datosNoRespaldados(respuesta: String, fuente: String): List<String> {
        val f = normalizarTexto(fuente)
        val malos = mutableListOf<String>()
        Regex("\\d{2,}").findAll(respuesta).forEach { if (!f.contains(it.value)) malos.add(it.value) }
        Regex("(?<=[a-záéíóúñ0-9,;:] )([A-ZÁÉÍÓÚÑ][\\p{L}]{2,})").findAll(respuesta).forEach { m ->
            if (!f.contains(normalizarTexto(m.groupValues[1]))) malos.add(m.groupValues[1])
        }
        return malos.distinct()
    }

    /** Últimos mensajes del historial del asistente, recortados, para dar contexto sin llenar la memoria. */
    private fun resumenReciente(excluirUltima: Boolean): String {
        val json = prefs.getString(KEY_ASSISTANT_HISTORY, null) ?: return ""
        return try {
            val arr = JSONArray(json)
            val fin = if (excluirUltima) arr.length() - 1 else arr.length()
            val sb = StringBuilder()
            for (i in maxOf(0, fin - 6) until fin) {
                val o = arr.getJSONObject(i)
                val quien = if (o.getBoolean("left")) "Asistente" else "Usuario"
                sb.append(quien).append(": ").append(o.getString("text").take(300)).append("\n")
            }
            sb.toString().trim()
        } catch (e: Exception) { "" }
    }

    /** Burbuja de la conversación del Asistente IA (independiente de la del Traductor). */
    private fun addAssistantBubble(text: String, alignLeft: Boolean, colorHex: String) {
        if (text.isBlank()) return
        val bubble = createBubbleView(text, colorHex, alignLeft)
        assistantTranscript.addView(bubble)
        assistantScrollView.post { assistantScrollView.fullScroll(View.FOCUS_DOWN) }
        saveAssistantHistoryEntry(text, colorHex, alignLeft)
    }

    /** Añade una entrada al JSON del historial del Asistente IA en SharedPreferences. */
    private fun saveAssistantHistoryEntry(text: String, colorHex: String, alignLeft: Boolean) {
        val existing = prefs.getString(KEY_ASSISTANT_HISTORY, null)
        val arr = if (existing != null) JSONArray(existing) else JSONArray()
        val obj = JSONObject()
        obj.put("text", text)
        obj.put("color", colorHex)
        obj.put("left", alignLeft)
        arr.put(obj)
        prefs.edit().putString(KEY_ASSISTANT_HISTORY, arr.toString()).apply()
    }

    /**
     * Reconstruye las burbujas guardadas del Asistente IA al abrir la app, y
     * además guarda esa conversación en [pendingAssistantContext] para que,
     * en cuanto el usuario escriba algo nuevo, se le pase al modelo como
     * contexto (ver [sendPrompt]).
     */
    private fun restoreAssistantHistory() {
        val json = prefs.getString(KEY_ASSISTANT_HISTORY, null) ?: return
        try {
            val arr = JSONArray(json)
            val transcripcion = StringBuilder()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val text = obj.getString("text")
                val colorHex = obj.getString("color")
                val alignLeft = obj.getBoolean("left")
                assistantTranscript.addView(createBubbleView(text, colorHex, alignLeft))
                val quienHabla = if (alignLeft) "Asistente" else "Usuario"
                transcripcion.append("$quienHabla: $text\n")
            }
            assistantScrollView.post { assistantScrollView.fullScroll(View.FOCUS_DOWN) }
            if (transcripcion.isNotBlank()) {
                pendingAssistantContext = transcripcion.toString().trim()
            }
        } catch (e: Exception) {
            // Historial corrupto o de una versión anterior: se ignora y se empieza de cero.
        }
    }

    /** Borra la conversación del Asistente IA de la pantalla, del almacenamiento guardado
     *  y del contexto que recordaba el modelo, para que el siguiente mensaje empiece de cero. */
    private fun clearAssistantHistory() {
        assistantTranscript.removeAllViews()
        prefs.edit().remove(KEY_ASSISTANT_HISTORY).apply()
        pendingAssistantContext = null
        lifecycleScope.launch { translationEngine.resetAssistantConversation() }
        Toast.makeText(this, "Conversación borrada", Toast.LENGTH_SHORT).show()
    }

    /**
     * Configura los dos desplegables de idioma del Traductor. Por defecto:
     * Español en A, Inglés en B. Si el usuario elige el mismo idioma en
     * los dos, se avisa y se deshabilitan los botones.
     */
    private fun setupLanguageSpinners() {
        val nombres = Idioma.values().map { it.etiqueta }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, nombres)
        adapter.setDropDownViewResource(R.layout.spinner_item)
        spinnerLangA.adapter = adapter
        spinnerLangB.adapter = adapter

        spinnerLangA.setSelection(Idioma.ESPANOL.ordinal)
        spinnerLangB.setSelection(Idioma.INGLES.ordinal)

        spinnerLangA.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                langA = Idioma.values()[position]
                updateLanguageButtons()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerLangB.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                langB = Idioma.values()[position]
                updateLanguageButtons()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Actualiza el texto y color de los botones según langA/langB. */
    private fun updateLanguageButtons() {
        btnLangA.text = "Hablar en ${langA.etiqueta}"
        btnLangB.text = "Hablar en ${langB.etiqueta}"
        btnLangA.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(langA.colorHex))
        btnLangB.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(langB.colorHex))

        val mismoIdioma = langA == langB
        if (mismoIdioma) {
            statusText.text = "Elige dos idiomas distintos para conversar."
        } else if (modelReady) {
            statusText.text = getString(R.string.status_ready)
        }
        btnLangA.isEnabled = modelReady && !mismoIdioma
        btnLangB.isEnabled = modelReady && !mismoIdioma
    }

    /**
     * Copia el archivo .litertlm elegido al almacenamiento privado de la
     * app. Se copia (en vez de leer el Uri directamente) porque LiteRT-LM
     * necesita una ruta de archivo real, no un content:// Uri.
     */
    private fun copyModelFromUri(uri: Uri) {
        btnPickModel.isEnabled = false
        statusText.text = getString(R.string.status_copying_model)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                        }
                    } ?: throw IllegalStateException("No se pudo abrir el archivo elegido")
                }
                loadModel(translationEngine)
            } catch (e: Exception) {
                statusText.text = "Error al copiar el modelo: ${e.message}"
                btnPickModel.isEnabled = true
            }
        }
    }

    /** Botón "Descargar e instalar": inicia la descarga, o la cancela si ya está en curso. */
    private fun onDescargarModeloClick() {
        if (descargaJob?.isActive == true) {
            descargaJob?.cancel()
            return
        }
        val reanudando = ModelDownloader.parcial(modelFile).exists()
        val libre = filesDir.usableSpace
        if (libre < ModelDownloader.ESPACIO_NECESARIO) {
            AlertDialog.Builder(this)
                .setTitle("Espacio insuficiente")
                .setMessage("Hace falta al menos 3,5 GB libres y ahora hay ${"%.1f".format(libre / 1e9)} GB. Libera espacio y vuelve a intentarlo.")
                .setPositiveButton("Entendido", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (reanudando) "Continuar la descarga" else "Descargar el modelo")
            .setMessage("Se descargarán 2,6 GB. Mejor con Wi-Fi. Mantén la app abierta hasta que termine; al acabar se activa sola.")
            .setPositiveButton("Descargar") { _, _ -> empezarDescarga() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun empezarDescarga() {
        btnPickModel.isEnabled = false
        btnDownloadModel.text = getString(R.string.btn_cancel_download)
        progressDownload.progress = 0
        progressDownload.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusText.text = "Descargando el modelo…"

        descargaJob = lifecycleScope.launch {
            try {
                ModelDownloader.descargar(modelFile) { hecho, total ->
                    runOnUiThread {
                        if (total > 0) {
                            progressDownload.progress = (hecho * 1000 / total).toInt()
                            statusText.text = "Descargando el modelo… ${hecho * 100 / total} % " +
                                "(${"%.1f".format(hecho / 1e9)} de ${"%.1f".format(total / 1e9)} GB)"
                        } else {
                            statusText.text = "Descargando el modelo… ${"%.1f".format(hecho / 1e9)} GB"
                        }
                    }
                }
                finalizarDescarga()
                loadModel(translationEngine)
            } catch (e: CancellationException) {
                finalizarDescarga()
                statusText.text = "Descarga pausada. Pulsa de nuevo para continuar donde se quedó."
                throw e
            } catch (e: Exception) {
                finalizarDescarga()
                statusText.text = "No se pudo completar la descarga: ${e.message}\nPulsa de nuevo para continuar donde se quedó."
            }
        }
    }

    private fun finalizarDescarga() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressDownload.visibility = View.GONE
        btnDownloadModel.text = getString(R.string.btn_download_model)
        btnPickModel.isEnabled = true
    }

    private fun ensureMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadModel(engine: TranslationEngine) {
        if (!engine.isModelPresent) {
            statusText.text = getString(R.string.status_model_missing)
            btnPickModel.isEnabled = true
            btnPickModel.visibility = View.VISIBLE
            btnDownloadModel.visibility = View.VISIBLE
            return
        }
        btnPickModel.visibility = View.GONE
        btnDownloadModel.visibility = View.GONE
        statusText.text = getString(R.string.status_loading)
        lifecycleScope.launch {
            try {
                engine.initialize()
                modelReady = true
                updateLanguageButtons()
                btnSendPrompt.isEnabled = true
            } catch (e: Exception) {
                statusText.text = "Error al cargar el modelo: ${e.message}"
                btnPickModel.visibility = View.VISIBLE
                btnPickModel.isEnabled = true
                btnDownloadModel.visibility = View.VISIBLE
            }
        }
    }

    private fun translateAndShow(spokenText: String, fromIdioma: Idioma, toIdioma: Idioma) {
        statusText.text = getString(R.string.translating)
        progressBar.visibility = View.VISIBLE
        addBubble(spokenText, fromIdioma)

        lifecycleScope.launch {
            try {
                val translated = translationEngine.translate(
                    spokenText,
                    fromIdioma.displayName.lowercase(),
                    toIdioma.displayName.lowercase()
                )
                addBubble(translated, toIdioma)
                speak(translated, toIdioma)
            } catch (e: Exception) {
                addBubble("(Error al traducir: ${e.message})", toIdioma)
            } finally {
                isBusy = false
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.status_ready)
            }
        }
    }

    /**
     * Quita símbolos que los motores de TTS suelen pronunciar en voz alta
     * de forma literal (p. ej. "asterisco", "guion", "almohadilla") y que
     * no aportan nada al oírlos: asteriscos, guiones bajos, almohadillas,
     * tildes de markdown, comillas invertidas, barras verticales, viñetas
     * al principio de línea y secuencias largas de puntos o guiones (como
     * "..." o "---"). La puntuación normal (. , ; : ? !) se conserva, ya
     * que el TTS la usa para pausas y entonación sin pronunciarla.
     */
    private fun limpiarTextoParaVoz(texto: String): String {
        return texto
            .replace(Regex("^[\\-•*]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("[*_#`~|]"), "")
            .replace(Regex("-{2,}"), " ")
            .replace(Regex("\\.{2,}"), ".")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    /**
     * Habla un texto en el idioma indicado. Se comprueba explícitamente si
     * el motor de TTS tiene instalado el paquete de voz de ese idioma. Si
     * falta, se avisa y se lleva al usuario a instalarlo. La usan tanto el
     * Traductor como (opcionalmente) el Asistente IA.
     */
    private fun speak(text: String, idioma: Idioma) {
        val result = tts.setLanguage(idioma.ttsLocale)
        val faltaPaquete = result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED

        if (faltaPaquete) {
            statusText.text = "Falta la voz de ${idioma.displayName} en este móvil. Abriendo instalación..."
            try {
                val installIntent = android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                startActivity(installIntent)
            } catch (e: Exception) {
                statusText.text = "Instala la voz de ${idioma.displayName} desde Ajustes > Accesibilidad > Conversión de texto a voz."
            }
            return
        }

        tts.speak(limpiarTextoParaVoz(text), TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /**
     * Crea la vista de una burbuja de conversación, coloreada según
     * colorHex y alineada a la izquierda o derecha según alignLeft.
     * Tocarla copia su texto al portapapeles.
     */
    private fun createBubbleView(text: String, colorHex: String, alignLeft: Boolean): TextView {
        val density = resources.displayMetrics.density
        val baseColor = Color.parseColor(colorHex)
        val bubbleColor = ColorUtils.blendARGB(Color.WHITE, baseColor, 0.15f)
        val background = GradientDrawable().apply {
            setColor(bubbleColor)
            cornerRadius = 16f * density
        }

        return TextView(this).apply {
            this.text = text
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            this.background = background
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (alignLeft) Gravity.START else Gravity.END
                setMargins((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Texto", text))
                Toast.makeText(this@MainActivity, "Copiado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Añade una burbuja nueva a la conversación del Traductor y la guarda en el historial. */
    private fun addBubble(text: String, idioma: Idioma) {
        val alignLeft = idioma == langA
        val bubble = createBubbleView(text, idioma.colorHex, alignLeft)
        transcriptContainer.addView(bubble)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        saveHistoryEntry(text, idioma, alignLeft)
    }

    /** Añade una entrada al JSON guardado en SharedPreferences. */
    private fun saveHistoryEntry(text: String, idioma: Idioma, alignLeft: Boolean) {
        val existing = prefs.getString(KEY_HISTORY, null)
        val arr = if (existing != null) JSONArray(existing) else JSONArray()
        val obj = JSONObject()
        obj.put("idioma", idioma.name)
        obj.put("text", text)
        obj.put("left", alignLeft)
        arr.put(obj)
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /** Reconstruye las burbujas guardadas del Traductor al abrir la app (o tras girar la pantalla). */
    private fun restoreHistory() {
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val idioma = Idioma.valueOf(obj.getString("idioma"))
                val text = obj.getString("text")
                val alignLeft = obj.getBoolean("left")
                transcriptContainer.addView(createBubbleView(text, idioma.colorHex, alignLeft))
            }
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        } catch (e: Exception) {
            // Historial corrupto o de una versión anterior: se ignora y se empieza de cero.
        }
    }

    /** Borra la conversación del Traductor de la pantalla y del almacenamiento guardado. */
    private fun clearHistory() {
        transcriptContainer.removeAllViews()
        prefs.edit().remove(KEY_HISTORY).apply()
        Toast.makeText(this, "Conversación borrada", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        grabacionActiva = false
        audioRecord?.release()
        tts.shutdown()
        translationEngine.close()
    }

    companion object {
        private const val SAMPLE_RATE_GRABACION = 16000

        // Enlace directo (Hugging Face, repo público Apache 2.0, 2,59 GB).
        private const val KEY_HISTORY = "transcript_history"
        private const val KEY_ASSISTANT_HISTORY = "assistant_history"
        private const val KEY_LAST_CRASH = "last_crash"
    }
}
