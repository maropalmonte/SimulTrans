package com.example.simultrans

import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Búsqueda en Internet para el Asistente IA, sin cuentas ni claves:
 *  - Noticias de actualidad: titulares y resúmenes de los canales RSS públicos
 *    de noticias de Bing y Google News.
 *  - Datos de fondo: API pública de Wikipedia.
 * Solo se envía el texto de búsqueda; nunca la conversación, la voz ni los
 * archivos adjuntos.
 */
object BuscadorWeb {
    private const val AGENTE = "Mozilla/5.0 (Linux; Android 14) SimulTrans/1.1 (https://empresa.maropal.es)"

    /** Último fallo de red/lectura, para mostrarlo si no hay resultados. */
    @Volatile var ultimoError: String = ""

    private suspend fun <T> intentar(origen: String, bloque: suspend () -> T, vacio: T): T =
        try { bloque() } catch (e: Exception) {
            ultimoError = "$origen: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            vacio
        }

    class Busqueda(val contexto: String, val fuentes: String, val diag: String, val goleador: String? = null)

    private data class Noticia(val titulo: String, val resumen: String, val medio: String, val fecha: String, val enlace: String, val esGoogle: Boolean, val ms: Long = 0L)
    private data class Articulo(val titulo: String, val extracto: String, val url: String)

    /** [localeTag] es el idioma elegido, p. ej. "es-ES". Devuelve null si no se encontró nada. */
    suspend fun buscar(consulta: String, localeTag: String, reciente: Boolean = false): Busqueda? = coroutineScope {
        ultimoError = ""
        val lang = localeTag.substringBefore('-')
        val pais = localeTag.substringAfter('-', lang.uppercase())
        val bing = async(Dispatchers.IO) { intentar("Bing", { noticiasBing(consulta, localeTag, reciente) }, emptyList()) }
        val google = async(Dispatchers.IO) { intentar("Google", { noticiasGoogle(consulta, lang, pais, reciente) }, emptyList()) }
        val wiki = async(Dispatchers.IO) {
            intentar("Wikipedia", {
                val r = wikipedia(consulta, lang)
                if (r.isEmpty() && lang != "en") wikipedia(consulta, "en") else r
            }, emptyList())
        }

        val listaBing = bing.await()
        val listaGoogle = google.await().let { l -> if (reciente) l.sortedByDescending { it.ms } else l }
        val vistos = mutableSetOf<String>()
        val candidatas = (listaBing.take(15) + listaGoogle.take(15))
            .filter { vistos.add(it.titulo.lowercase().take(50)) }
        val correcciones = corregirNombres(consulta, candidatas.map { it.titulo })
        val noticias = filtrarRelevantes(candidatas, consulta, correcciones, reciente).take(6)
        // Wikipedia solo como apoyo cuando hay pocas noticias (si no, mete ruido).
        val articulos = if (noticias.size >= 3) emptyList() else wiki.await().take(2)
        // Texto completo de hasta 3 noticias con enlace directo, para poder ampliar.
        val textos = noticias.filter { it.enlace.isNotBlank() }.take(4).map { n ->
            async(Dispatchers.IO) {
                n to intentar("Artículo", {
                    val u = enlaceDirecto(n)
                    if (u == null) "" else textoArticulo(u)
                }, "")
            }
        }.map { it.await() }.filter { it.second.length > 200 }.take(2)
            .map { (n, t) -> n to mejoresFrases(t, claveTema(consulta, correcciones)) }
        if (noticias.isEmpty() && articulos.isEmpty()) return@coroutineScope null

        val hoy = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.forLanguageTag(localeTag)).format(Date())
        val sb = StringBuilder()
        sb.append("Fecha de hoy: ").append(hoy).append(".\n")
        if (correcciones.isNotEmpty()) {
            sb.append("La pregunta puede venir de dictado por voz y tener nombres mal transcritos. ")
            sb.append("Correcciones probables: ")
            sb.append(correcciones.entries.joinToString("; ") { "«${it.key}» es «${it.value}»" })
            sb.append(". Usa siempre la forma correcta.\n")
        }
        sb.append("INSTRUCCIONES: abajo hay varias NOTICIAS de Internet; cada una es un hecho independiente. ")
        sb.append("1) Identifica qué noticias tratan exactamente del tema de la pregunta (mismo equipo, persona o asunto, y misma fecha). ")
        sb.append("2) Si varias cuentan el mismo hecho, combínalas. Si cuentan hechos distintos, NO los mezcles: responde solo con el hecho por el que se pregunta, o por separado si la pregunta es ambigua. ")
        sb.append("3) Usa solo datos que aparezcan en esas noticias (cifras, nombres, minutos). Un nombre solo es el autor de un gol si el texto lo dice expresamente. ")
        sb.append("4) Si el usuario escribió mal un nombre, asume el que aparece en las noticias y no menciones el error. ")
        sb.append("5) Si las noticias no contienen la respuesta, di claramente que no la has encontrado. Nunca uses marcadores como [nombre]. ")
        sb.append("Las noticias están ordenadas por relevancia, no por fecha: fíjate en la fecha de cada una.\n")
        val pista = extraerGoleador(
            noticias.map { it.titulo + ". " + it.resumen } + textos.map { it.second },
            consulta, correcciones
        )
        if (pista != null) {
            sb.append("\nPISTA EXTRAÍDA AUTOMÁTICAMENTE DE LOS TEXTOS: el autor del gol que más se menciona es «")
            sb.append(pista).append("». Si preguntan quién marcó, responde con ese nombre.\n")
        }
        noticias.forEachIndexed { i, n ->
            sb.append("\n\nNOTICIA ").append(i + 1)
            if (n.medio.isNotBlank()) sb.append(" — ").append(n.medio)
            if (n.fecha.isNotBlank()) sb.append(" — ").append(n.fecha)
            sb.append("\nTitular: ").append(n.titulo)
            val texto = textos.firstOrNull { it.first === n }?.second
            if (texto != null) sb.append("\nTexto: ").append(texto)
            else if (n.resumen.isNotBlank()) sb.append("\nResumen: ").append(n.resumen)
        }
        if (articulos.isNotEmpty()) {
            sb.append("\n\nWIKIPEDIA:")
            articulos.forEach { sb.append("\n- ").append(it.titulo).append(": ").append(it.extracto) }
        }

