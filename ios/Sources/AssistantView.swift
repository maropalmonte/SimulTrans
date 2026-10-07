import SwiftUI
import AVFoundation
import PhotosUI
import PDFKit
import UniformTypeIdentifiers

/// Pestaña "Asistente IA". Equivalente a la pestaña del mismo nombre en
/// MainActivity.kt: conversación con contexto, adjuntos (imagen, texto, PDF),
/// dictado por voz, lectura en voz alta e historial persistente que se
/// reinyecta al modelo tras reiniciar la app.
struct AssistantView: View {
    let engine: TranslationEngine

    @State private var idiomaVoz: Idioma = .espanol
    @State private var mensajes: [MensajeTraduccion] = []
    @State private var texto = ""
    @State private var estado = ""
    @State private var ocupado = false
    @State private var grabando = false
    @State private var leerEnVozAlta = false

    // Adjunto pendiente de enviar
    @State private var fotoSeleccionada: PhotosPickerItem?
    @State private var rutaImagen: String?
    @State private var textoAdjunto: String?
    @State private var nombreAdjunto: String?
    @State private var mostrandoArchivos = false

    /// Conversación guardada de una sesión anterior, que se le cuenta al
    /// modelo en la primera pregunta tras abrir la app.
    @State private var contextoPendiente: String?

