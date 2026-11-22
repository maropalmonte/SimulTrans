import SwiftUI
import StoreKit

/// Límites gratuitos diarios (los mismos que en Android).
enum Limites {
    static let minutosTraduccion = 15
    static let preguntas = 15
}

/// Contadores de uso diario, guardados solo en el dispositivo.
enum UsoDiario {
    private static let d = UserDefaults.standard

    private static func revisarDia() {
        let hoy = Calendar.current.startOfDay(for: Date()).timeIntervalSince1970
        if d.double(forKey: "uso_dia") != hoy {
            d.set(hoy, forKey: "uso_dia")
            d.set(0.0, forKey: "uso_segundos")
            d.set(0, forKey: "uso_preguntas")
        }
    }

    static func segundosRestantes() -> Double {
        revisarDia()
        return max(0, Double(Limites.minutosTraduccion * 60) - d.double(forKey: "uso_segundos"))
    }

    static func preguntasRestantes() -> Int {
        revisarDia()
        return max(0, Limites.preguntas - d.integer(forKey: "uso_preguntas"))
    }

    static func sumarTraduccion(segundos: Double) {
        revisarDia()
        d.set(d.double(forKey: "uso_segundos") + segundos, forKey: "uso_segundos")
    }

    static func sumarPregunta() {
        revisarDia()
        d.set(d.integer(forKey: "uso_preguntas") + 1, forKey: "uso_preguntas")
    }
}

/// Suscripción Pro con StoreKit 2.
@MainActor
final class Tienda: ObservableObject {
    static let shared = Tienda()

    static let idMensual = "simultrans_pro_mensual"
    static let idAnual = "simultrans_pro_anual"

    /// Quien compró la app cuando era de pago (compilaciones hasta esta) tiene Pro gratis.
    static let ultimaCompilacionDePago = 7

    @Published var esPro = false
    @Published var productos: [Product] = []
    @Published var motivoPaywall: String?     // no nil = mostrar el panel
    @Published var mensaje: String?
    @Published var uso = 0                     // se incrementa para refrescar contadores en pantalla

    private var escucha: Task<Void, Never>?

    private init() {
        escucha = Task { [weak self] in
            for await resultado in Transaction.updates {
                if case .verified(let t) = resultado {
                    await t.finish()
                    await self?.actualizarEstado()
                }
            }
        }
        Task { await cargar() }
    }

    func cargar() async {
        do {
            let lista = try await Product.products(for: [Self.idAnual, Self.idMensual])
            productos = lista.sorted { $0.id == Self.idAnual && $1.id != Self.idAnual }
        } catch {
            productos = []
        }
        await actualizarEstado()
    }

    func actualizarEstado() async {
        var pro = false
        for await resultado in Transaction.currentEntitlements {
            if case .verified(let t) = resultado,
               [Self.idMensual, Self.idAnual].contains(t.productID),
               t.revocationDate == nil {
                pro = true
            }
        }
        if !pro { pro = await compradorOriginal() }
        esPro = pro
    }

    /// Compradores de la versión de pago anterior: Pro gratis para siempre.
    private func compradorOriginal() async -> Bool {
        if case .verified(let app)? = try? await AppTransaction.shared {
            if let n = Int(app.originalAppVersion), n <= Self.ultimaCompilacionDePago { return true }
        }
        return false
    }

    func comprar(_ p: Product) async {
        do {
            let r = try await p.purchase()
            switch r {
            case .success(let v):
                if case .verified(let t) = v {
                    await t.finish()
                    await actualizarEstado()
                    if esPro { motivoPaywall = nil; mensaje = L("¡Gracias! Pro está activo.") }
                }
            default: break
            }
        } catch {
            mensaje = error.localizedDescription
        }
    }

    func restaurar() async {
        try? await AppStore.sync()
        await actualizarEstado()
        mensaje = L("Compras comprobadas.")
    }

    func mostrar(_ motivo: String) { motivoPaywall = motivo }
}

/// Panel de suscripción.
struct PaywallView: View {
    @ObservedObject var tienda = Tienda.shared
    let motivo: String
    @Environment(\.dismiss) private var dismiss

    private func periodo(_ p: Product) -> String {
        guard let s = p.subscription else { return "" }
        return s.subscriptionPeriod.unit == .year ? L("al año") : L("al mes")
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if tienda.esPro {
                        Text(L("⭐ SimulTrans Pro activo")).font(.title3.bold())
                    } else {
                        if !motivo.isEmpty { Text(motivo).font(.headline) }
                        Text(L("Gratis: %ld min de traducción por voz y %ld preguntas al asistente al día.\nPro: sin límites diarios.", Limites.minutosTraduccion, Limites.preguntas))
                        ForEach(tienda.productos, id: \.id) { p in
                            Button {
                                Task { await tienda.comprar(p) }
                            } label: {
                                VStack(spacing: 2) {
                                    Text(p.displayName.isEmpty ? (p.id == Tienda.idAnual ? L("Anual") : L("Mensual")) : p.displayName).bold()
                                    Text("\(p.displayPrice) \(periodo(p))").font(.subheadline)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(10)
                                .background(Color.accentColor)
                                .foregroundColor(.white)
                                .cornerRadius(10)
                            }
                        }
                        if tienda.productos.isEmpty {
                            Text(L("Las suscripciones no están disponibles ahora. Inténtalo más tarde.")).foregroundColor(.secondary)
                        }
                        Text(L("El pago se cargará a tu cuenta de Apple al confirmar la compra. La suscripción se renueva automáticamente salvo que la canceles al menos 24 horas antes de que termine el periodo actual. Puedes gestionarla o cancelarla en Ajustes › tu nombre › Suscripciones."))
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Button(L("Restaurar compras")) { Task { await tienda.restaurar() } }
                    HStack(spacing: 16) {
                        Link(L("Política de privacidad"), destination: URL(string: "https://empresa.maropal.es/privacidad.html")!)
                        Link(L("Condiciones de uso"), destination: URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!)
                    }.font(.footnote)
                    if let m = tienda.mensaje { Text(m).font(.footnote).foregroundColor(.secondary) }
                }
                .padding()
            }
            .navigationTitle("SimulTrans Pro")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("Ahora no")) { dismiss() } } }
        }
    }
}
