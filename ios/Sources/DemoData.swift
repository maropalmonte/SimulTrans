import Foundation

/// Datos de ejemplo para generar las capturas de la App Store desde el
/// simulador (workflow "iOS Screenshots"). No se usan en el uso normal.
enum DemoData {
    static var activo: Bool {
        ProcessInfo.processInfo.arguments.contains("-screenshots")
    }

    /// Pestaña inicial: "-tab 0" Traductor, "-tab 1" Asistente IA.
    static var pestanaInicial: Int {
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-tab"), i + 1 < args.count, let n = Int(args[i + 1]) {
            return n
        }
        return 0
    }

    static func sembrar() {
        let es = Idioma.espanol.colorHex
        let en = Idioma.ingles.colorHex
        let traductor = [
            MensajeTraduccion(text: "Buenos días, ¿podría decirme cómo llegar a la estación de tren?", colorHex: es, alignLeft: true),
            MensajeTraduccion(text: "Good morning, could you tell me how to get to the train station?", colorHex: en, alignLeft: false),
            MensajeTraduccion(text: "Of course! Go straight and turn left at the second street. It's a ten-minute walk.", colorHex: en, alignLeft: false),
            MensajeTraduccion(text: "¡Claro! Siga recto y gire a la izquierda en la segunda calle. Está a diez minutos andando.", colorHex: es, alignLeft: true)
        ]
        let usuario = "#00247D"
        let asistente = "#046A38"
        let chat = [
            MensajeTraduccion(text: "¿Cómo se dice «me gustaría una mesa para dos» en italiano?", colorHex: usuario, alignLeft: false),
            MensajeTraduccion(text: "Vorrei un tavolo per due, per favore.", colorHex: asistente, alignLeft: true),
            MensajeTraduccion(text: "¿Y para pedir la cuenta?", colorHex: usuario, alignLeft: false),
            MensajeTraduccion(text: "Il conto, per favore.", colorHex: asistente, alignLeft: true)
        ]
        let enc = JSONEncoder()
        if let d = try? enc.encode(traductor) { UserDefaults.standard.set(d, forKey: "transcript_history") }
        if let d = try? enc.encode(chat) { UserDefaults.standard.set(d, forKey: "assistant_history") }
    }
}
