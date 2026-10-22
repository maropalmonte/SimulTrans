package com.example.simultrans

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Descarga el modelo de IA (2,6 GB) directamente desde la app, con progreso,
 * y lo deja listo en [destino]. Si se corta (sin red, app cerrada, cancelar),
 * conserva el archivo parcial ".part" y la siguiente vez continúa por donde iba.
 */
object ModelDownloader {
    const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    /** Por debajo de este tamaño el archivo no puede ser el modelo completo. */
    const val MIN_BYTES = 2_000_000_000L

    /** Espacio libre recomendado: el modelo más margen para el motor. */
    const val ESPACIO_NECESARIO = 3_500_000_000L

    fun parcial(destino: File): File = File(destino.parentFile, destino.name + ".part")

    suspend fun descargar(destino: File, onProgreso: (descargado: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val part = parcial(destino)
            var existente = if (part.exists()) part.length() else 0L

            var url = URL(MODEL_URL)
            var redirecciones = 0
            var conexion: HttpURLConnection
            while (true) {
                val c = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "SimulTrans-Android")
                    if (existente > 0) setRequestProperty("Range", "bytes=$existente-")
                }
                val code = c.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val destinoRedir = c.getHeaderField("Location")
                        ?: throw IOException("Redirección sin destino")
                    c.disconnect()
                    url = URL(url, destinoRedir)
                    if (++redirecciones > 6) throw IOException("Demasiadas redirecciones")
                    continue
                }
                if (code == 416 && existente > 0) {
                    // El parcial no encaja con el servidor: se empieza de cero.
                    c.disconnect()
                    part.delete()
                    existente = 0L
                    url = URL(MODEL_URL)
                    redirecciones = 0
                    continue
                }
                if (code != 200 && code != 206) {
                    c.disconnect()
                    throw IOException("El servidor respondió con el error $code")
                }
                conexion = c
                break
            }

            val reanuda = conexion.responseCode == 206
            if (!reanuda && existente > 0) {
                // El servidor ignoró el rango: se reinicia el archivo.
                part.delete()
                existente = 0L
            }
            val restante = conexion.contentLengthLong
            val total = if (restante > 0) existente + restante else -1L

            var hecho = existente
            var ultimoAviso = 0L
            conexion.inputStream.use { entrada ->
                java.io.FileOutputStream(part, reanuda).use { salida ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        salida.write(buffer, 0, n)
                        hecho += n
                        val ahora = System.currentTimeMillis()
                        if (ahora - ultimoAviso > 300) {
                            ultimoAviso = ahora
                            onProgreso(hecho, total)
                        }
                    }
                }
            }
            conexion.disconnect()
            onProgreso(hecho, total)

            if (part.length() < MIN_BYTES || (total > 0 && part.length() != total)) {
                // Corte a mitad: se conserva el parcial para reanudar.
                throw IOException("La descarga se interrumpió. Vuelve a pulsar para continuar.")
            }
            if (destino.exists()) destino.delete()
            if (!part.renameTo(destino)) throw IOException("No se pudo guardar el modelo descargado")
        }
}
