import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @StateObject private var descarga = ModelDownloader()
    @State private var modelReady: Bool
    @State private var cargando = false
    @State private var statusMessage = "SimulTrans necesita un modelo de IA (2,6 GB) que se descarga una sola vez. Después funciona sin conexión."
    @State private var showingPicker = false
    @State private var confirmarDescarga = false
    @State private var engine: TranslationEngine?
    @State private var tab = DemoData.pestanaInicial

    init() {
        // En modo capturas se salta la carga del modelo (ver DemoData).
        _modelReady = State(initialValue: DemoData.activo)
        _engine = State(initialValue: DemoData.activo ? TranslationEngine(modelPath: "") : nil)
    }

    var body: some View {
        Group {
            if modelReady, let engine {
                VStack(spacing: 0) {
                    TabView(selection: $tab) {
                        TranslatorView(engine: engine)
                            .tabItem { Label("Traductor", systemImage: "globe") }
                            .tag(0)
                        AssistantView(engine: engine)
                            .tabItem { Label("Asistente IA", systemImage: "sparkles") }
                            .tag(1)
                    }
                    Link(destination: URL(string: "https://maropal.es")!) {
                        Text("Gestión y Software Maropal · maropal.es")
                            .font(.caption2)
                            .padding(6)
                    }
                }
            } else {
                pantallaModelo
            }
        }
        .fileImporter(isPresented: $showingPicker, allowedContentTypes: [.item]) { result in
            switch result {
            case .success(let url):
                copiarYCargarModelo(desde: url)
            case .failure(let error):
                statusMessage = "Error al elegir archivo: \(error.localizedDescription)"
            }
        }
        .confirmationDialog("Descargar el modelo de IA", isPresented: $confirmarDescarga, titleVisibility: .visible) {
            Button("Descargar (2,6 GB)") { descarga.empezar() }
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text("Son unos 2,6 GB y se descargan una sola vez. Se recomienda usar Wi-Fi.")
        }
        .task {
            // Si el modelo ya está en el teléfono, cargarlo directamente.
            if !DemoData.activo && ModelStore.exists && !modelReady {
                await cargarModelo()
            }
        }
        .onChange(of: descarga.estado) { _, nuevo in
            if nuevo == .completado {
                Task { await cargarModelo() }
            }
        }
    }

    // MARK: - Pantalla de modelo

    private var pantallaModelo: some View {
        VStack(spacing: 16) {
            Text("SimulTrans")
                .font(.largeTitle)
                .bold()

            if cargando {
                ProgressView()
                Text(statusMessage)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            } else if descarga.estado == .descargando {
                ProgressView(value: descarga.progreso)
                    .padding(.horizontal)
                Text(textoProgreso)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Text("Puedes dejar la app en segundo plano mientras se descarga.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                Button("Cancelar descarga", role: .cancel) { descarga.cancelar() }
            } else {
                Text(mensajeActual)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                Button {
                    confirmarDescarga = true
                } label: {
                    Text("📥 Descargar e instalar el modelo (2,6 GB)")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color(hex: "#C60B1E"))
                        .foregroundColor(.white)
                        .cornerRadius(10)
                }
                Button {
                    showingPicker = true
                } label: {
                    Text("📂 Ya lo tengo: elegir el archivo")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color(hex: "#00247D"))
                        .foregroundColor(.white)
                        .cornerRadius(10)
                }
            }
        }
        .padding()
    }

    private var mensajeActual: String {
        if case .error(let mensaje) = descarga.estado { return mensaje }
        return statusMessage
    }

    private var textoProgreso: String {
        let f = ByteCountFormatter()
        f.countStyle = .file
        let hecho = f.string(fromByteCount: descarga.bytes)
        let total = descarga.total > 0 ? f.string(fromByteCount: descarga.total) : "2,6 GB"
        return "\(Int(descarga.progreso * 100)) % · \(hecho) de \(total)"
    }

    // MARK: - Carga del modelo

    @MainActor
    private func cargarModelo() async {
        guard !cargando else { return }
        cargando = true
        statusMessage = "Cargando el modelo..."
        defer { cargando = false }
        do {
            let nuevoEngine = TranslationEngine(modelPath: ModelStore.path)
            try await nuevoEngine.initialize()
            engine = nuevoEngine
            modelReady = true
        } catch {
            statusMessage = "Error al cargar el modelo: \(error.localizedDescription)"
        }
    }

    private func copiarYCargarModelo(desde url: URL) {
        cargando = true
        statusMessage = "Copiando el modelo... puede tardar unos minutos"
        Task {
            do {
                try await Task.detached {
                    let accedido = url.startAccessingSecurityScopedResource()
                    defer { if accedido { url.stopAccessingSecurityScopedResource() } }
                    let destino = ModelStore.url
                    if FileManager.default.fileExists(atPath: destino.path) {
                        try FileManager.default.removeItem(at: destino)
                    }
                    try FileManager.default.copyItem(at: url, to: destino)
                    ModelStore.excludeFromBackup()
                }.value
                cargando = false
                await cargarModelo()
            } catch {
                cargando = false
                statusMessage = "Error al copiar el modelo: \(error.localizedDescription)"
            }
        }
    }
}

#Preview {
    ContentView()
}
