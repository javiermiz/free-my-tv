# Cómo instalar esta versión

Esta es una versión de [Free My TV](https://github.com/simbasjungles/free-my-tv) con un home al estilo de las apps de
streaming: un destacado con tráiler, filas de recomendaciones (de TMDB y de lo que ves en Stremio), logos de cada título,
una pantalla por serie con sus temporadas y capítulos, y "Continuar viendo" desde tu cuenta de Stremio.

Las llaves son tuyas y secretas: van solo en `config.properties`, que nunca se sube (está en `.gitignore`).

## 1. Lo que necesitas

- Una TV con Google TV o Android TV (Android 12 o más nuevo) y una computadora en la misma red Wi-Fi.
- En la computadora: un JDK 17+, las herramientas del Android SDK (build-tools 34+, platform android-34) y `adb`.
  En macOS:
  ```bash
  brew install --cask temurin android-commandlinetools android-platform-tools
  ```
  ```bash
  sdkmanager "build-tools;34.0.0" "platforms;android-34"
  ```

## 2. Prepara la TV

1. Ajustes → Sistema → Información → presiona *Compilación del SO de Android TV* 7 veces (activa el modo desarrollador).
2. Ajustes → Sistema → Opciones para desarrolladores → activa **Depuración por USB** (y la depuración por red si la TV la
   tiene aparte).
3. Anota la IP de la TV (Ajustes → Red e Internet).

## 3. Configura `config.properties`

Corre `./build.sh` una vez: crea `config.properties` a partir de `config.example.properties`. Ábrelo y llena:

| Clave | Para qué | Cómo conseguirla |
|---|---|---|
| `tmdb_key` | Recomendaciones, imágenes, logos, tráilers | Gratis: [themoviedb.org](https://www.themoviedb.org/settings/api) → Configuración → API. Sirve el "API Read Access Token" o la "API Key". |
| `tmdb_region` | En qué servicio está cada título | Tu país en dos letras, p. ej. `MX`. |
| `latitude`, `longitude`, `city` | Clima y modo noche | Las coordenadas de tu ciudad en grados decimales (p. ej. Cancún: `21.1619`, `-86.8515`). |
| `language` | Títulos y textos | `es` para español. |
| `temperature` | Grados | `C` o `F`. |
| `stremio_key` | "Continuar viendo" y recomendaciones según lo que ves | Ver abajo. Opcional. |
| `youtube_key` | Tráilers en español latino que TMDB no tiene | Ver abajo. Opcional. |

### La llave de Stremio (`stremio_key`)

- **Si entras a Stremio con correo y contraseña:** `python3 tools/stremio_key.py`. Pide tus datos, los manda solo a
  Stremio y guarda la llave en `config.properties`.
- **Si entras con Facebook (o Google):** abre [web.stremio.com](https://web.stremio.com) con tu sesión iniciada, abre la
  consola del navegador (F12 → Console) y pega:
  ```js
  (() => { for (const k of Object.keys(localStorage)) { try { const o = JSON.parse(localStorage.getItem(k)); const key = o?.auth?.key || o?.profile?.auth?.key; if (key) { copy(key); return "Copiada (de " + k + ")"; } } catch (e) {} } return "No encontrada"; })()
  ```
  La llave queda en tu portapapeles: pégala después de `stremio_key=`.

### La llave de YouTube (`youtube_key`)

1. Entra a [console.cloud.google.com](https://console.cloud.google.com), crea un proyecto.
2. APIs y servicios → Biblioteca → **YouTube Data API v3** → Habilitar.
3. APIs y servicios → Credenciales → Crear credenciales → Clave de API. Cópiala después de `youtube_key=`.

Es gratis: alcanza para unas 100 búsquedas de tráiler al día. Cuando se acaban, el home usa clips o el tráiler sin sonido
y vuelve a buscar al día siguiente.

## 4. Compila e instala

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./build.sh
```
```bash
adb connect <IP-de-la-TV>:5555
```
```bash
adb install -r FreeMyTV.apk
```

La primera vez la TV pregunta *¿Permitir depuración USB?*: acepta y marca *Permitir siempre*. `build.sh` crea
`freemytv.keystore` la primera vez: guárdalo, las actualizaciones deben firmarse con la misma llave (tampoco se sube).

Cada vez que cambies `config.properties` o el código, vuelve a compilar e instalar.

## 5. Actívalo en la TV

En la TV abre **Free My TV** → *Pantalla de inicio* → *Free My TV*. La TV pide otra vez permiso de depuración (la app
usa el ADB de la propia TV): acéptalo. Para volver al inicio original: *Pantalla de inicio* → *Original*.

`adb logcat -s FreeMyTV` muestra lo que hizo.

## Notas

- **TVs Hisense** (y otras sin `cmd role get-role-holders`): ya está resuelto, el cambio de inicio detecta el home de otra
  forma.
- **Stremio:** el home abre Stremio directo en la película o en el capítulo que elijas. Instálalo en la TV desde la Play
  Store.
- **La primera carga** de recomendaciones tarda unos 3 minutos y se repite cada 6 horas, en segundo plano.
- **Nunca subas** `config.properties`, `*.keystore` ni ninguna llave.
