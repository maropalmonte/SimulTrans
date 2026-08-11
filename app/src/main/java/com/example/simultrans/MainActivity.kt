package com.example.simultrans

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
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

/**
 * App de traducción simultánea multi-idioma usando Gemma 4 E2B
 * a través de LiteRT-LM, 100% on-device.
 *
 * El usuario elige 2 idiomas (Idioma A / Idioma B) para la conversación
 * actual con los desplegables; por defecto Español <-> Inglés. Cada botón
 * grande representa uno de los dos idiomas elegidos: se pulsa, se habla,
 * y se traduce automáticamente al otro idioma de la pareja.
 *
 * La conversación se guarda en SharedPreferences y se restaura al volver
 * a abrir la app (o al girar la pantalla). Se puede borrar con el enlace
 * "Borrar conversación". Tocar una burbuja copia su texto al portapapeles.
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

    private val pickModelFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) copyModelFromUri(uri)
    }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            statusText.text = "Se necesita permiso de micrófono para funcionar."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        prefs = getSharedPreferences("simultrans_prefs", MODE_PRIVATE)

        ensureMicPermission()
        setupLanguageSpinners()
        updateLanguageButtons()
        restoreHistory()

        btnClearHistory.setOnClickListener { clearHistory() }

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
     * de ese idioma (típico que falte en italiano, turco, árabe o alemán
     * en dispositivos que solo traen español e inglés de fábrica). Si
     * falta, se avisa y se lleva al usuario a instalarlo, en vez de fallar
     * en silencio.
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
     * Crea la vista de una burbuja de conversación, coloreada según el
     * idioma y alineada a la izquierda o derecha según alignLeft. Tocarla
     * copia su texto al portapapeles. No la añade ni la guarda por sí
     * misma — eso lo hacen addBubble() (mensaje nuevo) y restoreHistory()
     * (mensajes ya guardados).
     */
    private fun createBubbleView(text: String, idioma: Idioma, alignLeft: Boolean): TextView {
        val density = resources.displayMetrics.density
        val baseColor = Color.parseColor(idioma.colorHex)
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
                clipboard.setPrimaryClip(ClipData.newPlainText("Traducción", text))
                Toast.makeText(this@MainActivity, "Copiado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Añade una burbuja nueva a la conversación y la guarda en el historial. */
    private fun addBubble(text: String, idioma: Idioma) {
        val alignLeft = idioma == langA
        val bubble = createBubbleView(text, idioma, alignLeft)
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

    /** Reconstruye las burbujas guardadas al abrir la app (o tras girar la pantalla). */
    private fun restoreHistory() {
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val idioma = Idioma.valueOf(obj.getString("idioma"))
                val text = obj.getString("text")
                val alignLeft = obj.getBoolean("left")
                transcriptContainer.addView(createBubbleView(text, idioma, alignLeft))
            }
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        } catch (e: Exception) {
            // Historial corrupto o de una versión anterior: se ignora y se empieza de cero.
        }
    }

    /** Borra la conversación de la pantalla y del almacenamiento guardado. */
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
    }
}
