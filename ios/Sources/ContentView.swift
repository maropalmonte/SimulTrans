import SwiftUI
import UniformTypeIdentifiers

struct ContentView: View {
    @State private var modelReady = false
    @State private var statusMessage = "Elige el archivo del modelo para empezar."
    @State private var showingPicker = false
    @State private var inputText = ""
    @State private var outputText = ""
    @State private var isBusy = false
    @State private var engine: TranslationEngine?

    private let modelFileName = "gemma-4-E2B-it.litertlm"
    private var modelPath: String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent(modelFileName).path
    }

    var body: some View {
        VStack(spacing: 16) {
            Text("SimulTrans")
                .font(.largeTitle)
                .bold()
            Text(statusMessage)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            if !modelReady {
                Button("Elegir archivo del modelo") {
                    showingPicker = true
                }
                .buttonStyle(.borderedProminent)
            } else {
                TextField("Escribe algo en español", text: $inputText)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)

                Button(isBusy ? "Traduciendo..." : "Traducir a inglés") {
                    traducir()
                }
                .buttonStyle(.borderedProminent)
                .disabled(isBusy || inputText.isEmpty)

                if !outputText.isEmpty {
                    Text(outputText)
                        .padding()
                        .background(Color.blue.opacity(0.1))
                        .cornerRadius(8)
                        .padding(.horizontal)
                }
            }
        }
        .padding()
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
                // Necesario para leer archivos elegidos fuera de la carpeta
                // propia de la app (equivalente a por qué en Android
                // copiábamos el content:// Uri a un File real).
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
                statusMessage = "Listo. Escribe algo para traducir."
            } catch {
                statusMessage = "Error al cargar el modelo: \(error.localizedDescription)"
            }
        }
    }

    private func traducir() {
        guard let engine else { return }
        isBusy = true
        Task {
            do {
                outputText = try await engine.translate(
                    text: inputText,
                    fromLangName: "español",
                    toLangName: "inglés"
                )
            } catch {
                outputText = "(Error: \(error.localizedDescription))"
            }
            isBusy = false
        }
    }
}

#Preview {
    ContentView()
}
