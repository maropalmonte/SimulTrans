import Foundation

/// Dónde vive el modelo de IA en el iPhone y utilidades relacionadas.
enum ModelStore {
    static let fileName = "gemma-4-E2B-it.litertlm"
    static let downloadURL = URL(string: "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm")!
    /// Tamaño mínimo para dar por bueno el archivo (el modelo real ronda 2,6 GB).
    static let minimumBytes: Int64 = 2_000_000_000

    static var url: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(fileName)
    }
    static var path: String { url.path }

    /// Datos para reanudar una descarga interrumpida.
    static var resumeURL: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("modelo.resume")
    }

    static var exists: Bool {
        let attrs = try? FileManager.default.attributesOfItem(atPath: path)
        let tam = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        return tam > minimumBytes
    }

    /// Evita que 2,6 GB se copien a iCloud con la copia de seguridad.
    static func excludeFromBackup() {
        var u = url
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
    }

    static func freeSpace() -> Int64 {
        let v = try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage ?? Int64.max
    }
}

/// Descarga el modelo con progreso. Usa una sesión en segundo plano: la
/// descarga continúa aunque la app pase a segundo plano, y se puede reanudar
/// si se corta la conexión.
@MainActor
final class ModelDownloader: NSObject, ObservableObject {
    enum Estado: Equatable {
        case inactivo
        case descargando
        case completado
        case error(String)
    }

    @Published private(set) var estado: Estado = .inactivo
    @Published private(set) var bytes: Int64 = 0
    @Published private(set) var total: Int64 = 0

    var progreso: Double {
        total > 0 ? min(1, Double(bytes) / Double(total)) : 0
    }

    private var session: URLSession!

    override init() {
        super.init()
        let cfg = URLSessionConfiguration.background(withIdentifier: "es.maropal.simultrans.modelo")
        cfg.sessionSendsLaunchEvents = true
        cfg.isDiscretionary = false
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
        Task { await reconectar() }
    }

    /// Si la app se cerró con una descarga en marcha, vuelve a engancharse a ella.
    private func reconectar() async {
        let tareas = await session.allTasks
        if let t = tareas.first(where: { $0 is URLSessionDownloadTask && ($0.state == .running || $0.state == .suspended) }) {
            estado = .descargando
            bytes = t.countOfBytesReceived
            if t.countOfBytesExpectedToReceive > 0 { total = t.countOfBytesExpectedToReceive }
            t.resume()
        }
    }

    func empezar() {
        guard estado != .descargando else { return }
        if ModelStore.freeSpace() < 3_500_000_000 {
            estado = .error("No hay espacio suficiente. Libera al menos 3,5 GB e inténtalo de nuevo.")
            return
        }
        bytes = 0
        estado = .descargando
        let tarea: URLSessionDownloadTask
        if let datos = try? Data(contentsOf: ModelStore.resumeURL) {
            try? FileManager.default.removeItem(at: ModelStore.resumeURL)
            tarea = session.downloadTask(withResumeData: datos)
        } else {
            tarea = session.downloadTask(with: ModelStore.downloadURL)
        }
        tarea.resume()
    }

    func cancelar() {
        estado = .inactivo
        Task {
            // Versión async (sin closures) para no ejecutar código del hilo
            // principal desde un hilo secundario.
            let tareas = await session.allTasks
            for t in tareas {
                if let descarga = t as? URLSessionDownloadTask,
                   let datos = await descarga.cancelByProducingResumeData() {
                    try? datos.write(to: ModelStore.resumeURL)
                }
            }
        }
    }

    nonisolated fileprivate func publicar(_ nuevo: Estado) {
        Task { @MainActor in self.estado = nuevo }
    }
}

extension ModelDownloader: URLSessionDownloadDelegate {
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                                totalBytesExpectedToWrite: Int64) {
        Task { @MainActor in
            self.bytes = totalBytesWritten
            if totalBytesExpectedToWrite > 0 { self.total = totalBytesExpectedToWrite }
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didFinishDownloadingTo location: URL) {
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            publicar(.error("El servidor respondió con el código \(http.statusCode). Inténtalo de nuevo más tarde."))
            return
        }
        let attrs = try? FileManager.default.attributesOfItem(atPath: location.path)
        let tam = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        guard tam > ModelStore.minimumBytes else {
            publicar(.error("La descarga está incompleta. Inténtalo de nuevo."))
            return
        }
        do {
            let destino = ModelStore.url
            if FileManager.default.fileExists(atPath: destino.path) {
                try FileManager.default.removeItem(at: destino)
            }
            try FileManager.default.moveItem(at: location, to: destino)
            ModelStore.excludeFromBackup()
            try? FileManager.default.removeItem(at: ModelStore.resumeURL)
            publicar(.completado)
        } catch {
            publicar(.error("No se pudo guardar el modelo: \(error.localizedDescription)"))
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        let ns = error as NSError
        if ns.code == NSURLErrorCancelled { return }
        if let datos = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            try? datos.write(to: ModelStore.resumeURL)
        } else {
            try? FileManager.default.removeItem(at: ModelStore.resumeURL)
        }
        publicar(.error("Descarga interrumpida: \(ns.localizedDescription)\nPulsa de nuevo el botón para reanudarla."))
    }
}
