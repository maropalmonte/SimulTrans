import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @State private var modelReady = false
    @State private var statusMessage = "Falta el modelo de IA: gemma-4-E2B-it.litertlm (2,6 GB, se descarga una sola vez).\n\n1) Pulsa «Paso 1» y descarga el archivo con Safari (mejor con Wi-Fi).\n2) Vuelve aquí y pulsa «Paso 2» para elegirlo desde la app Archivos. Después la app funciona sin conexión."
    @State private var showingPicker = false
    @State private var engine: TranslationEngine?

    private let modelFileName = "gemma-4-E2B-it.litertlm"
    private let modelDownloadURL = URL(string: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm")!
    private var modelPath: String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent(modelFileName).path
    }

    var body: some View {
        Group {
            if modelReady, let engine {
                VStack(spacing: 0) {
                    TabView {
                        TranslatorView(engine: engine)
                            .tabItem { Label("Traductor", systemImage: "globe") }
                        AssistantView(engine: engine)
                            .tabItem { Label("Asistente IA", systemImage: "sparkles") }
                    }
                    Link(destination: URL(string: "https://maropal.es")!) {
                        Text("Gestión y Software Maropal · maropal.es")
                            .font(.caption2)
                            .padding(6)
                    }
                }
            } else {
                VStack(spacing: 16) {
                    Text("SimulTrans")
                        .font(.largeTitle)
                        .bold()
                    Text(statusMessage)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                    Link(destination: modelDownloadURL) {
                        Text("📥 Paso 1: descargar el modelo (2,6 GB)")
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color(hex: "#C60B1E"))
                            .foregroundColor(.white)
                            .cornerRadius(10)
                    }
                    Button {
                        showingPicker = true
                    } label: {
                        Text("📂 Paso 2: elegir el archivo descargado")
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color(hex: "#00247D"))
                            .foregroundColor(.white)
                            .cornerRadius(10)
                    }
                }
                .padding()
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
    }

    private func copiarYCargarModelo(desde url: URL) {
        statusMessage = "Copiando el modelo..."
        Task {
            do {
                let accedido = url.startAccessingSecurityScopedResource()
                defer { if accedido { url.stopAccessingSecurityScopedResource() } }

                let destino = URL(fileURLWithPath: modelPath)
                if FileManager.default.fileExists(atPath: modelPath) {
                    try FileManager.default.removeItem(at: destino)
                }
                try FileManager.default.copyItem(at: url, to: destino)

                statusMessage = "Cargando el modelo..."
                let nuevoEngine = TranslationEngine(modelPath: modelPath)
                try await nuevoEngine.initialize()
                engine = nuevoEngine
                modelReady = true
            } catch {
                statusMessage = "Error al cargar el modelo: \(error.localizedDescription)"
            }
        }
    }
}

#Preview {
    ContentView()
}
