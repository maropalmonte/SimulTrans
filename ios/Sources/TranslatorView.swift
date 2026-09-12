import SwiftUI
import AVFoundation

/// Pantalla del Traductor. Equivalente a la pestaña "Traductor" de
/// MainActivity.kt en Android: dos idiomas seleccionables, botones grandes
/// para grabar, lista de burbujas, borrar conversación e historial
/// persistente (aquí con UserDefaults en vez de SharedPreferences).
struct TranslatorView: View {
    let engine: TranslationEngine

    @State private var langA: Idioma = .espanol
    @State private var langB: Idioma = .ingles
    @State private var mensajes: [MensajeTraduccion] = []

    @State private var grabando = false
    @State private var idiomaGrabando: Idioma?
    @State private var isBusy = false
    @State private var statusMessage = "Listo."

    private let grabador = AudioRecorderService()
    private let synthesizer = AVSpeechSynthesizer()
    private let claveHistorial = "transcript_history"

    var body: some View {
        VStack(spacing: 0) {
            // Barra de estado + botón de detener lectura
            HStack {
                Text(statusMessage)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Spacer()
                Button {
                    synthesizer.stopSpeaking(at: .immediate)
                } label: {
                    Image(systemName: "stop.fill")
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
            .background(Color(.systemBackground))

            // Selectores de idioma
            HStack {
                Picker("Idioma A", selection: $langA) {
                    ForEach(Idioma.todos) { idioma in
                        Text(idioma.displayName).tag(idioma)
                    }
                }
                Picker("Idioma B", selection: $langB) {
                    ForEach(Idioma.todos) { idioma in
                        Text(idioma.displayName).tag(idioma)
                    }
                }
            }
            .pickerStyle(.menu)
            .padding(.horizontal)

            if langA == langB {
                Text("Elige dos idiomas distintos para conversar.")
                    .font(.caption)
                    .foregroundColor(.red)
            }

            Button("Borrar conversación") {
                borrarConversacion()
            }
            .font(.caption)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .padding(.horizontal)
            .padding(.top, 2)

            // Lista de burbujas
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(mensajes) { mensaje in
                            burbuja(mensaje).id(mensaje.id)
                        }
                    }
                    .padding()
                }
                .onChange(of: mensajes.count) { _, _ in
                    if let ultimo = mensajes.last {
                        withAnimation { proxy.scrollTo(ultimo.id, anchor: .bottom) }
                    }
                }
            }

            // Botones grandes de idioma
            HStack(spacing: 8) {
                botonIdioma(langA)
                botonIdioma(langB)
            }
            .padding()
        }
        .onAppear { cargarHistorial() }
    }

    // MARK: - Vistas auxiliares

    @ViewBuilder
    private func burbuja(_ mensaje: MensajeTraduccion) -> some View {
        HStack {
            if !mensaje.alignLeft { Spacer(minLength: 40) }
            Text(mensaje.text)
                .padding(12)
                .background(Color(hex: mensaje.colorHex).opacity(0.15))
                .cornerRadius(14)
            if mensaje.alignLeft { Spacer(minLength: 40) }
        }
    }

    @ViewBuilder
    private func botonIdioma(_ idioma: Idioma) -> some View {
        let estaGrabandoEste = grabando && idiomaGrabando == idioma
        Button {
            onLangButtonTap(idioma)
        } label: {
            Text(estaGrabandoEste ? "🔴 Toca para parar" : "Hablar en \(idioma.displayName)")
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color(hex: idioma.colorHex))
                .foregroundColor(.white)
                .cornerRadius(10)
        }
        .disabled((isBusy && !estaGrabandoEste) || langA == langB)
    }

    // MARK: - Grabación, transcripción y traducción

    private func onLangButtonTap(_ idioma: Idioma) {
        if grabando {
            if idiomaGrabando == idioma {
                detenerYTraducir()
            }
            return
        }
        solicitarPermisoYGrabar(idioma)
    }

    private func solicitarPermisoYGrabar(_ idioma: Idioma) {
        AVAudioApplication.requestRecordPermission { concedido in
            DispatchQueue.main.async {
                if concedido {
                    iniciarGrabacion(idioma)
                } else {
                    statusMessage = "Falta el permiso de micrófono."
                }
            }
        }
    }

    private func iniciarGrabacion(_ idioma: Idioma) {
        do {
            try grabador.startRecording(to: urlGrabacionTemporal())
            grabando = true
            idiomaGrabando = idioma
            statusMessage = "Grabando... toca de nuevo para traducir"
        } catch {
            statusMessage = "No se pudo iniciar la grabación: \(error.localizedDescription)"
        }
    }

    private func detenerYTraducir() {
        guard let idioma = idiomaGrabando else { return }
        grabador.stopRecording()
        grabando = false
        idiomaGrabando = nil
        isBusy = true
        statusMessage = "Transcribiendo..."

        let rutaAudio = urlGrabacionTemporal().path
        let otro = idioma == langA ? langB : langA

        Task {
            do {
                let texto = try await engine.transcribe(
                    audioPath: rutaAudio,
                    languageName: idioma.displayName.lowercased()
                )
                if texto.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    isBusy = false
                    statusMessage = "Listo."
                    return
                }
                agregarMensaje(texto: texto, idioma: idioma, alineadoIzquierda: idioma == langA)

                statusMessage = "Traduciendo..."
                let traduccion = try await engine.translate(
                    text: texto,
                    fromLangName: idioma.displayName.lowercased(),
                    toLangName: otro.displayName.lowercased()
                )
                agregarMensaje(texto: traduccion, idioma: otro, alineadoIzquierda: otro == langA)
                hablar(traduccion, idioma: otro)
            } catch {
                statusMessage = "Error: \(error.localizedDescription)"
            }
            isBusy = false
            if statusMessage.hasPrefix("Traduciendo") || statusMessage.hasPrefix("Transcribiendo") {
                statusMessage = "Listo."
            }
        }
    }

    private func urlGrabacionTemporal() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("grabacion_voz.wav")
    }

    // MARK: - Voz

    /// Quita símbolos que la voz pronunciaría literalmente ("asterisco",
    /// "guion"...), igual que limpiarTextoParaVoz() en Android.
    private func limpiarTextoParaVoz(_ texto: String) -> String {
        var resultado = texto
        resultado = aplicarRegex(resultado, patron: "^[\\-•*]\\s+", opciones: [.anchorsMatchLines], reemplazo: "")
        resultado = aplicarRegex(resultado, patron: "[*_#`~|]", opciones: [], reemplazo: "")
        resultado = aplicarRegex(resultado, patron: "-{2,}", opciones: [], reemplazo: " ")
        resultado = aplicarRegex(resultado, patron: "\\.{2,}", opciones: [], reemplazo: ".")
        resultado = aplicarRegex(resultado, patron: "\\s{2,}", opciones: [], reemplazo: " ")
        return resultado.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func aplicarRegex(_ texto: String, patron: String, opciones: NSRegularExpression.Options, reemplazo: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: patron, options: opciones) else { return texto }
        let rango = NSRange(texto.startIndex..., in: texto)
        return regex.stringByReplacingMatches(in: texto, options: [], range: rango, withTemplate: reemplazo)
    }

    private func hablar(_ texto: String, idioma: Idioma) {
        let utterance = AVSpeechUtterance(string: limpiarTextoParaVoz(texto))
        utterance.voice = AVSpeechSynthesisVoice(language: idioma.ttsLanguageCode)
        synthesizer.speak(utterance)
    }

    // MARK: - Historial (equivalente a SharedPreferences en Android)

    private func cargarHistorial() {
        guard let data = UserDefaults.standard.data(forKey: claveHistorial),
              let decodificado = try? JSONDecoder().decode([MensajeTraduccion].self, from: data) else { return }
        mensajes = decodificado
    }

    private func guardarHistorial() {
        if let data = try? JSONEncoder().encode(mensajes) {
            UserDefaults.standard.set(data, forKey: claveHistorial)
        }
    }

    private func agregarMensaje(texto: String, idioma: Idioma, alineadoIzquierda: Bool) {
        mensajes.append(MensajeTraduccion(text: texto, colorHex: idioma.colorHex, alignLeft: alineadoIzquierda))
        guardarHistorial()
    }

    private func borrarConversacion() {
        mensajes = []
        UserDefaults.standard.removeObject(forKey: claveHistorial)
    }
}
