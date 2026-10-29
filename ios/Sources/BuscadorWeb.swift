import Foundation

/// Búsqueda en Internet para el Asistente IA, sin cuentas ni claves. Es el
/// equivalente en Swift de BuscadorWeb.kt (Android):
///  - Noticias de actualidad: titulares de los canales RSS públicos de Google News.
///  - Datos de fondo: API pública de Wikipedia.
/// Solo se envía el texto de búsqueda; nunca la conversación, la voz ni los
/// archivos adjuntos.
final class BuscadorWeb {
    private let agente = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) SimulTrans/1.1 (https://empresa.maropal.es)"

    /// Último fallo de red/lectura, para mostrarlo en el modo depuración.
    private(set) var ultimoError = ""

    struct Busqueda {
        let contexto: String
        let fuentes: String
        let diag: String
        let goleador: String?
    }

    private struct Noticia {
        let titulo: String
        let resumen: String
        let medio: String
        let fecha: String
        let enlace: String
        let ms: Double
    }

    private struct Articulo {
        let titulo: String
        let extracto: String
        let url: String
    }

    private func intentar<T>(_ origen: String, vacio: T, _ bloque: () async throws -> T) async -> T {
        do {
            return try await bloque()
        } catch {
            ultimoError = "\(origen): \(error.localizedDescription)"
            return vacio
        }
    }

    private func fallo(_ mensaje: String) -> NSError {
        NSError(domain: "BuscadorWeb", code: 1, userInfo: [NSLocalizedDescriptionKey: mensaje])
    }

    // MARK: - Búsqueda principal

    /// `localeTag` es el idioma elegido, p. ej. "es-ES". Devuelve nil si no se encontró nada.
    func buscar(consulta: String, localeTag: String, reciente: Bool) async -> Busqueda? {
        ultimoError = ""
        let partes = localeTag.split(separator: "-").map(String.init)
        let lang = partes.first ?? "es"
        let pais = partes.count > 1 ? partes[1] : lang.uppercased()

        async let google: [Noticia] = intentar("Google", vacio: []) {
            try await self.noticiasGoogle(consulta: consulta, lang: lang, pais: pais, reciente: reciente)
        }
        async let wiki: [Articulo] = intentar("Wikipedia", vacio: []) {
            let r = try await self.wikipedia(consulta: consulta, idioma: lang)
            if r.isEmpty && lang != "en" { return try await self.wikipedia(consulta: consulta, idioma: "en") }
            return r
        }

        var listaGoogle = await google
        if reciente { listaGoogle.sort { $0.ms > $1.ms } }
        var vistos = Set<String>()
        var candidatas: [Noticia] = []
        for n in listaGoogle.prefix(15) {
            let clave = String(n.titulo.lowercased().prefix(50))
            if vistos.insert(clave).inserted { candidatas.append(n) }
        }
        let correcciones = corregirNombres(consulta: consulta, titulares: candidatas.map { $0.titulo })
        let noticias = Array(filtrarRelevantes(candidatas, consulta: consulta, correcciones: correcciones, reciente: reciente).prefix(6))

        // Wikipedia solo como apoyo cuando hay pocas noticias (si no, mete ruido).
        var articulos: [Articulo] = []
        if noticias.count < 3 { articulos = Array((await wiki).prefix(2)) }

        // Texto completo de hasta 2 noticias con enlace directo, para poder ampliar.
        let conEnlace = Array(noticias.filter { !$0.enlace.isEmpty }.prefix(4))
        let resultados: [(Int, String)] = await withTaskGroup(of: (Int, String).self) { grupo in
            for (i, n) in conEnlace.enumerated() {
                grupo.addTask {
                    let t: String = await self.intentar("Artículo", vacio: "") { () async throws -> String in
                        if let u = try await self.enlaceDirecto(n) { return try await self.textoArticulo(u) }
                        return ""
                    }
                    return (i, t)
                }
            }
            var res: [(Int, String)] = []
            for await r in grupo { res.append(r) }
            return res.sorted { $0.0 < $1.0 }
        }
        let claves = claveTema(consulta: consulta, correcciones: correcciones)
        var textos: [(titulo: String, texto: String)] = []
        for (i, t) in resultados where t.count > 200 {
            if textos.count >= 2 { break }
            textos.append((conEnlace[i].titulo, mejoresFrases(t, claves: claves)))
        }

        if noticias.isEmpty && articulos.isEmpty { return nil }

        let formato = DateFormatter()
        formato.locale = Locale(identifier: localeTag.replacingOccurrences(of: "-", with: "_"))
        formato.dateFormat = "yyyy-MM-dd (EEEE)"
        let hoy = formato.string(from: Date())

        var sb = "Fecha de hoy: \(hoy).\n"
        if !correcciones.isEmpty {
            sb += "La pregunta puede venir de dictado por voz y tener nombres mal transcritos. "
            sb += "Correcciones probables: "
            sb += correcciones.map { "«\($0.key)» es «\($0.value)»" }.joined(separator: "; ")
            sb += ". Usa siempre la forma correcta.\n"
        }
        sb += "INSTRUCCIONES: abajo hay varias NOTICIAS de Internet; cada una es un hecho independiente. "
        sb += "1) Identifica qué noticias tratan exactamente del tema de la pregunta (mismo equipo, persona o asunto, y misma fecha). "
        sb += "2) Si varias cuentan el mismo hecho, combínalas. Si cuentan hechos distintos, NO los mezcles: responde solo con el hecho por el que se pregunta, o por separado si la pregunta es ambigua. "
        sb += "3) Usa solo datos que aparezcan en esas noticias (cifras, nombres, minutos). Un nombre solo es el autor de un gol si el texto lo dice expresamente. "
        sb += "4) Si el usuario escribió mal un nombre, asume el que aparece en las noticias y no menciones el error. "
        sb += "5) Si las noticias no contienen la respuesta, di claramente que no la has encontrado. Nunca uses marcadores como [nombre]. "
        sb += "Las noticias están ordenadas por relevancia, no por fecha: fíjate en la fecha de cada una.\n"

        let pista = extraerGoleador(
            noticias.map { $0.titulo + ". " + $0.resumen } + textos.map { $0.texto },
            consulta: consulta, correcciones: correcciones
        )
        if let pista {
            sb += "\nPISTA EXTRAÍDA AUTOMÁTICAMENTE DE LOS TEXTOS: el autor del gol que más se menciona es «\(pista)». "
            sb += "Si preguntan quién marcó, responde con ese nombre.\n"
        }
        for (i, n) in noticias.enumerated() {
            sb += "\n\nNOTICIA \(i + 1)"
            if !n.medio.isEmpty { sb += " — \(n.medio)" }
            if !n.fecha.isEmpty { sb += " — \(n.fecha)" }
            sb += "\nTitular: \(n.titulo)"
            if let t = textos.first(where: { $0.titulo == n.titulo })?.texto {
                sb += "\nTexto: \(t)"
            } else if !n.resumen.isEmpty {
                sb += "\nResumen: \(n.resumen)"
            }
        }
        if !articulos.isEmpty {
            sb += "\n\nWIKIPEDIA:"
            for a in articulos { sb += "\n- \(a.titulo): \(a.extracto)" }
        }

        var fuentes: [String] = []
        for n in noticias.prefix(4) {
            var linea = "• " + n.titulo
            if !n.medio.isEmpty { linea += " — \(n.medio)" }
            if !n.fecha.isEmpty { linea += " (\(n.fecha))" }
            fuentes.append(linea)
        }
        for a in articulos { fuentes.append("• Wikipedia: \(a.titulo) — \(a.url)") }

        var diag = "Google \(listaGoogle.count) · textos \(textos.count)"
        if let pista { diag += " · goleador \(pista)" }
        if !ultimoError.isEmpty { diag += " · error: \(ultimoError)" }
        return Busqueda(contexto: sb, fuentes: fuentes.joined(separator: "\n"), diag: diag, goleador: pista)
    }

    // MARK: - Noticias

    private func noticiasGoogle(consulta: String, lang: String, pais: String, reciente: Bool) async throws -> [Noticia] {
        let q = codificar(reciente ? "\(consulta) when:2d" : consulta)
        let xml = try await descargar("https://news.google.com/rss/search?q=\(q)&hl=\(lang)&gl=\(pais)&ceid=\(pais):\(lang)")
        return parsearRss(xml)
    }

    private func parsearRss(_ xml: String) -> [Noticia] {
        guard let datos = xml.data(using: .utf8) else { return [] }
        let lector = LectorRss()
        let parser = XMLParser(data: datos)
        parser.delegate = lector
        parser.parse()
        var lista: [Noticia] = []
        for item in lector.items {
            var t = limpiar(item.titulo)
            var m = limpiar(item.medio)
            // Google News pone el medio al final del titular: "Titular - Medio".
            if m.isEmpty, let rango = t.range(of: " - ", options: .backwards) {
                m = String(t[rango.upperBound...])
                t = String(t[..<rango.lowerBound])
            }
            if t.isEmpty { continue }
            let fecha = item.fecha.trimmingCharacters(in: .whitespacesAndNewlines)
            lista.append(Noticia(
                titulo: t, resumen: "", medio: m,
                fecha: String(fecha.prefix(16)),
                enlace: item.enlace.trimmingCharacters(in: .whitespacesAndNewlines),
                ms: fechaAMs(fecha)
            ))
        }
        return lista
    }

    private func fechaAMs(_ s: String) -> Double {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        guard let d = f.date(from: s.trimmingCharacters(in: .whitespacesAndNewlines)) else { return 0 }
        return d.timeIntervalSince1970 * 1000
    }

    /// Quita etiquetas HTML y convierte las entidades (&amp;, &#39;…) en texto.
    private func limpiar(_ s: String) -> String {
        var r = s.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        let nombradas = ["&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&apos;": "'", "&nbsp;": " "]
        for (k, v) in nombradas { r = r.replacingOccurrences(of: k, with: v) }
        for m in Rx.todas("&#(x?[0-9a-fA-F]+);", en: r).reversed() {
            guard let rangoTodo = Range(m.range, in: r) else { continue }
            let num = Rx.grupo(m, 1, en: r).lowercased()
            let codigo = num.hasPrefix("x") ? UInt32(num.dropFirst(), radix: 16) : UInt32(num, radix: 10)
            if let codigo, let u = Unicode.Scalar(codigo) {
                r.replaceSubrange(rangoTodo, with: String(Character(u)))
            }
        }
        return r.replacingOccurrences(of: "\u{00A0}", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Wikipedia

    private func wikipedia(consulta: String, idioma: String) async throws -> [Articulo] {
        let q = codificar(consulta)
        let texto = try await descargar(
            "https://\(idioma).wikipedia.org/w/api.php?action=query&format=json" +
            "&generator=search&gsrsearch=\(q)&gsrlimit=3&redirects=1" +
            "&prop=extracts%7Cinfo&inprop=url&exintro=1&explaintext=1&exchars=800&exlimit=3"
        )
        guard let datos = texto.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: datos) as? [String: Any],
              let query = json["query"] as? [String: Any],
              let paginas = query["pages"] as? [String: Any] else { return [] }
        var lista: [(Int, Articulo)] = []
        for (_, valor) in paginas {
            guard let p = valor as? [String: Any] else { continue }
            let extracto = ((p["extract"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if extracto.isEmpty { continue }
            lista.append((
                (p["index"] as? Int) ?? 99,
                Articulo(titulo: (p["title"] as? String) ?? "", extracto: extracto, url: (p["fullurl"] as? String) ?? "")
            ))
        }
        return lista.sorted { $0.0 < $1.0 }.map { $0.1 }
    }

    // MARK: - Texto y claves

    private let temasGenericos: Set<String> = [
        "champions", "champion", "league", "liga", "resultado", "goles", "partido", "final",
        "futbol", "noticias", "ultima", "hora", "europa"
    ]

    private func normalizar(_ s: String) -> String {
        s.lowercased().folding(options: .diacriticInsensitive, locale: nil)
    }

    private func palabras(_ s: String) -> [String] {
        s.split(separator: " ").map(String.init)
    }

    private func claveTema(consulta: String, correcciones: [String: String]) -> [String] {
        var vistas = Set<String>()
        var resultado: [String] = []
        for p in palabras(consulta) + Array(correcciones.values) {
            let n = normalizar(p)
            if n.count >= 4 && !temasGenericos.contains(n) && vistas.insert(n).inserted { resultado.append(n) }
        }
        return resultado
    }

    /// De un artículo largo se quedan solo las frases que hablan del tema preguntado.
    private func mejoresFrases(_ texto: String, claves: [String], maximo: Int = 1200) -> String {
        let marcado: String
        if let regex = try? NSRegularExpression(pattern: "(?<=[.!?])\\s+") {
            marcado = regex.stringByReplacingMatches(
                in: texto, range: NSRange(texto.startIndex..., in: texto), withTemplate: "\u{1}")
        } else {
            marcado = texto
        }
        let frases = marcado.components(separatedBy: "\u{1}")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.count > 25 }
        if frases.isEmpty { return String(texto.prefix(maximo)) }
        var puntuadas: [(Int, String, Int)] = []
        for (i, f) in frases.enumerated() {
            let n = normalizar(f)
            let coincidencias = claves.filter { n.contains($0) }.count
            let puntos = coincidencias * 3 + (f.rangeOfCharacter(from: .decimalDigits) != nil ? 1 : 0)
            puntuadas.append((i, f, puntos))
        }
        var elegidas = Array(puntuadas.filter { $0.2 > 0 }.sorted { $0.2 > $1.2 }.prefix(6))
        if elegidas.isEmpty { elegidas = Array(puntuadas.prefix(3)) }
        elegidas.sort { $0.0 < $1.0 }
        var sb = ""
        for (_, f, _) in elegidas {
            if sb.count + f.count > maximo { break }
            sb += f + " "
        }
        let r = sb.trimmingCharacters(in: .whitespacesAndNewlines)
        return r.isEmpty ? String(texto.prefix(maximo)) : r
    }

    private let nombrePatron = "([A-ZÁÉÍÓÚÑ][\\p{L}'-]+(?: [A-ZÁÉÍÓÚÑ][\\p{L}'-]+)?)"

    /// Saca de los textos el nombre que más se repite como autor de un gol.
    private func extraerGoleador(_ textos: [String], consulta: String, correcciones: [String: String]) -> String? {
        let patrones = [
            "[Gg]ol(?:es)? de \(nombrePatron)",
            "[Pp]enalti (?:de|transformado por|marcado por) \(nombrePatron)",
            "\(nombrePatron) (?:marcó|anotó|marca|anota|firmó|firma|transformó|batió|bate|adelantó|adelanta) ",
            "(?:gracias a|con un gol de|con el gol de) \(nombrePatron)",
            "\(nombrePatron) da la victoria"
        ]
        var excluidas = Set<String>()
        let fijas = ["Real", "Betis", "Champions", "League", "Liga", "Cartuja", "Sevilla", "El", "La", "Los", "Las", "Un", "Una"]
        for p in palabras(consulta) + Array(correcciones.keys) + Array(correcciones.values) + fijas {
            excluidas.insert(normalizar(p))
        }
        var cuenta: [String: Int] = [:]
        var variante: [String: String] = [:]
        for t in textos {
            for patron in patrones {
                for m in Rx.todas(patron, en: t) {
                    let nombre = Rx.grupo(m, 1, en: t).trimmingCharacters(in: .whitespaces)
                    let tokens = nombre.split(separator: " ").map(String.init)
                    guard let primero = tokens.first else { continue }
                    if tokens.contains(where: { excluidas.contains(normalizar($0)) }) { continue }
                    let clave = normalizar(primero)
                    cuenta[clave, default: 0] += 1
                    if (variante[clave]?.count ?? 0) < nombre.count { variante[clave] = nombre }
                }
            }
        }
        guard let mejor = cuenta.max(by: { $0.value < $1.value }) else { return nil }
        return variante[mejor.key]
    }

    /// Deja solo las noticias que hablan del tema preguntado.
    private func filtrarRelevantes(_ lista: [Noticia], consulta: String, correcciones: [String: String], reciente: Bool) -> [Noticia] {
        let claves = claveTema(consulta: consulta, correcciones: correcciones)
        var r = lista
        if !claves.isEmpty {
            let porTema = r.filter { n in
                let t = normalizar(n.titulo)
                return claves.contains { t.contains($0) }
            }
            if !porTema.isEmpty { r = porTema }
        }
        if reciente {
            let limite = Date().timeIntervalSince1970 * 1000 - 60 * 3_600_000
            let recientes = r.filter { $0.ms == 0 || $0.ms >= limite }
            if !recientes.isEmpty { r = recientes }
        }
        // Las que nombran más palabras clave, primero (orden estable).
        let puntuadas = r.enumerated().map { (i, n) -> (Int, Int, Noticia) in
            let t = normalizar(n.titulo)
            return (claves.filter { t.contains($0) }.count, i, n)
        }
        return puntuadas.sorted { $0.0 != $1.0 ? $0.0 > $1.0 : $0.1 < $1.1 }.map { $0.2 }
    }

    /// Distancia de edición entre dos palabras.
    private func distancia(_ a: String, _ b: String) -> Int {
        let x = Array(a), y = Array(b)
        var prev = Array(0...y.count)
        if x.isEmpty { return y.count }
        for i in 1...x.count {
            var cur = [Int](repeating: 0, count: y.count + 1)
            cur[0] = i
            if y.count > 0 {
                for j in 1...y.count {
                    cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1))
                }
            }
            prev = cur
        }
        return prev[y.count]
    }

    /// El dictado por voz suele transcribir mal los nombres propios ("Fellenor" por
    /// "Feyenoord"). Compara las palabras de la búsqueda con los nombres de los titulares.
    private func corregirNombres(consulta: String, titulares: [String]) -> [String: String] {
        var enTitulares: [String] = []
        for t in titulares {
            enTitulares += t.components(separatedBy: CharacterSet.letters.inverted).filter { $0.count >= 4 }
        }
        let conocidas = Set(enTitulares.map { $0.lowercased() })
        var vistas = Set<String>()
        var candidatas: [String] = []
        for p in enTitulares {
            if let c = p.first, c.isUppercase, vistas.insert(p).inserted { candidatas.append(p) }
        }
        var resultado: [String: String] = [:]
        for palabra in palabras(consulta).filter({ $0.count >= 5 }) {
            let minus = palabra.lowercased()
            if conocidas.contains(minus) { continue }
            var mejor: (String, Int)?
            for c in candidatas {
                guard c.lowercased().first == minus.first else { continue }
                let d = distancia(minus, c.lowercased())
                let tope = max(2, Int(Double(max(c.count, palabra.count)) * 0.45))
                if d <= tope && (mejor == nil || d < mejor!.1) { mejor = (c, d) }
            }
            if let mejor { resultado[palabra] = mejor.0 }
        }
        return resultado
    }

    // MARK: - Palabras clave de la pregunta

    private static let palabrasVacias: Set<String> = [
        "cómo", "como", "qué", "que", "quién", "quien", "quiénes", "cuál", "cual", "cuándo", "cuando",
        "dónde", "donde", "por", "para", "con", "sin", "de", "del", "la", "el", "los", "las", "un", "una",
        "unos", "unas", "en", "y", "o", "a", "al", "es", "fue", "fueron", "ha", "han", "hay", "me", "mi",
        "tu", "su", "se", "lo", "le", "quedó", "quedo", "jugando", "jugó", "ayer", "hoy", "anoche",
        "mañana", "dime", "cuéntame", "puedes", "podrías", "sobre", "acerca", "está", "esta", "este",
        "ese", "esa", "más", "mas", "muy", "pero", "si", "no", "ya", "también", "tiene", "tienen",
        "how", "what", "who", "when", "where", "the", "of", "in", "on", "and", "is", "was", "did",
        "yesterday", "today", "tell", "me", "about"
    ]

    /// Palabras clave de una pregunta, sin necesidad de preguntárselo al modelo.
    static func palabrasClave(_ pregunta: String) -> String {
        let limpia = pregunta.replacingOccurrences(of: "[¿?¡!.,;:()\"]", with: " ", options: .regularExpression)
        var base = limpia.split(whereSeparator: { $0.isWhitespace }).map(String.init)
            .filter { !palabrasVacias.contains($0.lowercased()) }
            .filter { $0.lowercased().range(of: "^(marcó|marco|metió|metio|anotó|anoto|gol|goles|goleador|goleadores)$", options: .regularExpression) == nil }
        base = Array(base.prefix(6))
        let l = pregunta.lowercased()
        if l.range(of: "marcó|marco|metió|metio|anotó|anoto|\\bgol|goleador", options: .regularExpression) != nil {
            base.append("goles")
        } else if l.range(of: "quedó|quedo|resultado|ganó|perdió|empat|marcador", options: .regularExpression) != nil {
            base.append("resultado")
        }
        let r = base.joined(separator: " ")
        return String((r.trimmingCharacters(in: .whitespaces).isEmpty ? String(pregunta.prefix(100)) : r).prefix(100))
    }

    // MARK: - Artículos

    /// Los enlaces de Google News no apuntan al periódico: hay que pedirle a Google la
    /// dirección real. Primero se prueba el formato antiguo (base64) y, si no, el nuevo.
    private func enlaceDirecto(_ n: Noticia) async throws -> String? {
        if n.enlace.isEmpty { return nil }
        return try await resolverGoogle(n.enlace)
    }

    private func resolverGoogle(_ enlace: String) async throws -> String? {
        var id = ""
        if let r = enlace.range(of: "/articles/") {
            id = String(enlace[r.upperBound...])
            if let q = id.firstIndex(of: "?") { id = String(id[..<q]) }
        }
        if id.isEmpty { throw fallo("Google: enlace sin id") }

        // Formato antiguo: la dirección va dentro del identificador, en base64.
        var b64 = id.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        if let datos = Data(base64Encoded: b64) {
            let texto = String(data: datos, encoding: .isoLatin1) ?? ""
            if let m = Rx.todas("https?://[\\x21-\\x7e]+", en: texto).first {
                let u = Rx.grupo(m, 0, en: texto)
                if !u.contains("news.google.com") { return u }
            }
        }

        let pagina = try await descargar("https://news.google.com/rss/articles/\(id)?hl=en-US&gl=US&ceid=US:en")
        guard let sg = Rx.todas("data-n-a-sg=\"([^\"]+)\"", en: pagina).first else { throw fallo("Google: sin firma") }
        guard let ts = Rx.todas("data-n-a-ts=\"([^\"]+)\"", en: pagina).first else { throw fallo("Google: sin marca") }
        let firma = Rx.grupo(sg, 1, en: pagina)
        let marca = Rx.grupo(ts, 1, en: pagina)

        let interno = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1]," +
            "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"\(id)\",\(marca),\"\(firma)\"]"
        let internoJson = try JSONSerialization.data(withJSONObject: interno, options: [.fragmentsAllowed])
        let internoQuoted = String(data: internoJson, encoding: .utf8) ?? "\"\""
        let peticion = "[[[\"Fbv4je\"," + internoQuoted + ",null,\"generic\"]]]"

        guard let url = URL(string: "https://news.google.com/_/DotsSplashUi/data/batchexecute") else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 6)
        req.httpMethod = "POST"
        req.setValue(agente, forHTTPHeaderField: "User-Agent")
        req.setValue("application/x-www-form-urlencoded;charset=UTF-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = ("f.req=" + codificar(peticion)).data(using: .utf8)
        let (datosResp, resp) = try await URLSession.shared.data(for: req)
        let codigo = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if codigo != 200 { throw fallo("Google: batchexecute \(codigo)") }
        let respuesta = String(decoding: datosResp, as: UTF8.self)

        let trozos = respuesta.components(separatedBy: "\n\n")
        if trozos.count > 1 {
            let bloque = String(trozos[1].dropLast(2))
            if let d = bloque.data(using: .utf8),
               let externo = try? JSONSerialization.jsonObject(with: d) as? [Any],
               let primero = externo.first as? [Any], primero.count > 2,
               let interior = primero[2] as? String,
               let di = interior.data(using: .utf8),
               let arr = try? JSONSerialization.jsonObject(with: di) as? [Any], arr.count > 1,
               let directa = arr[1] as? String {
                return directa
            }
        }
        if let u = extraerUrlDeRespuesta(respuesta) { return u }
        throw fallo("Google: respuesta no legible")
    }

    /// Plan B: busca la dirección tras "garturlres" en el texto bruto de la respuesta.
    private func extraerUrlDeRespuesta(_ resp: String) -> String? {
        guard let marca = resp.range(of: "garturlres"),
              let inicio = resp.range(of: "http", range: marca.upperBound..<resp.endIndex) else { return nil }
        let c = Array(resp[inicio.lowerBound...])
        var sb = ""
        var k = 0
        while k < c.count {
            let ch = c[k]
            if ch == "\"" { break }
            if ch == "\\" {
                let n: Character? = k + 1 < c.count ? c[k + 1] : nil
                if n == "\"" { break }
                if n == "/" { sb.append("/"); k += 2; continue }
                if n == "\\" { k += 1; continue }
                if n == "u" && k + 6 <= c.count {
                    let hex = String(c[(k + 2)..<(k + 6)])
                    if let v = UInt32(hex, radix: 16), let u = Unicode.Scalar(v) { sb.append(Character(u)) }
                    k += 6
                    continue
                }
                k += 1
                continue
            }
            sb.append(ch)
            k += 1
        }
        return (sb.hasPrefix("http") && !sb.contains("news.google.com")) ? sb : nil
    }

    /// Descarga una noticia y devuelve sus primeros párrafos como texto plano.
    private func textoArticulo(_ enlace: String) async throws -> String {
        guard let url = URL(string: enlace) else { throw fallo("enlace no válido") }
        var req = URLRequest(url: url, timeoutInterval: 6)
        req.setValue(agente, forHTTPHeaderField: "User-Agent")
        req.setValue("es,en;q=0.8", forHTTPHeaderField: "Accept-Language")
        let (datos, resp) = try await URLSession.shared.data(for: req)
        let codigo = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if codigo != 200 { throw fallo("web \(codigo)") }
        var html = String(decoding: datos.prefix(1_500_000), as: UTF8.self)
        html = html.replacingOccurrences(
            of: "(?is)<(script|style|noscript|nav|footer|header|aside)[^>]*>.*?</\\1>",
            with: " ", options: .regularExpression)
        var parrafos: [String] = []
        for m in Rx.todas("(?is)<p[^>]*>(.*?)</p>", en: html) {
            let p = limpiar(Rx.grupo(m, 1, en: html))
            if p.count > 60 { parrafos.append(p) }
        }
        let texto = String(parrafos.joined(separator: " ").prefix(8000))
        if texto.count <= 200 { throw fallo("texto corto (\(texto.count))") }
        return texto
    }

    // MARK: - Red

    private func codificar(_ s: String) -> String {
        var permitidos = CharacterSet.alphanumerics
        permitidos.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: permitidos) ?? s
    }

    private func descargar(_ direccion: String) async throws -> String {
        guard let url = URL(string: direccion) else { throw fallo("dirección no válida") }
        var req = URLRequest(url: url, timeoutInterval: 8)
        req.setValue(agente, forHTTPHeaderField: "User-Agent")
        let (datos, resp) = try await URLSession.shared.data(for: req)
        let codigo = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if codigo != 200 { throw fallo("HTTP \(codigo)") }
        return String(decoding: datos, as: UTF8.self)
    }
}

