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

    class Busqueda(val contexto: String, val fuentes: String, val diag: String)

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
        val noticias = (listaBing.take(6) + listaGoogle.take(5))
            .filter { vistos.add(it.titulo.lowercase().take(50)) }
            .take(8)
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
        }.map { it.await() }.filter { it.second.length > 200 }.take(3)
        if (noticias.isEmpty() && articulos.isEmpty()) return@coroutineScope null

        val hoy = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.forLanguageTag(localeTag)).format(Date())
        val sb = StringBuilder()
        sb.append("Fecha de hoy: ").append(hoy).append(".\n")
        sb.append("Resultados de Internet obtenidos ahora mismo para la pregunta del usuario. ")
        sb.append("Úsalos como fuente principal si son relevantes para la pregunta (si no tienen que ver, ignóralos). Las noticias están ordenadas por relevancia, no por fecha: ")
        sb.append("fíjate en su fecha para saber si son de hoy, de ayer o más antiguas. ")
        sb.append("Cuando respondas, incluye los datos concretos que aparezcan (nombres de jugadores, minutos, cifras, lugares). No inventes datos y NUNCA uses marcadores como [nombre]: ")
        sb.append("si los resultados no contienen la respuesta, di claramente que no la has encontrado.\n")
        if (noticias.isNotEmpty()) {
            sb.append("\nNOTICIAS:")
            noticias.forEach {
                sb.append("\n- ").append(it.titulo)
                if (it.medio.isNotBlank()) sb.append(" (").append(it.medio).append(")")
                if (it.fecha.isNotBlank()) sb.append(" [").append(it.fecha).append("]")
                if (it.resumen.isNotBlank()) sb.append(": ").append(it.resumen)
            }
        }
        if (textos.isNotEmpty()) {
            sb.append("\n\nTEXTO DE LAS NOTICIAS (para dar detalles):")
            textos.forEach { (n, t) -> sb.append("\n[").append(n.titulo).append("] ").append(t) }
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
            (if (ultimoError.isNotBlank()) " · error: $ultimoError" else "")
        Busqueda(sb.toString(), fuentes, diag)
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
        val base = pregunta.replace(Regex("[¿?¡!.,;:()\"]"), " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase() !in PALABRAS_VACIAS }
            .take(6).toMutableList()
        val l = pregunta.lowercase()
        if (Regex("quedó|quedo|resultado|ganó|perdió|empat|marcador").containsMatchIn(l)) base.add("resultado")
        if (Regex("marcó|gol|goleador|anotó").containsMatchIn(l)) base.add("goles")
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
                throw java.io.IOException("Google: respuesta no legible")
            }
        } finally {
            c.disconnect()
        }
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
            val texto = parrafos.joinToString(" ").take(1800)
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