        val fuentes = buildList {
            noticias.take(4).forEach { n ->
                add("• " + n.titulo + (if (n.medio.isNotBlank()) " — ${n.medio}" else "") +
                    (if (n.fecha.isNotBlank()) " (${n.fecha})" else ""))
            }
            articulos.forEach { add("• Wikipedia: ${it.titulo} — ${it.url}") }
        }.joinToString("\n")
        val diag = "Bing ${listaBing.size} · Google ${listaGoogle.size} · textos ${textos.size}" +
            (if (pista != null) " · goleador $pista" else "") +
            (if (ultimoError.isNotBlank()) " · error: $ultimoError" else "")
        Busqueda(sb.toString(), fuentes, diag, pista)
    }

    // ---------------------------------------------------------------- noticias

    private suspend fun noticiasBing(consulta: String, locale: String, reciente: Boolean): List<Noticia> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(consulta, "UTF-8")
            val orden = if (reciente) "&qft=sortbydate%3d%221%22" else ""
            val cuerpo = descargar("https://www.bing.com/news/search?q=$q&format=RSS&mkt=$locale$orden")
            if (cuerpo.trimStart().startsWith("<?xml") || cuerpo.contains("<rss")) {
                parsearRss(cuerpo, google = false)
            } else {
                // Bing a veces devuelve la página web en vez del RSS: se leen las tarjetas de noticias.
                parsearBingHtml(cuerpo).also {
                    if (it.isEmpty()) throw java.io.IOException("Bing: HTML sin noticias reconocibles")
                }
            }
        }

    private suspend fun noticiasGoogle(consulta: String, lang: String, pais: String, reciente: Boolean): List<Noticia> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(if (reciente) "$consulta when:2d" else consulta, "UTF-8")
            parsearRss(
                descargar("https://news.google.com/rss/search?q=$q&hl=$lang&gl=$pais&ceid=$pais:$lang"),
                google = true
            )
        }

    private fun parsearRss(xml: String, google: Boolean): List<Noticia> {
        val p = Xml.newPullParser()
        p.setInput(StringReader(xml))
        val lista = mutableListOf<Noticia>()
        var enItem = false
        var tag = ""
        var titulo = StringBuilder(); var desc = StringBuilder()
        var fecha = StringBuilder(); var medio = StringBuilder(); var enlace = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    if (tag == "item") {
                        enItem = true
                        titulo = StringBuilder(); desc = StringBuilder()
                        fecha = StringBuilder(); medio = StringBuilder(); enlace = StringBuilder()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (enItem) when {
                    tag == "title" -> titulo.append(p.text)
                    tag == "description" -> desc.append(p.text)
                    tag == "link" -> enlace.append(p.text)
                    tag == "pubDate" -> fecha.append(p.text)
                    tag.endsWith("Source", ignoreCase = true) -> medio.append(p.text)
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "item" && enItem) {
                        enItem = false
                        var t = limpiar(titulo.toString())
                        var m = limpiar(medio.toString())
                        // Google News pone el medio al final del titular: "Titular - Medio".
                        if (google && m.isBlank() && t.contains(" - ")) {
                            m = t.substringAfterLast(" - ")
                            t = t.substringBeforeLast(" - ")
                        }
                        val resumen = if (google) "" else limpiar(desc.toString()).take(300)
                        if (t.isNotBlank()) {
                            lista.add(Noticia(t, resumen, m, fecha.toString().trim().take(16), enlace.toString().trim(), google, fechaAMs(fecha.toString())))
                        }
                    }
                    tag = ""
                }
            }
            ev = p.next()
        }
        return lista
    }

    private fun parsearBingHtml(html: String): List<Noticia> {
        val lista = mutableListOf<Noticia>()
        val tarjeta = Regex("<div[^>]*class=['\"][^'\"]*news-card[^'\"]*['\"][^>]*>", RegexOption.IGNORE_CASE)
        fun atributo(tag: String, nombre: String): String =
            Regex("$nombre=(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)
                ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } } ?: ""
        for (m in tarjeta.findAll(html)) {
            val tag = m.value
            val titulo = limpiar(atributo(tag, "data-title"))
            val url = limpiar(atributo(tag, "data-url"))
            val medio = limpiar(atributo(tag, "data-author"))
            if (titulo.isNotBlank() && url.startsWith("http")) {
                lista.add(Noticia(titulo, "", medio, "", url, false))
            }
        }
        return lista
    }

    private fun fechaAMs(s: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH).parse(s.trim())?.time ?: 0L
    } catch (e: Exception) { 0L }

    private fun limpiar(s: String): String =
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().replace(' ', ' ').trim()

    // --------------------------------------------------------------- wikipedia

    private suspend fun wikipedia(consulta: String, idioma: String): List<Articulo> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(consulta, "UTF-8")
            val json = JSONObject(
                descargar(
                    "https://$idioma.wikipedia.org/w/api.php?action=query&format=json" +
                        "&generator=search&gsrsearch=$q&gsrlimit=3&redirects=1" +
                        "&prop=extracts%7Cinfo&inprop=url&exintro=1&explaintext=1&exchars=800&exlimit=3"
                )
            )
            val paginas = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext emptyList()
            val lista = mutableListOf<Pair<Int, Articulo>>()
            for (clave in paginas.keys()) {
                val p = paginas.getJSONObject(clave)
                val extracto = p.optString("extract").trim()
                if (extracto.isEmpty()) continue
                lista.add(p.optInt("index", 99) to Articulo(p.optString("title"), extracto, p.optString("fullurl")))
            }
            lista.sortedBy { it.first }.map { it.second }
        }

    // ------------------------------------------------- corrección de nombres

    private fun claveTema(consulta: String, correcciones: Map<String, String>): List<String> =
        (consulta.split(" ") + correcciones.values).map { normalizar(it) }
            .filter { it.length >= 4 && it !in TEMAS_GENERICOS }.distinct()

    /**
     * De un artículo largo se quedan solo las frases que hablan del tema preguntado (las que
     * más palabras clave contienen), para que al modelo le lleguen datos y no relleno.
     */
    private fun mejoresFrases(texto: String, claves: List<String>, max: Int = 1200): String {
        val frases = texto.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length > 25 }
        if (frases.isEmpty()) return texto.take(max)
        val puntuadas = frases.mapIndexed { i, f ->
            val n = normalizar(f)
            val puntos = claves.count { n.contains(it) } * 3 + (if (Regex("\\d").containsMatchIn(f)) 1 else 0)
            Triple(i, f, puntos)
        }
        val elegidas = puntuadas.filter { it.third > 0 }.sortedByDescending { it.third }.take(6)
            .ifEmpty { puntuadas.take(3) }
            .sortedBy { it.first }
        val sb = StringBuilder()
        for ((_, f, _) in elegidas) {
            if (sb.length + f.length > max) break
            sb.append(f).append(' ')
        }
        return sb.toString().trim().ifEmpty { texto.take(max) }
    }

    private val NOMBRE = "([A-ZÁÉÍÓÚÑ][\\p{L}'-]+(?: [A-ZÁÉÍÓÚÑ][\\p{L}'-]+)?)"
    private val PATRONES_GOL = listOf(
        Regex("[Gg]ol(?:es)? de $NOMBRE"),
        Regex("[Pp]enalti (?:de|transformado por|marcado por) $NOMBRE"),
        Regex("$NOMBRE (?:marcó|anotó|marca|anota|firmó|firma|transformó|batió|bate|adelantó|adelanta) "),
        Regex("(?:gracias a|con un gol de|con el gol de) $NOMBRE"),
        Regex("$NOMBRE da la victoria")
    )

    /**
     * Saca de los textos el nombre que más se repite como autor de un gol ("gol de Isco",
     * "Isco anota…"). El modelo pequeño falla a menudo al leerlo y se inventa nombres; esto lo
     * hace con reglas fijas y se le da como pista.
     */
    private fun extraerGoleador(textos: List<String>, consulta: String, correcciones: Map<String, String>): String? {
        val excluidas = (consulta.split(" ") + correcciones.keys + correcciones.values +
            listOf("Real", "Betis", "Champions", "League", "Liga", "Cartuja", "Sevilla", "El", "La", "Los", "Las", "Un", "Una"))
            .map { normalizar(it) }.toSet()
        val cuenta = HashMap<String, Int>()
        val variante = HashMap<String, String>()
        for (t in textos) for (patron in PATRONES_GOL) for (m in patron.findAll(t)) {
            val nombre = m.groupValues[1].trim()
            val tokens = nombre.split(" ")
            if (tokens.any { normalizar(it) in excluidas }) continue
            val clave = normalizar(tokens[0])
            cuenta[clave] = (cuenta[clave] ?: 0) + 1
            if ((variante[clave]?.length ?: 0) < nombre.length) variante[clave] = nombre
        }
        val mejor = cuenta.maxByOrNull { it.value } ?: return null
        return variante[mejor.key]
    }

    private fun normalizar(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    private val TEMAS_GENERICOS = setOf(
        "champions", "champion", "league", "liga", "resultado", "goles", "partido", "final",
        "futbol", "noticias", "ultima", "hora", "europa"
    )

    /**
     * Deja solo las noticias que hablan del tema preguntado. Sin esto, una pregunta sobre
     * "el Betis en la Champions" trae también noticias de otros partidos de la misma jornada
     * y el modelo las mezcla. Se exige que el titular nombre alguna palabra clave propia
     * (no genérica) y, si se pregunta por algo reciente, que sea de los últimos 2-3 días.
     */
    private fun filtrarRelevantes(
        lista: List<Noticia>, consulta: String, correcciones: Map<String, String>, reciente: Boolean
    ): List<Noticia> {
        val claves = (consulta.split(" ") + correcciones.values)
            .map { normalizar(it) }
            .filter { it.length >= 4 && it !in TEMAS_GENERICOS }
            .distinct()
        var r = lista
        if (claves.isNotEmpty()) {
            val porTema = r.filter { n ->
                val t = normalizar(n.titulo)
                claves.any { t.contains(it) }
            }
            if (porTema.isNotEmpty()) r = porTema
        }
        if (reciente) {
            val limite = System.currentTimeMillis() - 60L * 3600_000L
            val recientes = r.filter { it.ms == 0L || it.ms >= limite }
            if (recientes.isNotEmpty()) r = recientes
        }
        // Las que nombran más palabras clave, primero (sin alterar el resto del orden).
        return r.sortedByDescending { n ->
            val t = normalizar(n.titulo)
            claves.count { t.contains(it) }
        }
    }

    /** Distancia de edición entre dos palabras. */
    private fun distancia(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * El dictado por voz suele transcribir mal los nombres propios ("Fellenor" por
     * "Feyenoord"). Compara las palabras de la búsqueda con los nombres que aparecen en
     * los titulares y propone la forma correcta cuando se parecen mucho.
     */
    private fun corregirNombres(consulta: String, titulares: List<String>): Map<String, String> {
        val enTitulares = titulares.flatMap { it.split(Regex("[^\\p{L}]+")) }.filter { it.length >= 4 }
        val conocidas = enTitulares.map { it.lowercase() }.toSet()
        val candidatas = enTitulares.filter { it[0].isUpperCase() }.distinct()
        val resultado = linkedMapOf<String, String>()
        for (palabra in consulta.split(" ").filter { it.length >= 5 }) {
            if (palabra.lowercase() in conocidas) continue
            val mejor = candidatas
                .filter { it[0].lowercaseChar() == palabra[0].lowercaseChar() }
                .map { it to distancia(palabra.lowercase(), it.lowercase()) }
                .filter { (c, d) -> d <= maxOf(2, (maxOf(c.length, palabra.length) * 0.45).toInt()) }
                .minByOrNull { it.second }
            if (mejor != null) resultado[palabra] = mejor.first
        }
        return resultado
    }

    // ----------------------------------------------------------- palabras clave

    private val PALABRAS_VACIAS = setOf(
        "cómo", "como", "qué", "que", "quién", "quien", "quiénes", "cuál", "cual", "cuándo", "cuando",
        "dónde", "donde", "por", "para", "con", "sin", "de", "del", "la", "el", "los", "las", "un", "una",
        "unos", "unas", "en", "y", "o", "a", "al", "es", "fue", "fueron", "ha", "han", "hay", "me", "mi",
        "tu", "su", "se", "lo", "le", "quedó", "quedo", "jugando", "jugó", "ayer", "hoy", "anoche",
        "mañana", "dime", "cuéntame", "puedes", "podrías", "sobre", "acerca", "está", "esta", "este",
        "ese", "esa", "más", "mas", "muy", "pero", "si", "no", "ya", "también", "tiene", "tienen",
        "how", "what", "who", "when", "where", "the", "of", "in", "on", "and", "is", "was", "did",
        "yesterday", "today", "tell", "me", "about"
    )

    /** Palabras clave de una pregunta, sin necesidad de preguntárselo al modelo (que es lento y falla). */
    fun palabrasClave(pregunta: String): String {
        val esGol = Regex("^(marcó|marco|metió|metio|anotó|anoto|gol|goles|goleador|goleadores)$")
        val base = pregunta.replace(Regex("[¿?¡!.,;:()\"]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() !in PALABRAS_VACIAS }
            .filter { !esGol.matches(it.lowercase()) }
            .take(6).toMutableList()
        val l = pregunta.lowercase()
        if (Regex("marcó|marco|metió|metio|anotó|anoto|\\bgol|goleador").containsMatchIn(l)) base.add("goles")
        else if (Regex("quedó|quedo|resultado|ganó|perdió|empat|marcador").containsMatchIn(l)) base.add("resultado")
        return base.joinToString(" ").ifBlank { pregunta.take(100) }.take(100)
    }

    // -------------------------------------------------------------- artículos

    /**
     * Dirección real de la noticia: Bing la lleva en el parámetro "url"; Google News
     * la codifica en base64 dentro del enlace (solo los formatos antiguos se pueden leer).
     */
    private suspend fun enlaceDirecto(n: Noticia): String? = withContext(Dispatchers.IO) {
        if (n.enlace.isBlank()) return@withContext null
        if (!n.esGoogle) {
            val u = Regex("[?&]url=([^&]+)").find(n.enlace)?.groupValues?.get(1)
            return@withContext if (u != null) java.net.URLDecoder.decode(u, "UTF-8") else n.enlace
        }
        resolverGoogle(n.enlace)
    }

    /**
     * Los enlaces de Google News no apuntan al periódico: hay que pedirle a Google la
     * dirección real. Primero se prueba el formato antiguo (la dirección va dentro del
     * enlace, en base64) y, si no, el nuevo (consulta a su servicio interno).
     */
    private fun resolverGoogle(enlace: String): String? {
        val id = enlace.substringAfter("/articles/", "").substringBefore('?')
        if (id.isBlank()) throw java.io.IOException("Google: enlace sin id")
        try {
            val bytes = android.util.Base64.decode(id, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
            val u = Regex("https?://[\\x21-\\x7e]+").find(String(bytes, Charsets.ISO_8859_1))?.value
            if (u != null && !u.contains("news.google.com")) return u
        } catch (e: Exception) { /* sigue con el formato nuevo */ }
        val pagina = descargar("https://news.google.com/rss/articles/$id?hl=en-US&gl=US&ceid=US:en")
        val firma = Regex("data-n-a-sg=\"([^\"]+)\"").find(pagina)?.groupValues?.get(1)
            ?: throw java.io.IOException("Google: sin firma")
        val marca = Regex("data-n-a-ts=\"([^\"]+)\"").find(pagina)?.groupValues?.get(1)
            ?: throw java.io.IOException("Google: sin marca")
        val interno = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1]," +
            "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"$id\",$marca,\"$firma\"]"
        val peticion = "[[[\"Fbv4je\"," + JSONObject.quote(interno) + ",null,\"generic\"]]]"
        val c = URL("https://news.google.com/_/DotsSplashUi/data/batchexecute").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 6_000
            c.readTimeout = 6_000
            c.setRequestProperty("User-Agent", AGENTE)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            c.outputStream.use { it.write(("f.req=" + URLEncoder.encode(peticion, "UTF-8")).toByteArray()) }
            if (c.responseCode != 200) throw java.io.IOException("Google: batchexecute ${c.responseCode}")
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            return try {
                val bloque = resp.split("\n\n")[1].dropLast(2)
                val interior = org.json.JSONArray(bloque).getJSONArray(0).getString(2)
                org.json.JSONArray(interior).getString(1)
            } catch (e: Exception) {
                extraerUrlDeRespuesta(resp)
                    ?: throw java.io.IOException(
                        "Google: respuesta no legible: " + resp.take(120).replace(Regex("\\s+"), " ")
                    )
            }
        } finally {
            c.disconnect()
        }
    }

    /** Plan B: busca la dirección tras "garturlres" en el texto bruto de la respuesta. */
    private fun extraerUrlDeRespuesta(resp: String): String? {
        val i = resp.indexOf("garturlres")
        if (i < 0) return null
        var k = resp.indexOf("http", i)
        if (k < 0) return null
        val sb = StringBuilder()
        while (k < resp.length) {
            val ch = resp[k]
            if (ch == '"') break
            if (ch == '\\') {
                val n = resp.getOrNull(k + 1)
                when {
                    n == '"' -> break
                    n == '/' -> { sb.append('/'); k += 2; continue }
                    n == '\\' -> { k += 1; continue }
                    n == 'u' && k + 6 <= resp.length -> {
                        sb.append(resp.substring(k + 2, k + 6).toInt(16).toChar()); k += 6; continue
                    }
                    else -> { k += 1; continue }
                }
            }
            sb.append(ch)
            k++
        }
        return sb.toString().takeIf { it.startsWith("http") && !it.contains("news.google.com") }
    }

    /** Descarga una noticia y devuelve sus primeros párrafos como texto plano. */
    private suspend fun textoArticulo(enlace: String): String = withContext(Dispatchers.IO) {
        val c = URL(enlace).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 6_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", AGENTE)
            c.setRequestProperty("Accept-Language", "es,en;q=0.8")
            if (c.responseCode != 200) throw java.io.IOException("web ${c.responseCode}")
            val bytes = c.inputStream.use { it.readNBytesCompat(1_500_000) }
            val html = String(bytes, Charsets.UTF_8)
                .replace(Regex("(?is)<(script|style|noscript|nav|footer|header|aside)[^>]*>.*?</\\1>"), " ")
            val parrafos = Regex("(?is)<p[^>]*>(.*?)</p>").findAll(html)
                .map { limpiar(it.groupValues[1]) }
                .filter { it.length > 60 }
                .toList()
            val texto = parrafos.joinToString(" ").take(8000)
            if (texto.length <= 200) throw java.io.IOException("texto corto (${texto.length})")
            texto
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------- red

    private fun descargar(direccion: String): String {
        val c = URL(direccion).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", AGENTE)
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
