import SwiftUI

/// Idiomas soportados. Equivalente a "enum class Idioma" en Android.
/// En Swift usamos una struct con valores estáticos en vez de un enum
/// clásico, porque necesitamos guardar varios datos junto a cada idioma.
struct Idioma: Identifiable, Hashable, Codable {
    let id: String              // clave estable, se usa también al guardar el historial
    let displayName: String
    let ttsLanguageCode: String // código BCP-47 para AVSpeechSynthesizer, p.ej. "es-ES"
    let colorHex: String
    let bandera: String

    /// Texto para la interfaz (bandera + nombre). El prompt usa displayName.
    var etiqueta: String { "\(bandera) \(displayName)" }

    static let espanol  = Idioma(id: "ESPANOL",  displayName: "Español",  ttsLanguageCode: "es-ES", colorHex: "#C60B1E", bandera: "🇪🇸")
    static let ingles   = Idioma(id: "INGLES",   displayName: "Inglés",   ttsLanguageCode: "en-US", colorHex: "#00247D", bandera: "🇬🇧")
    static let frances  = Idioma(id: "FRANCES",  displayName: "Francés",  ttsLanguageCode: "fr-FR", colorHex: "#0055A4", bandera: "🇫🇷")
    static let italiano = Idioma(id: "ITALIANO", displayName: "Italiano", ttsLanguageCode: "it-IT", colorHex: "#008C45", bandera: "🇮🇹")
    static let chino    = Idioma(id: "CHINO",    displayName: "Chino",    ttsLanguageCode: "zh-CN", colorHex: "#DE2910", bandera: "🇨🇳")
    static let turco    = Idioma(id: "TURCO",    displayName: "Turco",    ttsLanguageCode: "tr-TR", colorHex: "#E30A17", bandera: "🇹🇷")
    static let arabe    = Idioma(id: "ARABE",    displayName: "Árabe",    ttsLanguageCode: "ar-SA", colorHex: "#006C35", bandera: "🇸🇦")
    static let aleman   = Idioma(id: "ALEMAN",   displayName: "Alemán",   ttsLanguageCode: "de-DE", colorHex: "#FFCE00", bandera: "🇩🇪")

    static let portugues = Idioma(id: "PORTUGUES", displayName: "Portugués", ttsLanguageCode: "pt-PT", colorHex: "#046A38", bandera: "🇵🇹")

    static let rumano = Idioma(id: "RUMANO", displayName: "Rumano", ttsLanguageCode: "ro-RO", colorHex: "#002B7F", bandera: "🇷🇴")
    static let polaco = Idioma(id: "POLACO", displayName: "Polaco", ttsLanguageCode: "pl-PL", colorHex: "#DC143C", bandera: "🇵🇱")

    static let todos: [Idioma] = [.espanol, .ingles, .frances, .italiano, .chino, .turco, .arabe, .aleman, .portugues, .rumano, .polaco]
}

/// Una burbuja de la conversación. Equivalente a las entradas que
/// guardábamos en el JSON de SharedPreferences en Android.
struct MensajeTraduccion: Identifiable, Codable {
    let id: UUID
    let text: String
    let colorHex: String
    let alignLeft: Bool

    init(text: String, colorHex: String, alignLeft: Bool) {
        self.id = UUID()
        self.text = text
        self.colorHex = colorHex
        self.alignLeft = alignLeft
    }
}

/// SwiftUI no trae de fábrica una forma de crear Color a partir de un
/// código hexadecimal (a diferencia de Android, que sí tiene
/// Color.parseColor). Esta extensión rellena ese hueco.
extension Color {
    init(hex: String) {
        let limpio = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))
        var valor: UInt64 = 0
        Scanner(string: limpio).scanHexInt64(&valor)
        let r = Double((valor & 0xFF0000) >> 16) / 255
        let g = Double((valor & 0x00FF00) >> 8) / 255
        let b = Double(valor & 0x0000FF) / 255
        self.init(red: r, green: g, blue: b)
    }
}
