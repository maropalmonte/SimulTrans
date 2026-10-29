import SwiftUI

/// Burbuja temporal "Escuchando…" con el tiempo grabado y un medidor de voz.
/// Se muestra dentro de la conversación mientras se graba y desaparece al parar.
struct IndicadorEscucha: View {
    let grabador: AudioRecorderService
    let colorHex: String
    @State private var inicio = Date()

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.15)) { contexto in
            Text(texto(en: contexto.date))
                .font(.system(.body, design: .monospaced))
                .padding(12)
                .background(Color(hex: colorHex).opacity(0.15))
                .cornerRadius(14)
        }
    }

    private func texto(en fecha: Date) -> String {
        let seg = max(0, Int(fecha.timeIntervalSince(inicio)))
        let barras = min(10, max(0, Int((grabador.nivel() * 10).rounded())))
        let medidor = String(repeating: "█", count: barras) + String(repeating: "░", count: 10 - barras)
        let tiempo = "\(seg / 60):" + (seg % 60 < 10 ? "0" : "") + "\(seg % 60)"
        return "🎙️ Escuchando… \(tiempo)\n\(medidor)"
    }
}
