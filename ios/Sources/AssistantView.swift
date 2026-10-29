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
    @AppStorage("busqueda_web") private var busquedaWeb = true
    @AppStorage("debug") private var debugActivo = false

    // Memoria para preguntas de seguimiento ("búscala por internet")
    @State private var ultimaConsultaUsada: String?
    @State private var ultimaReciente = false

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
    private let buscador = BuscadorWeb()
    private let synthesizer = AVSpeechSynthesizer()
    private let claveHistorial = "assistant_history"

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Idioma de voz:").font(.footnote)
                Picker("Idioma de voz", selection: $idiomaVoz) {
                    ForEach(Idioma.todos) { Text($0.etiqueta).tag($0) }
                }
                .pickerStyle(.menu)
                Spacer()
                Button {
                    synthesizer.stopSpeaking(at: .immediate)
                } label: {
                    Image(systemName: "stop.fill")
                }
                Button("Borrar") { borrarConversacion() }
                    .font(.caption)
                    .padding(.leading, 8)
            }
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
                        if grabando {
                            HStack {
                                Spacer(minLength: 40)
                                IndicadorEscucha(grabador: grabador, colorHex: "#607D8B")
                            }
                            .id("escuchando")
                        }
                    }
                    .padding()
                }
                .onChange(of: mensajes.count) { _, _ in
                    if let ultimo = mensajes.last {
                        withAnimation { proxy.scrollTo(ultimo.id, anchor: .bottom) }
                    }
                }
                .onChange(of: grabando) { _, ahora in
                    if ahora { withAnimation { proxy.scrollTo("escuchando", anchor: .bottom) } }
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
                Button {
                    enviar()
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.title)
                        .foregroundColor(Color(hex: "#00247D").opacity(puedeEnviar ? 1 : 0.35))
                }
                .disabled(!puedeEnviar)
            }
            .padding(.horizontal)
            .padding(.top, 6)

            HStack {
                Toggle("🔊 Leer en voz alta", isOn: $leerEnVozAlta)
                Toggle("🌐 Buscar en Internet", isOn: $busquedaWeb)
            }
            .font(.caption)
            .toggleStyle(CasillaCompacta())
            .padding(.horizontal)
            .padding(.vertical, 6)
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

        // Orden interna "/debug": muestra u oculta cómo se ha buscado.
        if pregunta.lowercased() == "/debug" {
            debugActivo.toggle()
            texto = ""
            estado = debugActivo ? "Modo depuración activado" : "Modo depuración desactivado"
            return
        }

        let visible = pregunta.isEmpty ? "(adjunto)" : pregunta
        let adjuntoEtiqueta = nombreAdjunto.map { "\n📎 \($0)" } ?? ""
        agregar("\(visible)\(adjuntoEtiqueta)", asistente: false)

        let contextoPrevio = contextoPendiente.map { String($0.suffix(1500)) }
        let base = pregunta.isEmpty ? "Analiza el adjunto." : pregunta
        var promptFinal = base
        if let textoAdjunto {
            promptFinal = "Contenido del documento adjunto:\n\(textoAdjunto)\n\nPregunta del usuario: \(base)"
        }
        if let previo = contextoPrevio {
            promptFinal = "Esta es la conversación que tuvimos antes de cerrar la app; tenla en cuenta para entender referencias a lo ya hablado:\n\(previo)\n\n\(promptFinal)"
        }
        // La conversación anterior solo hace falta inyectarla una vez.
        contextoPendiente = nil
        let imagenes = rutaImagen.map { [$0] } ?? []
        let conDocumento = textoAdjunto != nil
        let yaTeniaContexto = contextoPrevio != nil
        let buscarEnInternet = busquedaWeb && imagenes.isEmpty && pregunta.count >= 8 && !conDocumento

        texto = ""
        quitarAdjunto()
        ocupado = true
        estado = "Pensando..."

        Task {
            var promptConBusqueda = promptFinal
            var huboBusqueda = false
            var textoFuentes = ""
            var titularesFuentes = ""
            var goleador: String?
            var lineaDebug = ""
            var avisoSinRed = ""

            if buscarEnInternet {
                estado = "Buscando en Internet..."
                let claves = BuscadorWeb.palabrasClave(pregunta)
                // Pregunta corta o orden de buscar ("búscala por internet"): se suma el tema anterior.
                let esOrden = pregunta.count < 60 &&
                    pregunta.range(of: "b[uú]sca(la|lo|me)?\\b|internet|mira en|consulta", options: [.regularExpression, .caseInsensitive]) != nil
                let palabrasClave = claves.split(separator: " ").map { $0.lowercased() }
                let pocasClaves = palabrasClave.filter { $0 != "resultado" && $0 != "goles" }.count <= 3
                let esSeguimiento = ultimaConsultaUsada != nil && (esOrden || (pregunta.count < 60 && pocasClaves))
                let reciente = pregunta.range(of: "ayer|hoy|anoche|esta semana|últim|reciente|ahora mismo|actual", options: [.regularExpression, .caseInsensitive]) != nil
                    || (esSeguimiento && ultimaReciente)
                var consulta = claves
                if esOrden, let previa = ultimaConsultaUsada {
                    consulta = previa
                } else if esSeguimiento, let previa = ultimaConsultaUsada {
                    var vistas = Set<String>()
                    var unidas: [String] = []
                    for p in previa.split(separator: " ").map(String.init).filter({ $0.lowercased() != "resultado" }) + claves.split(separator: " ").map(String.init) {
                        if vistas.insert(p.lowercased()).inserted { unidas.append(p) }
                    }
                    consulta = String(unidas.joined(separator: " ").prefix(100))
                }
                let locale = idiomaVoz.ttsLanguageCode
                var usada = consulta
                var busqueda = await buscador.buscar(consulta: consulta, localeTag: locale, reciente: reciente)
                if busqueda == nil && consulta != claves {
                    usada = claves
                    busqueda = await buscador.buscar(consulta: usada, localeTag: locale, reciente: reciente)
                }
                if let busqueda {
                    promptConBusqueda = busqueda.contexto + "\n\n" + promptFinal
                    huboBusqueda = true
                    textoFuentes = busqueda.contexto
                    titularesFuentes = busqueda.fuentes
                    goleador = busqueda.goleador
                    lineaDebug = "\n\n🔎 «\(usada)» [\(busqueda.diag)]\n" + busqueda.fuentes
                    if !esSeguimiento || usada == claves { ultimaConsultaUsada = usada }
                    ultimaReciente = reciente
                } else {
                    avisoSinRed = "\n\n(No he encontrado nada en Internet sobre esto; respondo con lo que sé, que puede estar desactualizado.)"
                }
                estado = "Pensando..."
            }

            do {
                Diagnostico.paso("Asistente: enviando pregunta al modelo")
                let preguntaPorGol = pregunta.range(of: "marc[oó]|met[ií]o|anot[oó]|\\bgol|goleador", options: [.regularExpression, .caseInsensitive]) != nil
                var respuesta: String
                var reiniciada: Bool
                if huboBusqueda, preguntaPorGol, let goleador {
                    // Respuesta directa con el nombre extraído de las noticias: el modelo pequeño lo inventa.
                    respuesta = "El gol lo marcó \(goleador), según las noticias encontradas."
                    reiniciada = true
                } else {
                    (respuesta, reiniciada) = try await responder(
                        prompt: promptConBusqueda, imagenes: imagenes,
                        conBusqueda: huboBusqueda, yaTieneContexto: yaTeniaContexto)
                }

                if huboBusqueda {
                    // Control de calidad: lo que diga la respuesta tiene que estar en los textos encontrados.
                    var dudosos = datosNoRespaldados(respuesta, fuente: textoFuentes + " " + pregunta)
                    if !dudosos.isEmpty {
                        respuesta = try await engine.askOneShot(
                            prompt: promptConBusqueda + "\n\nATENCIÓN: una respuesta anterior tuya incluía datos que NO aparecen en los textos (" +
                                dudosos.joined(separator: ", ") + "). Responde de nuevo usando únicamente datos que aparezcan " +
                                "literalmente en los textos. Si los textos no contienen la respuesta, di que no la has encontrado.",
                            imagePaths: [])
                        await engine.resetAssistantConversation()
                        reiniciada = true
                        dudosos = datosNoRespaldados(respuesta, fuente: textoFuentes + " " + pregunta)
                        let hayNumero = dudosos.contains { d in d.allSatisfy { $0.isNumber } }
                        if !dudosos.isEmpty && (hayNumero || dudosos.count >= 2) {
                            respuesta = "No estoy seguro de la respuesta: los textos que he encontrado no la confirman con claridad. " +
                                "Esto es lo que dicen los titulares:\n" +
                                titularesFuentes.components(separatedBy: "\n").prefix(4).joined(separator: "\n")
                        }
                    }
                }

                agregar(respuesta + avisoSinRed + (debugActivo ? lineaDebug : ""), asistente: true)
                // La conversación viva se descartó: el próximo mensaje recibe un resumen de lo hablado.
                if reiniciada { contextoPendiente = resumenReciente(excluirUltima: false) }
                if leerEnVozAlta { hablar(respuesta) }
            } catch {
                agregar("Error: \(error.localizedDescription)", asistente: true)
            }
            ocupado = false
            estado = ""
        }
    }

    /// Responde en la conversación viva del asistente, salvo que el mensaje sea muy largo
    /// (resultados de Internet) o la memoria del modelo se haya llenado: entonces usa una
    /// conversación puntual con un resumen de lo hablado y descarta la viva.
    private func responder(prompt: String, imagenes: [String], conBusqueda: Bool, yaTieneContexto: Bool) async throws -> (String, Bool) {
        func puntual() async throws -> (String, Bool) {
            let resumen = yaTieneContexto ? "" : resumenReciente(excluirUltima: true)
            let completo = resumen.isEmpty ? prompt : "Conversación reciente:\n\(resumen)\n\n\(prompt)"
            let r = try await engine.askOneShot(prompt: completo, imagePaths: imagenes)
            await engine.resetAssistantConversation()
            return (r, true)
        }
        if conBusqueda { return try await puntual() }
        do {
            return (try await engine.askWithContext(prompt: prompt, imagePaths: imagenes), false)
        } catch {
            let m = error.localizedDescription
            if m.contains("Prefill") || m.contains("FAILED_PRECONDITION") || m.contains("capacity") {
                return try await puntual()
            }
            throw error
        }
    }

    private func normalizarTexto(_ s: String) -> String {
        s.lowercased().folding(options: .diacriticInsensitive, locale: nil)
    }

    /// Datos de la respuesta (cifras y nombres propios) que no aparecen en los textos de los
    /// que debía salir: señal de que el modelo se los ha inventado o los ha mezclado.
    private func datosNoRespaldados(_ respuesta: String, fuente: String) -> [String] {
        let f = normalizarTexto(fuente)
        var malos: [String] = []
        for m in Rx.todas("\\d{2,}", en: respuesta) {
            let n = Rx.grupo(m, 0, en: respuesta)
            if !f.contains(n) { malos.append(n) }
        }
        for m in Rx.todas("(?<=[a-záéíóúñ0-9,;:] )([A-ZÁÉÍÓÚÑ][\\p{L}]{2,})", en: respuesta) {
            let palabra = Rx.grupo(m, 1, en: respuesta)
            if !f.contains(normalizarTexto(palabra)) { malos.append(palabra) }
        }
        var vistos = Set<String>()
        return malos.filter { vistos.insert($0).inserted }
    }

    /// Últimos mensajes de la conversación, recortados, para dar contexto sin llenar la memoria.
    private func resumenReciente(excluirUltima: Bool) -> String {
        let fin = excluirUltima ? mensajes.count - 1 : mensajes.count
        if fin <= 0 { return "" }
        return mensajes[max(0, fin - 6)..<fin]
            .map { ($0.alignLeft ? "Asistente: " : "Usuario: ") + String($0.text.prefix(300)) }
            .joined(separator: "\n")
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
                    Diagnostico.paso("Asistente: transcribiendo dictado")
                    let t = try await engine.transcribe(audioPath: ruta, languageName: idioma)
                    texto = t
                } catch {
                    estado = "Error: \(error.localizedDescription)"
                }
                ocupado = false
                if estado.hasPrefix("Transcribiendo") { estado = "" }
            }
        } else {
            Task { @MainActor in
                Diagnostico.paso("Asistente: pidiendo permiso de micrófono")
                let concedido = await AVAudioApplication.requestRecordPermission()
                Diagnostico.paso("Asistente: permiso de micrófono = \(concedido)")
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


/// Casilla pequeña (texto + interruptor) para que dos quepan en una misma fila.
struct CasillaCompacta: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.label
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Toggle("", isOn: configuration.$isOn)
                .labelsHidden()
                .scaleEffect(0.75)
        }
    }
}
