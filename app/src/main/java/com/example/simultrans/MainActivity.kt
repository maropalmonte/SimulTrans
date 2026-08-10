package com.example.simultrans

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * App de traducción simultánea Español <-> Inglés usando Gemma 4 E2B
 * a través de LiteRT-LM, 100% on-device.
 *
 * El modelo NO se incluye en el APK (pesa varios GB). Se descarga con el
 * navegador del propio móvil desde Hugging Face y se selecciona dentro de
 * la app con el botón "Elegir archivo del modelo" (selector de documentos
 * del sistema) — no hace falta ordenador, cable ni adb.
 *
 * Descarga el archivo .litertlm (tras aceptar la licencia de Gemma) desde:
 *   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var transcriptContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var btnSpanish: Button
    private lateinit var btnEnglish: Button
    private lateinit var btnPickModel: Button

    private lateinit var translationEngine: TranslationEngine
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var modelFile: File

    private var isBusy = false

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
        btnSpanish = findViewById(R.id.btnSpanish)
        btnEnglish = findViewById(R.id.btnEnglish)
        btnPickModel = findViewById(R.id.btnPickModel)

        ensureMicPermission()

        tts = TextToSpeech(this) { }

        val modelDir = File(filesDir, "models")
        modelDir.mkdirs()
        modelFile = File(modelDir, "gemma-4-E2B-it.litertlm")
        translationEngine = TranslationEngine(modelFile)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        btnSpanish.setOnClickListener { startListening(fromEs = true) }
        btnEnglish.setOnClickListener { startListening(fromEs = false) }
        btnPickModel.setOnClickListener {
            // "*/*" porque .litertlm no tiene un tipo MIME reconocido por Android
            pickModelFile.launch(arrayOf("*/*"))
        }

        loadModel(translationEngine)
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
                statusText.text = getString(R.string.status_ready)
                btnSpanish.isEnabled = true
                btnEnglish.isEnabled = true
            } catch (e: Exception) {
                statusText.text = "Error al cargar el modelo: ${e.message}"
                btnPickModel.visibility = View.VISIBLE
                btnPickModel.isEnabled = true
            }
        }
    }

    private fun startListening(fromEs: Boolean) {
        if (isBusy) return
        isBusy = true
        statusText.text = getString(R.string.listening)

        val locale = if (fromEs) "es-ES" else "en-US"
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
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
                translateAndShow(spokenText, fromEs)
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

    private fun translateAndShow(spokenText: String, fromEs: Boolean) {
        statusText.text = getString(R.string.translating)
        addBubble(spokenText, fromEs)

        val fromLangName = if (fromEs) "español" else "inglés"
        val toLangName = if (fromEs) "inglés" else "español"

        lifecycleScope.launch {
            try {
                val translated = translationEngine.translate(spokenText, fromLangName, toLangName)
                addBubble(translated, !fromEs)
                speak(translated, toEnglish = fromEs)
            } catch (e: Exception) {
                addBubble("(Error al traducir: ${e.message})", !fromEs)
            } finally {
                isBusy = false
                statusText.text = getString(R.string.status_ready)
            }
        }
    }

    private fun speak(text: String, toEnglish: Boolean) {
        tts.language = if (toEnglish) Locale.US else Locale("es", "ES")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    

Recalled memory
kotlin
private fun addBubble(text: String, isSpanish: Boolean) {
    val bubble = TextView(this).apply {
        this.text = text
        setPadding(24, 16, 24, 16)
        textSize = 16f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        setBackgroundResource(
            if (isSpanish) R.drawable.bubble_es else R.drawable.bubble_en
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isSpanish) Gravity.START else Gravity.END
            setMargins(8, 8, 8, 8)
        }
    }
    transcriptContainer.addView(bubble)
    scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
}

private fun addBubble(text: String, isSpanish: Boolean) {
    val bubble = TextView(this).apply {
        this.text = text
        setPadding(24, 16, 24, 16)
        textSize = 16f
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        setBackgroundResource(
            if (isSpanish) R.drawable.bubble_es else R.drawable.bubble_en
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isSpanish) Gravity.START else Gravity.END
            setMargins(8, 8, 8, 8)
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