    private let grabador = AudioRecorderService()
    private let synthesizer = AVSpeechSynthesizer()
    private let claveHistorial = "assistant_history"

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Idioma de voz:").font(.footnote)
                Picker("Idioma de voz", selection: $idiomaVoz) {
                    ForEach(Idioma.todos) { Text($0.displayName).tag($0) }
                }
                .pickerStyle(.menu)
                Spacer()
                Button {
                    synthesizer.stopSpeaking(at: .immediate)
                } label: {
                    Image(systemName: "stop.fill")
                }
            }
            .padding(.horizontal)

            Button("Borrar conversación") { borrarConversacion() }
                .font(.caption)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.horizontal)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(mensajes) { m in
                            HStack {
                                if !m.alignLeft { Spacer(minLength: 40) }
                                Text(m.text)
                                    .padding(12)
                                    .background(Color(hex: m.colorHex).opacity(0.15))
                                    .cornerRadius(14)
                                    .textSelection(.enabled)
                                if m.alignLeft { Spacer(minLength: 40) }
                            }
                            .id(m.id)
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

            if !estado.isEmpty {
                Text(estado).font(.footnote).foregroundColor(.secondary)
            }
            if let nombreAdjunto {
                HStack {
                    Text("📎 \(nombreAdjunto)").font(.footnote)
                    Button("Quitar") { quitarAdjunto() }.font(.footnote)
                }
            }

            HStack(spacing: 6) {
                PhotosPicker(selection: $fotoSeleccionada, matching: .images) {
                    Image(systemName: "photo")
                }
                Button { mostrandoArchivos = true } label: {
                    Image(systemName: "paperclip")
                }
                Button { alternarDictado() } label: {
                    Text(grabando ? "🔴" : "🎤")
                }
                .disabled(ocupado && !grabando)
                TextField("Escribe o dicta tu pregunta...", text: $texto, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.roundedBorder)
            }
            .padding(.horizontal)
            .padding(.top, 6)

            Toggle("🔊 Leer respuesta en voz alta", isOn: $leerEnVozAlta)
                .font(.footnote)
                .padding(.horizontal)

            Button {
                enviar()
            } label: {
                Text("Enviar")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color(hex: "#00247D").opacity(puedeEnviar ? 1 : 0.4))
                    .foregroundColor(.white)
                    .cornerRadius(10)
            }
            .disabled(!puedeEnviar)
            .padding()
        }
        .onAppear { cargarHistorial() }
        .onChange(of: fotoSeleccionada) { _, nuevo in cargarFoto(nuevo) }
        .fileImporter(isPresented: $mostrandoArchivos, allowedContentTypes: [.plainText, .pdf, .image, .text]) { resultado in
            if case .success(let url) = resultado { procesarArchivo(url) }
        }
    }

    private var puedeEnviar: Bool {
        !ocupado && !grabando &&
        (!texto.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || rutaImagen != nil || textoAdjunto != nil)
    }

    // MARK: - Envío

    private func enviar() {
        let pregunta = texto.trimmingCharacters(in: .whitespacesAndNewlines)
        let visible = pregunta.isEmpty ? "(adjunto)" : pregunta
        let adjuntoEtiqueta = nombreAdjunto.map { "\n📎 \($0)" } ?? ""
        agregar("\(visible)\(adjuntoEtiqueta)", asistente: false)

        var prompt = pregunta.isEmpty ? "Analiza el adjunto." : pregunta
        if let textoAdjunto {
            prompt = "Contenido del documento adjunto:\n\(textoAdjunto)\n\n\(prompt)"
        }
        if let previo = contextoPendiente {
            prompt = "Esta es la conversación que tuvimos antes de cerrar la app; tenla en cuenta:\n\(previo)\n\nNueva pregunta: \(prompt)"
        }
        let imagenes = rutaImagen.map { [$0] } ?? []

        texto = ""
        quitarAdjunto()
        ocupado = true
        estado = "Pensando..."

        Task {
            do {
                let respuesta = try await engine.askWithContext(prompt: prompt, imagePaths: imagenes)
                contextoPendiente = nil
                agregar(respuesta, asistente: true)
                if leerEnVozAlta { hablar(respuesta) }
            } catch {
                agregar("Error: \(error.localizedDescription)", asistente: true)
            }
            ocupado = false
            estado = ""
        }
    }

    // MARK: - Adjuntos

    private func quitarAdjunto() {
        rutaImagen = nil
        textoAdjunto = nil
        nombreAdjunto = nil
        fotoSeleccionada = nil
    }

    private func cargarFoto(_ item: PhotosPickerItem?) {
        guard let item else { return }
        Task {
            if let datos = try? await item.loadTransferable(type: Data.self),
               let imagen = UIImage(data: datos),
               let jpg = imagen.jpegData(compressionQuality: 0.8) {
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("adjunto.jpg")
                try? jpg.write(to: url)
                textoAdjunto = nil
                rutaImagen = url.path
                nombreAdjunto = "Imagen"
            }
        }
    }

    private func procesarArchivo(_ url: URL) {
        let accedido = url.startAccessingSecurityScopedResource()
        defer { if accedido { url.stopAccessingSecurityScopedResource() } }
        let nombre = url.lastPathComponent
        let ext = url.pathExtension.lowercased()

        if ["jpg", "jpeg", "png", "heic"].contains(ext),
           let datos = try? Data(contentsOf: url),
           let imagen = UIImage(data: datos),
           let jpg = imagen.jpegData(compressionQuality: 0.8) {
            let destino = FileManager.default.temporaryDirectory.appendingPathComponent("adjunto.jpg")
            try? jpg.write(to: destino)
            textoAdjunto = nil
            rutaImagen = destino.path
            nombreAdjunto = nombre
        } else if ext == "pdf" {
            if let doc = PDFDocument(url: url), let contenido = doc.string, !contenido.isEmpty {
                rutaImagen = nil
                textoAdjunto = String(contenido.prefix(12000))
                nombreAdjunto = nombre
            } else {
                estado = "No se pudo leer texto del PDF."
            }
        } else if let contenido = try? String(contentsOf: url, encoding: .utf8) {
            rutaImagen = nil
            textoAdjunto = String(contenido.prefix(12000))
            nombreAdjunto = nombre
        } else {
            estado = "Tipo de archivo no admitido."
        }
    }

    // MARK: - Dictado

    private func urlDictado() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("dictado_asistente.wav")
    }

    private func alternarDictado() {
        if grabando {
            grabador.stopRecording()
            grabando = false
            ocupado = true
            estado = "Transcribiendo..."
            let ruta = urlDictado().path
            let idioma = idiomaVoz.displayName.lowercased()
            Task {
                do {
                    let t = try await engine.transcribe(audioPath: ruta, languageName: idioma)
                    texto = t
                } catch {
                    estado = "Error: \(error.localizedDescription)"
                }
                ocupado = false
                if estado.hasPrefix("Transcribiendo") { estado = "" }
            }
        } else {
            AVAudioApplication.requestRecordPermission { concedido in
                DispatchQueue.main.async {
                    guard concedido else { estado = "Falta el permiso de micrófono."; return }
                    do {
                        try grabador.startRecording(to: urlDictado())
                        grabando = true
                        estado = "Grabando... toca 🔴 para terminar"
                    } catch {
                        estado = "No se pudo grabar: \(error.localizedDescription)"
                    }
                }
            }
        }
    }

    // MARK: - Voz

    private func hablar(_ texto: String) {
        let limpio = texto
            .replacingOccurrences(of: "[*_#`~|]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\s{2,}", with: " ", options: .regularExpression)
        let utterance = AVSpeechUtterance(string: limpio)
        utterance.voice = AVSpeechSynthesisVoice(language: idiomaVoz.ttsLanguageCode)
        synthesizer.speak(utterance)
    }

    // MARK: - Historial

    private func agregar(_ t: String, asistente: Bool) {
        mensajes.append(MensajeTraduccion(
            text: t,
            colorHex: asistente ? "#046A38" : "#00247D",
            alignLeft: asistente
        ))
        guardarHistorial()
    }

    private func guardarHistorial() {
        if let data = try? JSONEncoder().encode(mensajes) {
            UserDefaults.standard.set(data, forKey: claveHistorial)
        }
    }

    private func cargarHistorial() {
        guard mensajes.isEmpty,
              let data = UserDefaults.standard.data(forKey: claveHistorial),
              let guardados = try? JSONDecoder().decode([MensajeTraduccion].self, from: data),
              !guardados.isEmpty else { return }
        mensajes = guardados
        contextoPendiente = guardados
            .map { ($0.alignLeft ? "Asistente: " : "Usuario: ") + $0.text }
            .joined(separator: "\n")
    }

    private func borrarConversacion() {
        mensajes = []
        contextoPendiente = nil
        UserDefaults.standard.removeObject(forKey: claveHistorial)
        Task { await engine.resetAssistantConversation() }
    }
}
