package com.example.simultrans

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Envoltorio simple sobre el motor de LiteRT-LM para hacer traducciones
 * puntuales (sin memoria entre turnos) y consultas libres (con o sin
 * imágenes adjuntas) con el modelo Gemma 4 E2B.
 *
 * Cada llamada a [translate] o [ask] abre una conversación nueva, para que
 * el modelo no arrastre contexto de turnos anteriores.
 */
class TranslationEngine(private val modelFile: File) {
    private lateinit var engine: Engine
    private val mutex = Mutex()
    val isModelPresent: Boolean
        get() = modelFile.exists() && modelFile.length() > 0

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            // CPU es lo más compatible entre dispositivos. Si tu app solo
            // apunta a gama media/alta, puedes cambiar a Backend.GPU() para
            // más velocidad (requiere declarar libOpenCL.so en el manifest).
            backend = Backend.CPU(),
            // Necesario para que el modelo pueda procesar las imágenes que
            // se adjuntan desde la pestaña "Asistente IA" (fotos o páginas
            // de PDF rasterizadas). Gemma 4 E2B es multimodal de fábrica.
            visionBackend = Backend.CPU(),
            // Necesario para el reconocimiento de voz propio (transcribe):
            // en vez de depender del SpeechRecognizer del sistema (que en
            // algunos fabricantes, como Samsung, no respeta los paquetes
            // offline instalados), Gemma 4 E2B reconoce audio de forma
            // nativa sin salir de la app.
            audioBackend = Backend.CPU(),
        )
        engine = Engine(config)
        engine.initialize()
    }

    suspend fun translate(text: String, fromLangName: String, toLangName: String): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val systemInstruction = """
                    Eres un traductor profesional simultáneo.
                    Traduce SIEMPRE del $fromLangName al $toLangName.
                    Responde ÚNICAMENTE con la traducción, sin explicaciones,
                    sin comillas y sin repetir el texto original.
                """.trimIndent()
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                )
                engine.createConversation(conversationConfig).use { conversation ->
                    conversation.sendMessage(text).toString().trim()
                }
            }
        }

    /**
     * Consulta libre al modelo, con 0 o varias imágenes adjuntas (fotos
     * sueltas, o páginas de un PDF ya rasterizadas a imagen por quien
     * llama a esta función). No lleva instrucción de sistema fija: el
     * usuario escribe la pregunta tal cual.
     */
    suspend fun ask(prompt: String, images: List<File> = emptyList()): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val partes = mutableListOf<Content>()
                partes.add(Content.Text(prompt))
                images.forEach { img -> partes.add(Content.ImageFile(img.absolutePath)) }
                val contents = Contents.of(*partes.toTypedArray())
                engine.createConversation().use { conversation ->
                    conversation.sendMessage(contents).toString().trim()
                }
            }
        }

    /**
     * Transcribe un archivo de audio grabado con MediaRecorder, usando el
     * reconocimiento de voz nativo de Gemma 4 E2B. Sustituye por completo
     * al SpeechRecognizer del sistema operativo: no depende de qué motor
     * de voz haya elegido el fabricante del móvil (el problema detectado
     * en Samsung/One UI), porque todo el reconocimiento ocurre dentro del
     * propio modelo, 100% on-device.
     */
    suspend fun transcribe(audioFile: File, langName: String): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val systemInstruction = """
                    Transcribe fielmente el audio adjunto, hablado en $langName.
                    Responde ÚNICAMENTE con la transcripción exacta de lo dicho,
                    sin explicaciones, sin comillas y sin comentarios adicionales.
                """.trimIndent()
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                )
                engine.createConversation(conversationConfig).use { conversation ->
                    val contents = Contents.of(Content.AudioFile(audioFile.absolutePath))
                    conversation.sendMessage(contents).toString().trim()
                }
            }
        }

    fun close() {
        if (::engine.isInitialized) {
            engine.close()
        }
    }
}
