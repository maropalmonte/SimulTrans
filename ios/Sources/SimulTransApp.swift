import SwiftUI

@main
struct SimulTransApp: App {
    init() {
        // Modo de capturas para la App Store: con el argumento "-screenshots"
        // la app muestra conversaciones de ejemplo y no necesita el modelo.
        if ProcessInfo.processInfo.arguments.contains("-screenshots") {
            DemoData.sembrar()
        } else {
            Diagnostico.iniciarSesion()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
