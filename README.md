<div align="center">

<img src="app/res/drawable-nodpi/icon.png" width="96" alt="">

# Free My TV · versión streaming

**Un home sin anuncios para Google TV, al estilo de las apps de streaming.**

Un destacado con tráiler, filas de recomendaciones de TMDB y de lo que ves en Stremio, una pestaña por servicio, una pantalla por serie y un reproductor propio.

[Capturas](#capturas) · [Instalación paso a paso](SETUP.md) · [Ajustes](#ajustes) · [Volver al original](#volver-al-original)

<img src=".github/screens/hero.webp" width="880" alt="Free My TV versión streaming: un destacado con tráiler, pestañas por servicio y el reproductor propio">

</div>

> **Basado en [Free My TV](https://github.com/simbasjungles/free-my-tv)** de simbasjungles y sus colaboradores (licencia MIT). Este repositorio es un trabajo derivado: toma su base (el cambio seguro de home, la lista de apps protegidas, el watchdog, el build sin Gradle) y le cambia el home por completo. Esta versión no tiene sitio web; todo lo que necesitas está aquí y en [SETUP.md](SETUP.md).

## Qué tiene

- **Sin anuncios.** Ni filas patrocinadas, ni banners, ni promos que se reproducen solas.
- **Un destacado con tráiler.** El título enfocado se agranda a 16:9 y reproduce su tráiler adentro, en español latino si existe.
- **Una pestaña por servicio:** Para ti, Netflix, Disney+, Prime Video, Paramount+, Crunchyroll y Stremio, cada una con sus propias filas.
- **Continuar viendo, Mi lista y Nuevos episodios** desde tu cuenta de Stremio, y recomendaciones según lo que ves.
- **Una pantalla por serie** con reanudar, temporadas, capítulos y próximos estrenos.
- **Un reproductor propio** para los títulos de Stremio, con audio, subtítulos y versión a elegir, y tu avance sincronizado con Stremio (lo que ves aquí aparece igual en Stremio y en tu teléfono).
- **Abre cada título en su app:** Netflix, Disney+ y Prime Video abren directo en la película o serie, Stremio en el capítulo.
- **Buscar, todas tus apps y el clima** de tu ciudad arriba a la derecha.
- **Seguro.** Sin root. No desinstala nada. Un botón devuelve el home de fábrica.

## Capturas

| | |
|---|---|
| <img src=".github/screens/01-inicio.webp" alt="Inicio"><br>**Para ti:** el destacado, con su tráiler | <img src=".github/screens/02-continuar.webp" alt="Continuar viendo"><br>**Continuar viendo,** desde tu cuenta de Stremio |
| <img src=".github/screens/03-milista.webp" alt="Mi lista"><br>**Mi lista,** también de Stremio | <img src=".github/screens/04-nuevos.webp" alt="Nuevos"><br>**Nuevos episodios** de tus series |
| <img src=".github/screens/05-novedades.webp" alt="Novedades"><br>**Novedades:** los estrenos | <img src=".github/screens/06-netflix.webp" alt="Netflix"><br>**Una pestaña por servicio:** Netflix |
| <img src=".github/screens/07-netflix-filas.webp" alt="Filas de Netflix"><br>**Filas propias** en cada servicio | <img src=".github/screens/08-anime.webp" alt="Anime"><br>**Anime** (Crunchyroll) |
| <img src=".github/screens/09-anime-filas.webp" alt="Filas de anime"><br>**Continuar viendo** en cada servicio | <img src=".github/screens/10-menu.webp" alt="Menú"><br>**El menú:** buscar, series, películas, apps y ajustes |
| <img src=".github/screens/11-apps.webp" alt="Apps"><br>**Todas tus apps,** con buscador | <img src=".github/screens/13-buscar.webp" alt="Buscar"><br>**Buscar** |
| <img src=".github/screens/14-serie.webp" alt="Serie"><br>**Una pantalla por serie:** reanudar, temporadas y próximos capítulos | <img src=".github/screens/15-controles.webp" alt="Reproductor"><br>**El reproductor propio,** con tu avance sincronizado a Stremio |
| <img src=".github/screens/16-audio-subs.webp" alt="Audio y subtítulos"><br>**Audio, subtítulos y versión** | |

## Instalación

Unos 20 minutos la primera vez. Necesitas una TV con Google TV o Android TV (Android 12 o más nuevo) y una computadora en la misma red Wi-Fi.

**Todos los pasos, uno por uno, están en [SETUP.md](SETUP.md):** las herramientas, preparar la TV, cada llave (TMDB, Stremio, YouTube) con cómo conseguirla, compilar, instalar, activarlo y qué hacer si algo falla.

¿Usas [Claude Code](https://claude.com/claude-code)? Clona el repositorio, abre Claude Code en la carpeta y dile *"Instala Free My TV en mi TV en 192.168.1.50. Vivo en Bogotá."* Instala lo que falte, compila y lo pone en tu TV ([CLAUDE.md](CLAUDE.md) le dice cómo). Las llaves las pegas tú en `config.properties`.

## Ajustes

Todo vive en `config.properties`, que crea el primer `./build.sh` y que nunca se sube a git:

| Clave | Para qué | Por defecto |
|---|---|---|
| `tmdb_key` | Recomendaciones, imágenes, logos y tráilers. **Necesaria** para el home. | vacía |
| `tmdb_region` | Tu país en dos letras: qué título está en qué servicio | `US` |
| `latitude`, `longitude` | Tu ciudad, para el clima y el modo noche | Nueva York |
| `city` | El nombre junto al clima | vacío |
| `language` | `es` para títulos y home en español (`auto`, `en` o `pt` también sirven) | `auto` |
| `temperature` | `C`, `F` o `auto` | `auto` |
| `stremio_key` | Continuar viendo, Mi lista, recomendaciones y el reproductor propio. Opcional. | vacía |
| `youtube_key` | Tráilers en español latino que TMDB no tiene. Opcional. | vacía |
| `currency` | Moneda de los precios cripto (no se muestran en este home) | `USD` |
| `disable_assistant` | También apaga el Asistente de Google | `false` |

Cómo conseguir cada llave: [SETUP.md](SETUP.md#3-las-llaves). ¿Cambiaste algo? Vuelve a correr `./build.sh` e instala otra vez. Guarda `freemytv.keystore`: las actualizaciones se firman con él.

## Volver al original

En la TV: *Free My TV* → *Home Screen* → *Original*. El home de fábrica, el protector de pantalla y todas las apps vuelven tal como estaban. Si el home nuevo falla tres veces en cinco minutos, vuelve al original solo.

Para quitarlo del todo, primero cambia a *Original* y después desinstala *Free My TV* (Ajustes → Apps).

## Qué cambia en la TV

- Se vuelve el home y el protector de pantalla.
- Apaga, sin desinstalar:
  - el home y el protector de pantalla de fábrica, con los atajos del control que dependen de ellos en algunas TVs (como el botón de Netflix en TCL);
  - el home de recuperación de Google TV, Play Juegos y YouTube Music;
  - en TVs TCL, el canal de TCL, mensajes, demo de tienda, menú de hotel, manuales, duplicación de pantalla y apps de registro;
  - el Asistente de Google, solo con `disable_assistant=true`.
- Imagen, sonido, entradas, emparejamiento del control, servicios de Google y Play Store están en una lista protegida que se revisa antes de cada cambio.

## Otras TVs

Funciona en Google TV y Android TV: TCL, Hisense, Sony, Philips, Xiaomi y otras, y en streamers como Chromecast con Google TV, Google TV Streamer, onn 4K y NVIDIA Shield. **Samsung, LG u otra TV:** conecta un streamer con Google TV por HDMI e instálalo ahí.

Para adaptarlo a otra marca, las listas están en el código:

| Qué | Dónde |
|---|---|
| Apps que se apagan | `DEBLOAT` en [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) |
| Apps que nunca se tocan | `PROTECTED` en el mismo archivo |
| Apps de la fila | `SHELF` en [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) |
| La app de TV en vivo de tu marca | `LIVE_TV_APPS` en el mismo archivo |

## Cómo funciona

- **Una app de Android en Java, sin Gradle.** [`build.sh`](build.sh) corre aapt2 → javac → d8 → zipalign → apksigner. La única librería es ExoPlayer (Media3), para el reproductor; `build.sh` la descarga sola la primera vez.
- **Usa el ADB de la propia TV, desde la TV.** La app se conecta a `127.0.0.1:5555` con su propia llave (por eso la TV pregunta *¿Permitir depuración USB?*) y corre los mismos comandos que correrías tú: `cmd package set-home-activity`, `settings put secure screensaver_components`, `pm disable-user`. Nada escucha en la red.
- **Una red de seguridad.** Un watchdog en otro proceso vuelve al home de fábrica tras tres fallas en cinco minutos, y una revisión al encender mantiene tu elección después de las actualizaciones de la TV.

| Archivo | Qué es |
|---|---|
| [`FlixView.java`](app/src/io/freemytv/FlixView.java) | El home al estilo streaming |
| [`Recs.java`](app/src/io/freemytv/Recs.java) | Las recomendaciones y los tráilers (TMDB, Stremio, YouTube, Wikidata) |
| [`Streams.java`](app/src/io/freemytv/Streams.java) | Los streams y tu avance en Stremio |
| [`PlayerActivity.java`](app/src/io/freemytv/PlayerActivity.java) | El reproductor |
| [`SearchActivity.java`](app/src/io/freemytv/SearchActivity.java) | Buscar |
| [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) | El cambio entre Free My TV y Original |
| [`Adb.java`](app/src/io/freemytv/Adb.java) | El ADB de la TV, desde la TV |
| [`Watchdog.java`](app/src/io/freemytv/Watchdog.java) | La red de seguridad |

## Privacidad

Sin cuentas propias, sin analíticas, sin rastreo. La app solo habla con TMDB, Open-Meteo (con las coordenadas que pongas), Wikidata (para abrir cada título en su app), YouTube (los tráilers, y la búsqueda si pones `youtube_key`) y, si pones `stremio_key`, con tu cuenta de Stremio y sus add-ons. Tus llaves y ajustes se quedan en tu computadora y en tu TV.

## Créditos

- **[Free My TV](https://github.com/simbasjungles/free-my-tv)** de simbasjungles y colaboradores: el proyecto original en el que se basa esta versión (MIT).
- Hecho con [Claude Code](https://claude.com/claude-code).
- Este producto usa la API de TMDB pero no está avalado ni certificado por [TMDB](https://www.themoviedb.org/).
- Clima de [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Identificadores de títulos de [Wikidata](https://www.wikidata.org/) (CC0).
- Reproductor: [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) (Apache 2.0).
- Íconos: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Fuente: [Inter](https://rsms.me/inter/) (SIL OFL 1.1).
- Sin relación con Google, TCL, Hisense, Stremio ni ningún servicio de streaming. Los nombres y logos de las apps son de sus dueños.

## Licencia

[MIT](LICENSE), igual que el original: el aviso de copyright y la licencia del proyecto original se mantienen.
