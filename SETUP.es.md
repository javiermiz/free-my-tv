# Cómo instalar Free My TV · versión streaming

[English](SETUP.md) · **Español** · [Português](SETUP.pt.md)

Esta es una versión de [Free My TV](https://github.com/simbasjungles/free-my-tv) (de simbasjungles, licencia MIT) con un
home al estilo de las apps de streaming. Aquí están **todos** los pasos, de cero a tenerlo en tu TV. La primera vez toma
unos 20 minutos; casi todo es esperar descargas.

Resumen:

1. [Instala las herramientas en tu computadora](#1-las-herramientas)
2. [Prepara la TV](#2-prepara-la-tv)
3. [Consigue las llaves](#3-las-llaves) (TMDB obligatoria; Stremio y YouTube opcionales)
4. [Llena `config.properties`](#4-llena-configproperties)
5. [Compila e instala](#5-compila-e-instala)
6. [Actívalo en la TV](#6-actívalo-en-la-tv)
7. [Actualizar, volver al original y problemas comunes](#7-actualizar-volver-al-original-y-problemas)

> **Tus llaves son tuyas y secretas.** Van solo en `config.properties`, que nunca se sube a git (está en `.gitignore`).
> No las pegues en issues, chats ni capturas.

---

## 1. Las herramientas

Necesitas, en la computadora:

- **git**, para bajar el código.
- **Un JDK 17 o más nuevo** (Java), para compilar.
- **Las herramientas de línea de comandos del Android SDK**: `build-tools` 34 o más nuevo y la plataforma `android-34`.
- **adb**, para instalar la app en la TV por Wi-Fi.
- **Python 3**, solo si vas a sacar la llave de Stremio con correo y contraseña.

### macOS

1. Si no tienes [Homebrew](https://brew.sh), abre la app *Terminal* y pega la línea de instalación de su página. Al
   terminar, sigue las dos líneas que te imprime ("Next steps") para agregarlo al PATH.
2. Instala Java, el SDK y adb:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   ```
3. Descarga build-tools y la plataforma de Android (acepta las licencias con `y` cuando te pregunte):
   ```bash
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
4. Comprueba que todo está:
   ```bash
   java -version
   ```
   ```bash
   adb version
   ```
   `java -version` debe decir 17 o más. Si dice otra versión (o tienes varias), usa la 17 al compilar con
   `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./build.sh`.

`git` y `python3` ya vienen con macOS: la primera vez que los uses puede pedirte instalar las *Command Line Tools*;
acepta.

### Linux

1. Instala git, OpenJDK 17, adb y Python 3 con tu gestor de paquetes. En Ubuntu o Debian:
   ```bash
   sudo apt install git openjdk-17-jdk adb python3 unzip curl
   ```
2. Descarga las *Command line tools only* de [developer.android.com/studio](https://developer.android.com/studio#command-tools),
   descomprímelas en `~/Android/Sdk/cmdline-tools/latest/` y corre:
   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   `build.sh` encuentra el SDK solo en `~/Android/Sdk`. Si lo pusiste en otro lado, exporta `ANDROID_HOME` con esa ruta.

### Windows

Instala [WSL](https://learn.microsoft.com/es-es/windows/wsl/install) (Ubuntu) y sigue los pasos de Linux dentro de él.

### Baja el código

```bash
git clone https://github.com/javiermiz/free-my-tv
```
```bash
cd free-my-tv
```

---

## 2. Prepara la TV

1. **Activa el modo desarrollador:** Ajustes → Sistema → Información → baja hasta *Compilación del SO de Android TV* y
   presiona OK **7 veces** seguidas, hasta que diga que ya eres desarrollador. (En algunas TVs está en Ajustes →
   Preferencias del dispositivo → Información.)
2. **Activa la depuración:** Ajustes → Sistema → Opciones para desarrolladores → activa **Depuración por USB**. Si tu TV
   tiene aparte *Depuración por red* o *Depuración inalámbrica*, actívala también.
3. **Anota la IP de la TV:** Ajustes → Red e Internet → tu red Wi-Fi (o Ethernet) → *Dirección IP*. Algo como
   `192.168.1.50`. La computadora y la TV deben estar en la misma red.
4. **Instala las apps que quieras abrir desde el home** desde la Play Store de la TV: Netflix, Disney+, Prime Video,
   Paramount+, Crunchyroll y **Stremio**. Las que no tengas simplemente no aparecen.
5. **Si vas a usar Stremio**, abre Stremio en la TV e inicia sesión con **la misma cuenta** de la que vas a sacar la
   llave (paso 3).

---

## 3. Las llaves

| Llave | ¿Necesaria? | Qué pasa sin ella |
|---|---|---|
| TMDB (`tmdb_key`) | **Sí** | El home queda sin títulos, imágenes ni tráilers. |
| Stremio (`stremio_key`) | No | No hay Continuar viendo, Mi lista, Nuevos episodios, recomendaciones según lo que ves ni reproductor propio. |
| YouTube (`youtube_key`) | No | Si TMDB no tiene tráiler en tu idioma, se usa lo que TMDB tenga (un clip, el opening de un anime o el tráiler original sin sonido). |

### TMDB (`tmdb_key`): obligatoria, gratis

TMDB es la base de datos de películas y series de donde salen las recomendaciones, los pósters, los logos, los tráilers y
en qué servicio está cada título.

1. Crea una cuenta en [themoviedb.org/signup](https://www.themoviedb.org/signup) y confirma tu correo (te llega un
   enlace).
2. Inicia sesión y ve a [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api) (tu avatar → Ajustes →
   API).
3. Pulsa **Crear** (o *Request an API Key*) y elige **Developer**.
4. Acepta los términos y llena el formulario: tipo de uso *Personal*, nombre de la app (por ejemplo "Free My TV"),
   URL (puede ser `https://github.com/javiermiz/free-my-tv`) y un resumen corto ("Home para mi TV"). Tus datos de
   contacto los pide TMDB; no se comparten con nadie más.
5. Al enviarlo verás dos valores: **API Key** (32 caracteres) y **API Read Access Token** (muy largo, empieza con
   `eyJ`). Cualquiera de los dos sirve; copia uno.

### Stremio (`stremio_key`): opcional

Es la llave de tu sesión de Stremio. Con ella el home lee tu biblioteca (Continuar viendo, Mi lista, Nuevos episodios),
te recomienda según lo último que viste, y el reproductor propio guarda tu avance en tu cuenta (lo que ves en la TV
aparece igual en Stremio y en tu teléfono). No es tu contraseña: es una llave de sesión; si algún día quieres invalidarla,
cierra la sesión de Stremio en todos tus dispositivos.

Si aún no tienes cuenta, créala gratis en [stremio.com](https://www.stremio.com).

**Si entras a Stremio con correo y contraseña** (lo más fácil):

1. Primero corre `./build.sh` una vez (paso 5) para que exista `config.properties`. Fallará la compilación o no, da
   igual: lo que importa es que cree el archivo.
2. En la carpeta del proyecto:
   ```bash
   python3 tools/stremio_key.py
   ```
3. Escribe tu correo y tu contraseña de Stremio (la contraseña no se ve mientras escribes). Se mandan solo a
   `api.strem.io`, no se guardan ni se imprimen. La llave queda escrita sola en `config.properties`.

**Si entras con Facebook o Google** (no tienes contraseña de Stremio):

1. En la computadora, abre [web.stremio.com](https://web.stremio.com) en Chrome, Edge o Firefox e inicia sesión como
   siempre.
2. Abre la consola del navegador: **F12** (en Mac, **Cmd+Opción+J** en Chrome o **Cmd+Opción+K** en Firefox) → pestaña
   *Console*. Si el navegador te pide escribir `allow pasting` antes de pegar, escríbelo y pulsa Enter.
3. Pega esto y pulsa Enter:
   ```js
   (() => { for (const k of Object.keys(localStorage)) { try { const o = JSON.parse(localStorage.getItem(k)); const key = o?.auth?.key || o?.profile?.auth?.key; if (key) { copy(key); return "Copiada (de " + k + ")"; } } catch (e) {} } return "No encontrada"; })()
   ```
4. Si dice *Copiada*, la llave está en tu portapapeles: pégala después de `stremio_key=` en `config.properties`. Si
   dice *No encontrada*, recarga la página con la sesión iniciada y vuelve a intentar.

**Para el reproductor propio** además necesitas, en tu cuenta de Stremio, add-ons que den **enlaces directos** (HTTP).
El reproductor usa los add-ons que ya tengas instalados en tu cuenta y elige el mejor según tu `language`: doblado en ese idioma
(español latino o portugués de Brasil primero, luego el de España o Portugal), si no el original con subtítulos en ese
idioma, luego lo demás. En inglés deja el audio original. Los torrents no se reproducen aquí (necesitan
el motor de Stremio): si un título solo tiene torrents, el home lo abre en la app de Stremio. Usa solo add-ons y fuentes
que tengas derecho a ver.

### YouTube (`youtube_key`): opcional, gratis

Sirve para buscar en YouTube el tráiler en español latino (o subtitulado) cuando TMDB no lo tiene.

1. Entra a [console.cloud.google.com](https://console.cloud.google.com) con tu cuenta de Google. Si es la primera vez,
   acepta los términos (no pide tarjeta).
2. Arriba, en el selector de proyectos → **Proyecto nuevo** → ponle un nombre (por ejemplo "free-my-tv") → **Crear**, y
   selecciónalo.
3. Menú ☰ → **APIs y servicios** → **Biblioteca** → busca **YouTube Data API v3** → ábrela → **Habilitar**.
4. Menú ☰ → **APIs y servicios** → **Credenciales** → **Crear credenciales** → **Clave de API**. Copia la clave (empieza
   con `AIza`).
5. Recomendado: en esa clave pulsa **Editar clave de API** → *Restricciones de API* → **Restringir clave** → marca solo
   *YouTube Data API v3* → **Guardar**. Así, si alguien la ve, no sirve para nada más.

Es gratis: alcanza para unas 100 búsquedas de tráiler al día. Cuando se acaban, el home usa lo que TMDB tenga y vuelve a
buscar al día siguiente. Los tráilers encontrados se guardan en la TV, así que solo se busca una vez por título.

---

## 4. Llena `config.properties`

Si no existe todavía, corre `./build.sh` una vez: lo crea copiando `config.example.properties`. Ábrelo con cualquier
editor de texto (en Mac: `open -e config.properties`) y llena:

| Clave | Qué poner | Ejemplo |
|---|---|---|
| `tmdb_key` | Tu llave de TMDB (paso 3) | `eyJhbGciOi…` |
| `tmdb_region` | Tu país en dos letras (decide qué título está en qué servicio) | `MX`, `CO`, `AR`, `CL`, `PE`, `ES`, `US` |
| `latitude`, `longitude` | Las coordenadas de tu ciudad, en grados decimales | Ciudad de México: `19.4326`, `-99.1332` |
| `city` | El nombre que se ve junto al clima | `CDMX` |
| `language` | El idioma de la app y de títulos, tráilers, doblaje y subtítulos: `es`, `en` o `pt` (Brasil) | `es` |
| `temperature` | `C` o `F` | `C` |
| `stremio_key` | Tu llave de Stremio (paso 3), o vacío | |
| `youtube_key` | Tu llave de YouTube (paso 3), o vacío | `AIza…` |
| `disable_assistant` | `true` también apaga el Asistente de Google (el botón de micrófono deja de funcionar) | `false` |

**Para sacar las coordenadas:** abre [Google Maps](https://maps.google.com), haz clic derecho en tu ciudad y pulsa el
primer renglón (los dos números): se copian. El primero es `latitude`, el segundo `longitude`. Basta con el centro de la
ciudad; no pongas tu casa.

Escribe cada valor justo después del `=`, sin comillas ni espacios. Ejemplo:

```properties
tmdb_key=eyJhbGciOi...
tmdb_region=MX
latitude=19.4326
longitude=-99.1332
city=CDMX
language=es
temperature=C
```

---

## 5. Compila e instala

1. **Compila** en la carpeta del proyecto:
   ```bash
   ./build.sh
   ```
   La primera vez descarga el reproductor (ExoPlayer) a `libs/` y crea tu llave de firma `freemytv.keystore`. Debe
   terminar con `Built FreeMyTV.apk`. Si dice que no encuentra el JDK, usa
   `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./build.sh` (Mac) o instala el JDK 17 (Linux).
2. **Conéctate a la TV** (cambia la IP por la tuya):
   ```bash
   adb connect 192.168.1.50:5555
   ```
   La TV muestra *¿Permitir depuración USB?*: marca **Permitir siempre desde esta computadora** y pulsa **Aceptar**.
   Comprueba con `adb devices`: debe decir `device` (si dice `unauthorized`, acepta el aviso en la TV).
3. **Instala:**
   ```bash
   adb install -r FreeMyTV.apk
   ```
   Debe decir `Success`.

**Guarda `freemytv.keystore`** (por ejemplo en tu gestor de contraseñas o un respaldo): las actualizaciones tienen que
firmarse con la misma llave. Si la pierdes, tendrás que desinstalar la app y volver a instalarla. Tampoco se sube a git.

---

## 6. Actívalo en la TV

1. En la TV abre la app **Free My TV** (está en tus apps).
2. Entra a **Inicio** y elige **Free My TV**.
3. La TV pregunta otra vez *¿Permitir depuración USB?*: es la app usando el ADB de la propia TV para hacer el cambio.
   Marca **Permitir siempre** y acepta.
4. Pulsa el botón de inicio del control: ya estás en el home nuevo.

La primera carga de recomendaciones tarda unos 3 minutos y se repite cada 6 horas, en segundo plano. Para ver qué hizo
el cambio: `adb logcat -s FreeMyTV`.

No cambies el modo desde la computadora: hazlo siempre desde la app en la TV.

---

## 7. Actualizar, volver al original y problemas

**Actualizar** (cambiaste `config.properties` o bajaste código nuevo con `git pull`): repite el paso 5 (`./build.sh`,
`adb connect`, `adb install -r FreeMyTV.apk`). No hace falta volver a activarlo.

**Volver al original:** en la TV, *Free My TV* → *Inicio* → *Original*. El home de fábrica, el protector de
pantalla y todas las apps vuelven tal como estaban. Para quitarlo del todo, primero cambia a *Original* y después
desinstala *Free My TV* desde Ajustes → Apps. Si el home nuevo falla tres veces en cinco minutos, vuelve al original
solo.

| Problema | Qué hacer |
|---|---|
| `adb connect` dice `failed to connect` o `timed out` | Revisa la IP y que la TV y la computadora estén en la misma red. Comprueba que la *Depuración por USB* (y la *por red*, si existe) sigan activas. Apaga y prende la TV y vuelve a intentar. |
| `adb devices` dice `unauthorized` | Acepta el aviso en la TV. Si no aparece: `adb disconnect`, luego otra vez `adb connect`. |
| `adb install` dice `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | La app instalada se firmó con otra llave (perdiste `freemytv.keystore`). Cambia a *Original*, desinstala Free My TV y vuelve a instalar. |
| `Android SDK not found` | Instala el SDK (paso 1) o exporta `ANDROID_HOME` con su ruta. |
| `Android platform not found` | Corre `sdkmanager "platforms;android-34"`. |
| `JDK not found` | Instala el JDK 17 o usa `JAVA_HOME=…` (paso 5). |
| `latitude must be a number` | En `config.properties`, usa punto decimal y sin comillas: `19.4326`. |
| El home sale vacío o dice que falta la llave de TMDB | Revisa `tmdb_key`, recompila e instala. Espera unos 3 minutos a la primera carga. |
| No hay Continuar viendo | Revisa `stremio_key` y que tengas algo empezado en Stremio. Si cambiaste la contraseña de Stremio o cerraste sesión en todos lados, saca la llave otra vez. |
| Un título de Stremio no se reproduce en el reproductor propio | Ese título no tiene enlaces directos en tus add-ons; ábrelo en la app de Stremio. Los 4K y Dolby Vision se prueban al final porque algunas TVs no los decodifican. |
| Los tráilers salen sin sonido o en otro idioma | TMDB no tiene uno en tu idioma. Agrega `youtube_key`; si ya la tienes, quizá se acabaron las búsquedas del día. |
| El botón de Netflix (u otro atajo) del control no hace nada | En algunas TVs (TCL) esos atajos dependen del home de fábrica; descansan mientras Free My TV está activo y vuelven con *Original*. |

## Notas

- **TVs Hisense** (y otras sin `cmd role get-role-holders`): ya está resuelto; el cambio de home detecta el home de
  fábrica de otra forma.
- **Nunca subas** `config.properties`, `freemytv.keystore` ni ninguna llave. `.gitignore` ya los excluye.
- ¿Algo no está aquí? Abre un issue en este repositorio (sin pegar tus llaves).
