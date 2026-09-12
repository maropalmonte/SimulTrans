import Foundation
import LiteRTLM

/// Envoltorio sobre el motor de LiteRT-LM para hacer traducciones puntuales
/// y transcripciones de audio, con el modelo Gemma 4 E2B. Es el equivalente
/// en Swift de TranslationEngine.kt (Android) — mismas funciones, misma idea:
/// cada llamada abre una conversación nueva para no arrastrar contexto entre
/// turnos.
///
/// Se declara como "actor" (el equivalente en Swift al Mutex que usábamos en
/// Kotlin): garantiza que solo una parte del código a la vez toca el motor,
/// aunque se llame desde varios sitios de la app al mismo tiempo.
actor TranslationEngine {
    private var engine: Engine?
    private let modelPath: String

    init(modelPath: String) {
        self.modelPath = modelPath
    }

    var isModelPresent: Bool {
        FileManager.default.fileExists(atPath: modelPath)
    }

    func initialize() async throws {
        let config = try EngineConfig(
            modelPath: modelPath,
            backend: .cpu(),
            // Necesario para poder transcribir audio (pestaña Traductor) y,
            // más adelante, procesar imágenes (pestaña Asistente IA).
            visionBackend: .cpu(),
            audioBackend: .cpu(),
            cacheDir: NSTemporaryDirectory()
        )
        let nuevoEngine = Engine(engineConfig: config)
        try await nuevoEngine.initialize()
        self.engine = nuevoEngine
    }

    /// Traduce un texto de un idioma a otro. Equivalente a translate() en
    /// TranslationEngine.kt.
    func translate(text: String, fromLangName: String, toLangName: String) async throws -> String {
        guard let engine else {
            throw NSError(
                domain: "TranslationEngine",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "El motor no está inicializado"]
            )
        }
        let systemInstruction = """
        Eres un traductor profesional simultáneo.
        Traduce SIEMPRE del \(fromLangName) al \(toLangName).
        Responde ÚNICAMENTE con la traducción, sin explicaciones,
        sin comillas y sin repetir el texto original.
        """
        let config = ConversationConfig(systemMessage: Message(systemInstruction))
        let conversation = try await engine.createConversation(with: config)
        let response = try await conversation.sendMessage(Message(text))
        return response.toString.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Transcribe un archivo de audio (.wav) en el idioma indicado.
    /// Equivalente a transcribe() en TranslationEngine.kt.
    func transcribe(audioPath: String, languageName: String) async throws -> String {
        guard let engine else {
            throw NSError(
                domain: "TranslationEngine",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "El motor no está inicializado"]
            )
        }
        let prompt = "Transcribe este audio en \(languageName). Responde únicamente con la transcripción, sin explicaciones."
        let message = Message(contents: [
            Content.audioFile(audioPath),
            Content.text(prompt)
        ])
        let conversation = try await engine.createConversation()
        let response = try await conversation.sendMessage(message)
        return response.toString.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
