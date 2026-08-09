package com.example.simultrans

import com.google.ai.edge.litertlm.Backend
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
 * puntuales (sin memoria entre turnos) con el modelo Gemma 4 E2B.
 *
 * Cada llamada a [translate] abre una conversación nueva con una instrucción
 * de sistema que fija el idioma de origen/destino, para que el modelo no
 * arrastre contexto de turnos anteriores y la traducción sea consistente.
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
                    conversation.sendMessage(text).text.trim()
                }
            }
        }

    fun close() {
        if (::engine.isInitialized) {
            engine.close()
        }
    }
}
