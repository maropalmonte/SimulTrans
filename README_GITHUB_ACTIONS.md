# Compilar el APK en la nube con GitHub Actions

Este repo incluye `.github/workflows/build.yml`, que compila automáticamente
un APK de depuración cada vez que subes cambios a `main` (o lo lanzas a
mano). No necesitas instalar nada en tu ordenador.

## Pasos

1. **Crea un repositorio nuevo en GitHub** (puede ser privado), por ejemplo
   `simultrans-app`.

2. **Sube el contenido de esta carpeta** (`SimulTrans/`, incluyendo la
   carpeta oculta `.github/`) a ese repositorio:

   ```bash
   cd SimulTrans
   git init
   git add .
   git commit -m "Proyecto inicial SimulTrans"
   git branch -M main
   git remote add origin https://github.com/TU_USUARIO/simultrans-app.git
   git push -u origin main
   ```

3. En GitHub, entra en la pestaña **Actions** del repo. Debería aparecer el
   workflow **"Build APK"** ejecutándose automáticamente (tarda unos
   3-5 minutos: instala el SDK de Android, Gradle y compila).

4. Cuando termine (✅ verde), entra en esa ejecución y baja hasta
   **Artifacts**. Descarga `SimulTrans-debug-apk` — es un `.zip` que
   contiene `app-debug.apk`.

5. **Instálalo en tu móvil — sin cable, sin adb, sin ordenador:**
   - Abre `github.com` con el navegador de tu propio teléfono, entra en la
     misma pantalla de Actions → Artifacts y descarga
     `SimulTrans-debug-apk` directamente ahí.
   - Es un `.zip`: ábrelo con el gestor de archivos del teléfono (o "Files
     by Google") para extraer `app-debug.apk`.
   - Toca el `.apk` para instalarlo. La primera vez Android te pedirá
     permitir "Instalar apps desconocidas" para el navegador o gestor de
     archivos que estés usando — actívalo solo para esa app si quieres.

## Notas

- Es un **APK de depuración** (`assembleDebug`), sin firmar para
  distribución en Play Store — perfecto para instalarlo tú mismo y probarlo.
  Si más adelante quieres publicarlo, hay que generar una keystore de
  firma y cambiar el workflow a `assembleRelease`.
- Recuerda que el **modelo Gemma 4 E2B sigue sin ir dentro del APK**
  (pesa varios GB). Tras instalar la app, descárgalo con el navegador del
  móvil y selecciónalo desde el botón "Elegir archivo del modelo" dentro
  de la propia app — ver `README.md` principal.
- El workflow instala Gradle directamente en el runner (no usa el
  `gradlew` del proyecto), así que no hace falta que generes el wrapper
  tú mismo.
