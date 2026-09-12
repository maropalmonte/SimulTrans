import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @State private var modelReady = false
    @State private var statusMessage = "Elige el archivo del modelo para empezar."
    @State private var showingPicker = false
    @State private var engine: TranslationEngine?

    private let modelFileName = "gemma-4-E2B-it.litertlm"
    private var modelPath: String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent(modelFileName).path
    }

    var body: some View {
        Group {
            if modelReady, let engine {
                TranslatorView(engine: engine)
            } else {
                VStack(spacing: 16) {
                    Text("SimulTrans")
                        .font(.largeTitle)
                        .bold()
                    Text(statusMessage)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                    Button("Elegir archivo del modelo") {
                        showingPicker = true
                    }
                    .buttonStyle(.borderedProminent)
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
