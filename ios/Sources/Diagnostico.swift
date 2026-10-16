import Foundation
import os

/// Registro de pasos guardado en el propio teléfono. Si la app se cierra de
/// forma inesperada (fallo, falta de memoria...), al volver a abrirla se
/// muestra el último tramo del registro para poder diagnosticarlo.
enum Diagnostico {
    private static let archivo: URL =
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("diagnostico.log")

    /// Informe de la sesión anterior, si terminó de forma inesperada.
    static var informePrevio: String?

    static func paso(_ texto: String) {
        let libre = os_proc_available_memory() / 1_048_576
        let hora = ISO8601DateFormatter().string(from: Date()).suffix(9).dropLast()
        let linea = "\(hora) [\(libre) MB libres] \(texto)\n"
        guard let datos = linea.data(using: .utf8) else { return }
        if let h = try? FileHandle(forWritingTo: archivo) {
            h.seekToEndOfFile()
            h.write(datos)
            try? h.synchronize()
            try? h.close()
        } else {
            try? datos.write(to: archivo)
        }
    }

    /// Se llama al arrancar: recoge el informe previo si la sesión anterior
    /// no terminó limpiamente y empieza un registro nuevo.
    static func iniciarSesion() {
        let ud = UserDefaults.standard
        let limpia = ud.object(forKey: "sesion_limpia") as? Bool ?? true
        if !limpia, let txt = try? String(contentsOf: archivo, encoding: .utf8), !txt.isEmpty {
            informePrevio = txt.split(separator: "\n").suffix(14).joined(separator: "\n")
        }
        try? FileManager.default.removeItem(at: archivo)
        ud.set(false, forKey: "sesion_limpia")
        ud.synchronize()
        instalarManejadores()
        paso("Inicio de la app")
    }

    static func cerrarLimpio() {
        UserDefaults.standard.set(true, forKey: "sesion_limpia")
        UserDefaults.standard.synchronize()
    }

    static func sesionActiva() {
        UserDefaults.standard.set(false, forKey: "sesion_limpia")
        UserDefaults.standard.synchronize()
    }

    private static func instalarManejadores() {
        NSSetUncaughtExceptionHandler { e in
            Diagnostico.paso("EXCEPCIÓN \(e.name.rawValue): \(e.reason ?? "?")")
        }
        for s in [SIGABRT, SIGILL, SIGTRAP, SIGSEGV, SIGBUS] {
            signal(s) { n in
                Diagnostico.paso("SEÑAL \(n)")
                signal(n, SIG_DFL)
                raise(n)
            }
        }
    }
}
