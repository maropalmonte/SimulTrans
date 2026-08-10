package com.example.simultrans

import android.Manifest
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
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    CHINO("Chino", "zh-CN", Locale.SIMPLIFIED_CHINESE, "#DE2910");

    override fun toString(): String = displayName
}

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var spinnerLangA: Spinner
    private lateinit var spinnerLangB: Spinner
    private lateinit var btnLangA: Button
    private lateinit var btnLangB: Button
    private lateinit var btnPickModel: Button

    private lateinit var translationEngine: TranslationEngine
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var modelFile: File

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
        transcriptContainer = findViewById(R.id.transcriptContainer)
        scrollView = findViewById(R.id.scrollView)
        spinnerLangA = findViewById(R.id.spinnerLangA)
        spinnerLangB = findViewById(R.id.spinnerLangB)
        btnLangA = findViewById(R.id.btnLangA)
        btnLangB = findViewById(R.id.btnLangB)
        btnPickModel = findViewById(R.id.btnPickModel)

        ensureMicPermission()
        setupLanguageSpinners()
        updateLanguageButtons()

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
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, nombres)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
                statusText.text = getString(R.string.status_ready)
            }
        }
    }

    private fun speak(text: String, idioma: Idioma) {
        tts.language = idioma.ttsLocale
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /** Burbuja coloreada según el idioma hablado; alineada a la izquierda si es langA. */
    private fun addBubble(text: String, idioma: Idioma) {
        val density = resources.displayMetrics.density
        val baseColor = Color.parseColor(idioma.colorHex)
        val bubbleColor = ColorUtils.blendARGB(Color.WHITE, baseColor, 0.15f)
        val background = GradientDrawable().apply {
            setColor(bubbleColor)
            cornerRadius = 16f * density
        }

        val bubble = TextView(this).apply {
            this.text = text
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            this.background = background
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (idioma == langA) Gravity.START else Gravity.END
                setMargins((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            }
        }
        transcriptContainer.addView(bubble)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        tts.shutdown()
        translationEngine.close()
    }
}
