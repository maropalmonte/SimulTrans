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
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * App de traducción simultánea multi-idioma + asistente IA con prompt
 * libre, usando Gemma 4 E2B a través de LiteRT-LM, 100% on-device.
 *
 * Tiene 2 pestañas:
 * - "Traductor": la funcionalidad original (2 idiomas, botones de hablar).
 * - "Asistente IA": prompt de texto libre, con opción de adjuntar una
 *   imagen, un PDF (se rasteriza página a página y se envía como
 *   imágenes) o un .txt (su contenido se añade al prompt como contexto).
 *
 * CAPTURA DE ERRORES: si la app se cierra por un fallo inesperado, el
 * stack trace se guarda en SharedPreferences (ver installCrashHandler /
 * mostrarUltimoCrashSiExiste). La próxima vez que se abra la app, se
 * muestra en un diálogo con botón "Copiar" — pensado para poder depurar
 * sin ordenador ni adb, copiando el error directamente desde el móvil.
 *
 * El modelo NO se incluye en el APK (pesa varios GB). Se descarga con el
 * navegador del propio móvil desde Hugging Face y se selecciona dentro de
 * la app con el botón "Elegir archivo del modelo" (selector de documentos
 * del sistema) — no hace falta ordenador, cable ni adb.
 *
 * Descarga el archivo .litertlm (tras aceptar la licencia de Gemma) desde:
 *   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
 */

/**
 * Idiomas soportados. displayName se usa tanto en la UI como en el prompt
 * de traducción (ej. "traduce de español a inglés"). speechLocale es el
 * código que espera SpeechRecognizer; ttsLocale es el Locale que espera
 * TextToSpeech. colorHex se usa para el botón y, en versión clara, para
 * el fondo de la burbuja de ese idioma.
 */
