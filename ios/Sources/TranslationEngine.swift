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
    /// Conversación persistente del Asistente IA: mantiene el contexto entre
    /// preguntas hasta que el usuario borra la conversación.
    private var assistantConversation: Conversation?

    private static let instruccionAsistente = """
    Eres un asistente amable y útil dentro de una app de traducción.
    Responde siempre en el mismo idioma en que te habla el usuario.
    Sé conciso: por defecto responde en 1 a 3 frases, sin rodeos.
    Solo te extiendes y conversas con más detalle si el usuario quiere practicar un idioma o pide más explicación; en ese caso corrige sus errores con amabilidad.
    No uses markdown, listas con símbolos ni asteriscos: tus respuestas se leen en voz alta.
    """

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

    private func requireEngine() throws -> Engine {
        guard let engine else {
            throw NSError(
                domain: "TranslationEngine",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "El motor no está inicializado"]
            )
        }
        return engine
    }

    /// Transcribe y traduce en una sola llamada al modelo (más rápido que
    /// transcribir y traducir por separado). Devuelve ("","") si el audio no
    /// contiene voz y nil si el modelo no respeta el formato esperado, para
    /// que el llamador use el camino de dos pasos.
    func transcribeAndTranslate(audioPath: String, fromLangName: String, toLangName: String) async throws -> (original: String, traduccion: String)? {
        let engine = try requireEngine()
        let instruccion = """
        Eres un traductor profesional simultáneo.
        Recibirás un audio hablado en \(fromLangName).
        Responde EXACTAMENTE con dos líneas y nada más:
        ORIGINAL: <transcripción literal del audio en \(fromLangName)>
        TRADUCCION: <traducción al \(toLangName)>
        Si el audio no contiene voz, responde solo con: ORIGINAL:
        """
        let config = ConversationConfig(systemMessage: Message(instruccion))
        let conversation = try await engine.createConversation(with: config)
        let message = Message(contents: [
            Content.audioFile(audioPath),
            Content.text("Transcribe y traduce este audio.")
        ])
        let response = try await conversation.sendMessage(message)
        let texto = response.toString.trimmingCharacters(in: .whitespacesAndNewlines)

        let patron = "ORIGINAL:\\s*(.*?)\\s*TRADUCCION:\\s*(.*)"
        if let regex = try? NSRegularExpression(pattern: patron, options: [.dotMatchesLineSeparators, .caseInsensitive]),
           let m = regex.firstMatch(in: texto, range: NSRange(texto.startIndex..., in: texto)),
           let r1 = Range(m.range(at: 1), in: texto),
           let r2 = Range(m.range(at: 2), in: texto) {
            let original = String(texto[r1]).trimmingCharacters(in: .whitespacesAndNewlines)
            let traduccion = String(texto[r2]).trimmingCharacters(in: .whitespacesAndNewlines)
            if original.isEmpty || traduccion.isEmpty { return ("", "") }
            return (original, traduccion)
        }
        if texto.uppercased().hasPrefix("ORIGINAL:") &&
            texto.dropFirst("ORIGINAL:".count).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return ("", "")
        }
        return nil
    }

    /// Pregunta al Asistente IA conservando el contexto de la conversación.
    /// `imagePaths` son rutas de imágenes adjuntas (jpg/png).
    func askWithContext(prompt: String, imagePaths: [String] = []) async throws -> String {
        let engine = try requireEngine()
        if assistantConversation == nil {
            let config = ConversationConfig(systemMessage: Message(Self.instruccionAsistente))
            assistantConversation = try await engine.createConversation(with: config)
        }
        guard let conversation = assistantConversation else { return "" }
        var contenidos: [Content] = imagePaths.map { Content.imageFile($0) }
        contenidos.append(Content.text(prompt))
        let response = try await conversation.sendMessage(Message(contents: contenidos))
        return response.toString.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Olvida el contexto del Asistente (al borrar la conversación).
    func resetAssistantConversation() {
        assistantConversation = nil
    }
}
