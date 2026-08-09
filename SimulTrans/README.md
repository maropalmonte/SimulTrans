# SimulTrans — Traducción simultánea Español ⇄ Inglés (on-device)

App Android en Kotlin que usa **LiteRT-LM** con el modelo **Gemma 4 E2B**
para traducir conversaciones habladas entre español e inglés, completamente
en el dispositivo (sin conexión a internet ni servidores externos).

## Cómo funciona

1. Pulsas el botón del idioma en el que vas a hablar (🇪🇸 o 🇬🇧).
2. `SpeechRecognizer` (Android nativo) convierte tu voz en texto.
3. El texto se envía al modelo Gemma 4 E2B vía LiteRT-LM con una instrucción
   de traducción fija.
4. La traducción se muestra en pantalla y se lee en voz alta con
   `TextToSpeech`.

Todo el pipeline corre localmente: no se envía audio ni texto a ningún
servidor.

## 1. Abrir el proyecto

Abre la carpeta `SimulTrans/` con **Android Studio (Koala o posterior)**.
Deja que Gradle sincronice; descargará automáticamente la dependencia
`com.google.ai.edge.litertlm:litertlm-android`.

## 2. Conseguir el modelo Gemma 4 E2B

El modelo (~2-3 GB) **no va dentro del APK**. Todo se hace desde el propio
teléfono, sin ordenador ni `adb`:

1. En el navegador del móvil, ve a
   <https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm>
   y acepta la licencia de Gemma (necesitas una cuenta de Hugging Face).
2. Descarga el archivo `.litertlm` (por ejemplo la variante int4, más ligera
   y rápida en móvil). Quedará en la carpeta "Descargas" del teléfono.
3. Abre la app SimulTrans y pulsa **"📂 Elegir archivo del modelo"**.
4. Busca el archivo `.litertlm` que acabas de descargar y selecciónalo.
   La app lo copiará a su almacenamiento interno (puede tardar unos
   minutos, ya que pesa varios GB) y luego cargará el modelo
   automáticamente.

> **Nota:** puedes borrar el archivo de "Descargas" después, la app ya
> tiene su propia copia.

> **Alternativa (descarga automática):** en vez de descargarlo a mano en el
> navegador, se puede añadir un descargador dentro de la app que baje el
> `.litertlm` la primera vez que se abre usando un token de acceso de
> Hugging Face (porque el modelo tiene licencia "gated"). No lo incluí en
> esta versión para no tener que gestionar tokens/secrets dentro del
> código, pero es una mejora natural más adelante.

## 3. Compilar y ejecutar

Ejecuta normalmente desde Android Studio (▶) en un dispositivo físico
(recomendado — el emulador va muy lento para un LLM) con Android 8.0+
(minSdk 26).

## Estructura del proyecto

```
app/src/main/java/com/example/simultrans/
  MainActivity.kt        -> UI, micrófono, texto a voz
  TranslationEngine.kt    -> envoltorio sobre el Engine de LiteRT-LM
app/src/main/res/
  layout/activity_main.xml
  values/ (strings, colors, themes)
  drawable/ (burbujas de chat, icono)
```

## Posibles mejoras

- **GPU backend**: cambia `Backend.CPU()` por `Backend.GPU()` en
  `TranslationEngine.kt` para mayor velocidad (requiere declarar
  `libOpenCL.so` como `uses-native-library` en el manifest — ver docs de
  LiteRT-LM).
- **Traducción de textos largos / documentos**: reutilizar el mismo patrón
  de `translate()` con textos más largos.
- **Detección automática de idioma** en vez de botones, usando un único
  prompt "detecta el idioma y tradúcelo al otro".
- **Streaming de la traducción** en vez de esperar la respuesta completa,
  usando `sendMessageAsync(...): Flow<Message>` (ya soportado por la API).

## Recursos

- LiteRT-LM (repo oficial): <https://github.com/google-ai-edge/LiteRT-LM>
- Guía Kotlin/Android: <https://ai.google.dev/edge/litert-lm/android>
- Modelo Gemma 4 E2B: <https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm>