enum class Idioma(
    val displayName: String,
    val speechLocale: String,
    val ttsLocale: Locale,
    val colorHex: String
) {
    ESPANOL("Español", "es-ES", Locale("es", "ES"), "#C60B1E"),
    INGLES("Inglés", "en-US", Locale.US, "#00247D"),
    FRANCES("Francés", "fr-FR", Locale.FRANCE, "#0055A4"),
    ITALIANO("Italiano", "it-IT", Locale.ITALY, "#008C45"),
    CHINO("Chino", "zh-CN", Locale.SIMPLIFIED_CHINESE, "#DE2910"),
    TURCO("Turco", "tr-TR", Locale("tr", "TR"), "#E30A17"),
    ARABE("Árabe", "ar-SA", Locale("ar", "SA"), "#006C35"),
    ALEMAN("Alemán", "de-DE", Locale.GERMANY, "#FFCE00");

    override fun toString(): String = displayName
}

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var spinnerLangA: Spinner
    private lateinit var spinnerLangB: Spinner
    private lateinit var btnLangA: Button
    private lateinit var btnLangB: Button
    private lateinit var btnPickModel: Button
    private lateinit var btnClearHistory: TextView

    private lateinit var tabTranslator: Button
    private lateinit var tabAssistant: Button
    private lateinit var translatorContainer: LinearLayout
    private lateinit var assistantContainer: LinearLayout
    private lateinit var assistantTranscript: LinearLayout
    private lateinit var assistantScrollView: ScrollView
    private lateinit var promptInput: EditText
    private lateinit var btnAttach: Button
    private lateinit var btnSendPrompt: Button
    private lateinit var txtAttachment: TextView

    private lateinit var translationEngine: TranslationEngine
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var modelFile: File
    private lateinit var prefs: SharedPreferences
    // Pareja de idiomas activa en la conversación. Por defecto Español/Inglés.
    private var langA: Idioma = Idioma.ESPANOL
    private var langB: Idioma = Idioma.INGLES

    private var isBusy = false
    private var modelReady = false

    // Adjunto pendiente de enviar en la pestaña Asistente IA.
    private var pendingImages: List<File> = emptyList()
    private var pendingTextContext: String? = null
    private var pendingAttachmentLabel: String? = null

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

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        transcriptContainer = findViewById(R.id.transcriptContainer)
        scrollView = findViewById(R.id.scrollView)
        spinnerLangA = findViewById(R.id.spinnerLangA)
        spinnerLangB = findViewById(R.id.spinnerLangB)
        btnLangA = findViewById(R.id.btnLangA)
        btnLangB = findViewById(R.id.btnLangB)
        btnPickModel = findViewById(R.id.btnPickModel)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        tabTranslator = findViewById(R.id.tabTranslator)
        tabAssistant = findViewById(R.id.tabAssistant)
        translatorContainer = findViewById(R.id.translatorContainer)
        assistantContainer = findViewById(R.id.assistantContainer)
        assistantTranscript = findViewById(R.id.assistantTranscript)
        assistantScrollView = findViewById(R.id.assistantScrollView)
        promptInput = findViewById(R.id.promptInput)
        btnAttach = findViewById(R.id.btnAttach)
        btnSendPrompt = findViewById(R.id.btnSendPrompt)
        txtAttachment = findViewById(R.id.txtAttachment)

        ensureMicPermission()
        setupLanguageSpinners()
        updateLanguageButtons()
        restoreHistory()
        setupTabs()

        btnClearHistory.setOnClickListener { clearHistory() }
        btnAttach.setOnClickListener {
            pickAttachment.launch(arrayOf("image/*", "application/pdf", "text/plain"))
        }
        btnSendPrompt.setOnClickListener { sendPrompt() }

        tts = TextToSpeech(this) { }

        val modelDir = File(filesDir, "models")
        modelDir.mkdirs()
        modelFile = File(modelDir, "gemma-4-E2B-it.litertlm")
        translationEngine = TranslationEngine(modelFile)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        btnLangA.setOnClickListener { startListening(langA) }
        btnLangB.setOnClickListener { startListening(langB) }
        btnPickModel.setOnClickListener {
            // "*/*" porque .litertlm no tiene un tipo MIME reconocido por Android
            pickModelFile.launch(arrayOf("*/*"))
        }

        loadModel(translationEngine)
    }

    /**
     * Instala un manejador global de errores no capturados: guarda el
     * stack trace en SharedPreferences antes de que la app se cierre, para
     * poder mostrarlo la próxima vez que se abra (ver
     * mostrarUltimoCrashSiExiste). Debe llamarse ANTES de super.onCreate()
     * para capturar también fallos muy tempranos (inflado de layout, etc).
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
                // Si ni siquiera se puede guardar el error, no hacemos nada más:
                // seguimos con el cierre normal para no dejar la app colgada.
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
     * "Copiar" que lo pone en el portapapeles. Se borra tras mostrarlo,
     * para no repetirlo en el siguiente arranque.
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
            // Si ni el propio diálogo se puede mostrar, al menos avisamos con un Toast breve.
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

        val promptFinal = if (pendingTextContext != null) {
            "Contenido del documento adjunto:\n\n$pendingTextContext\n\nPregunta del usuario: $texto"
        } else {
            texto
        }
        val imagenesParaEnviar = pendingImages
        promptInput.setText("")
        pendingImages = emptyList()
        pendingTextContext = null
        pendingAttachmentLabel = null
        txtAttachment.visibility = View.GONE

        isBusy = true
        progressBar.visibility = View.VISIBLE
        btnSendPrompt.isEnabled = false

        lifecycleScope.launch {
            try {
                val respuesta = translationEngine.ask(promptFinal, imagenesParaEnviar)
                addAssistantBubble(respuesta, alignLeft = true, colorHex = "#4A90D9")
            } catch (e: Exception) {
                addAssistantBubble("(Error: ${e.message})", alignLeft = true, colorHex = "#4A90D9")
            } finally {
                isBusy = false
                progressBar.visibility = View.GONE
                btnSendPrompt.isEnabled = modelReady
            }
        }
    }

    /** Burbuja de la conversación del Asistente IA (independiente de la del Traductor). */
    private fun addAssistantBubble(text: String, alignLeft: Boolean, colorHex: String) {
        if (text.isBlank()) return
        val bubble = createBubbleView(text, colorHex, alignLeft)
        assistantTranscript.addView(bubble)
        assistantScrollView.post { assistantScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Configura los dos desplegables de idioma. Por defecto: Español en A,
     * Inglés en B. Si el usuario elige el mismo idioma en los dos, se
     * avisa y se deshabilitan los botones hasta que elija dos distintos.
     */
    private fun setupLanguageSpinners() {
        val nombres = Idioma.values().map { it.displayName }
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
        btnLangA.text = "Hablar en ${langA.displayName}"
        btnLangB.text = "Hablar en ${langB.displayName}"
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
     * Copia el archivo .litertlm elegido en el selector de documentos al
     * almacenamiento privado de la app. Se copia (en vez de leer el Uri
     * directamente) porque LiteRT-LM necesita una ruta de archivo real, no
     * un content:// Uri, y porque así el modelo persiste aunque el usuario
     * borre el archivo original de Descargas.
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
            return
        }
        btnPickModel.visibility = View.GONE
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
            }
        }
    }

    private fun startListening(idioma: Idioma) {
        if (isBusy) return
        isBusy = true
        statusText.text = getString(R.string.listening)

        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma.speechLocale)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spokenText = matches?.firstOrNull()
                if (spokenText.isNullOrBlank()) {
                    isBusy = false
                    statusText.text = getString(R.string.status_ready)
                    return
                }
                val otherIdioma = if (idioma == langA) langB else langA
                translateAndShow(spokenText, idioma, otherIdioma)
            }

            override fun onError(error: Int) {
                isBusy = false
                statusText.text = getString(R.string.status_ready)
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer.startListening(intent)
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
     * Habla el texto traducido en el idioma indicado. Se comprueba
     * explícitamente si el motor de TTS tiene instalado el paquete de voz
     * de ese idioma. Si falta, se avisa y se lleva al usuario a
     * instalarlo, en vez de fallar en silencio.
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

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /**
     * Crea la vista de una burbuja de conversación, coloreada según
     * colorHex y alineada a la izquierda o derecha según alignLeft.
     * Tocarla copia su texto al portapapeles. La usan tanto la pestaña
     * Traductor (colores por idioma) como el Asistente IA (colores fijos
     * para "tú" y "asistente"). No la añade ni la guarda por sí misma.
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
        speechRecognizer.destroy()
        tts.shutdown()
        translationEngine.close()
    }

    companion object {
        private const val KEY_HISTORY = "transcript_history"
        private const val KEY_LAST_CRASH = "last_crash"
    }
}