// MARK: - Utilidades

/// Pequeños ayudantes para trabajar con expresiones regulares.
enum Rx {
    static func todas(_ patron: String, en texto: String, opciones: NSRegularExpression.Options = []) -> [NSTextCheckingResult] {
        guard let regex = try? NSRegularExpression(pattern: patron, options: opciones) else { return [] }
        return regex.matches(in: texto, range: NSRange(texto.startIndex..., in: texto))
    }

    static func grupo(_ m: NSTextCheckingResult, _ n: Int, en texto: String) -> String {
        guard n < m.numberOfRanges, let r = Range(m.range(at: n), in: texto) else { return "" }
        return String(texto[r])
    }
}

/// Lee los <item> de un canal RSS (título, enlace, fecha y medio).
private final class LectorRss: NSObject, XMLParserDelegate {
    struct Item { var titulo = ""; var enlace = ""; var fecha = ""; var medio = "" }
    var items: [Item] = []
    private var actual: Item?
    private var etiqueta = ""

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        etiqueta = elementName
        if elementName == "item" { actual = Item() }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) { agregar(string) }

    func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) {
        agregar(String(decoding: CDATABlock, as: UTF8.self))
    }

    private func agregar(_ s: String) {
        guard actual != nil else { return }
        if etiqueta == "title" { actual?.titulo += s }
        else if etiqueta == "link" { actual?.enlace += s }
        else if etiqueta == "pubDate" { actual?.fecha += s }
        else if etiqueta.lowercased().hasSuffix("source") { actual?.medio += s }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if elementName == "item", let a = actual {
            items.append(a)
            actual = nil
        }
        etiqueta = ""
    }
}
