package com.example.simultrans

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Búsqueda en Internet para el Asistente IA, usando la API pública de
 * Wikipedia (sin cuenta, sin clave y sin coste). Solo se envía el texto de
 * búsqueda; nunca la conversación, la voz ni los archivos adjuntos.
 */
object BuscadorWeb {
    data class Resultado(val titulo: String, val extracto: String, val url: String)

    suspend fun buscar(consulta: String, idioma: String): List<Resultado> =
        withContext(Dispatchers.IO) {
            val r = consultar(consulta, idioma)
            if (r.isEmpty() && idioma != "en") consultar(consulta, "en") else r
        }

    private fun consultar(consulta: String, idioma: String): List<Resultado> {
        val q = URLEncoder.encode(consulta, "UTF-8")
        val direccion = "https://$idioma.wikipedia.org/w/api.php?action=query&format=json" +
            "&generator=search&gsrsearch=$q&gsrlimit=3&redirects=1" +
            "&prop=extracts%7Cinfo&inprop=url&exintro=1&explaintext=1&exchars=1200&exlimit=3"
        val c = URL(direccion).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "SimulTrans/1.1 (https://empresa.maropal.es)")
            if (c.responseCode != 200) return emptyList()
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val paginas = json.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
            val lista = mutableListOf<Pair<Int, Resultado>>()
            for (clave in paginas.keys()) {
                val p = paginas.getJSONObject(clave)
                val extracto = p.optString("extract").trim()
                if (extracto.isEmpty()) continue
                lista.add(
                    p.optInt("index", 99) to
                        Resultado(p.optString("title"), extracto, p.optString("fullurl"))
                )
            }
            return lista.sortedBy { it.first }.map { it.second }
        } finally {
            c.disconnect()
        }
    }

    /** Bloque de contexto que se antepone a la pregunta del usuario. */
    fun construirContexto(resultados: List<Resultado>): String {
        val sb = StringBuilder(
            "Resultados de Wikipedia obtenidos ahora mismo para esta pregunta. " +
                "Úsalos como fuente principal si son relevantes. Pueden no incluir noticias " +
                "de última hora: si no bastan para responder, dilo con claridad.\n"
        )
        resultados.forEachIndexed { i, r ->
            sb.append("\n[").append(i + 1).append("] ").append(r.titulo).append(": ").append(r.extracto)
        }
        return sb.toString()
    }

    fun fuentes(resultados: List<Resultado>): String =
        resultados.joinToString("\n") { "• ${it.titulo} — ${it.url}" }
}
