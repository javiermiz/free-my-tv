<div align="center">

**English** · [Español](README.es.md) · [Português](README.pt.md)

<img src="app/res/drawable-nodpi/icon.png" width="96" alt="">

# Free My TV · streaming edition

**An ad-free home screen for Google TV, in the style of the streaming apps.**

A featured title with its trailer, rows of picks from TMDB and from what you watch on Stremio, a tab per service, a page per show and a player of its own.

[Screenshots](#screenshots) · [Step-by-step install](SETUP.md) · [Settings](#settings) · [Going back](#going-back)

<img src=".github/screens/hero.webp" width="880" alt="Free My TV streaming edition: a featured title with its trailer, tabs per service and its own player">

</div>

> **Based on [Free My TV](https://github.com/simbasjungles/free-my-tv)** by simbasjungles and contributors (MIT license). This repository is a derivative work: it keeps the original's foundation (the safe home screen switch, the protected apps list, the watchdog, the Gradle-free build) and replaces the home screen entirely. This version has no website; everything you need is here and in [SETUP.md](SETUP.md).

## What you get

- **No ads.** No sponsored rows, banners or autoplaying promos.
- **A featured title with its trailer.** The focused title widens to 16:9 and plays its trailer inside the card, in your language when there is one.
- **A tab per service:** For you, Netflix, Disney+, Prime Video, Paramount+, Crunchyroll and Stremio, each with its own rows.
- **Continue watching, My list and New episodes** from your Stremio account, and picks based on what you watch.
- **A page per show** with resume, seasons, episodes and upcoming air dates.
- **Its own player** for Stremio titles, with audio, subtitles and version to choose from, and your place synced to Stremio (what you watch here shows up the same on Stremio and on your phone).
- **Opens every title in its app:** Netflix, Disney+ and Prime Video open right on the movie or show, Stremio on the episode.
- **Search, all your apps and the weather** for your city at the top right.
- **Safe.** No root. Nothing is uninstalled. One button brings back the factory home screen.

The whole app (home screen, player, search and settings) is in Spanish, English or Brazilian Portuguese (`language=es`, `en` or `pt`), and dubs and subtitles follow it: Latin American Spanish or Brazilian Portuguese first. The screenshots show it in Spanish.

## Screenshots

| | |
|---|---|
| <img src=".github/screens/01-inicio.webp" alt="Home"><br>**For you:** the featured title, with its trailer | <img src=".github/screens/02-continuar.webp" alt="Continue watching"><br>**Continue watching,** from your Stremio account |
| <img src=".github/screens/03-milista.webp" alt="My list"><br>**My list,** also from Stremio | <img src=".github/screens/04-nuevos.webp" alt="New episodes"><br>**New episodes** of your shows |
| <img src=".github/screens/05-novedades.webp" alt="New releases"><br>**New releases** | <img src=".github/screens/06-netflix.webp" alt="Netflix"><br>**A tab per service:** Netflix |
| <img src=".github/screens/07-netflix-filas.webp" alt="Netflix rows"><br>**Rows of its own** in every service | <img src=".github/screens/08-anime.webp" alt="Anime"><br>**Anime** (Crunchyroll) |
| <img src=".github/screens/09-anime-filas.webp" alt="Anime rows"><br>**Continue watching** in every service | <img src=".github/screens/10-menu.webp" alt="Menu"><br>**The menu:** search, shows, movies, apps and settings |
| <img src=".github/screens/11-apps.webp" alt="Apps"><br>**All your apps,** with search | <img src=".github/screens/13-buscar.webp" alt="Search"><br>**Search** |
| <img src=".github/screens/14-serie.webp" alt="Show page"><br>**A page per show:** resume, seasons and upcoming episodes | <img src=".github/screens/15-controles.webp" alt="Player"><br>**Its own player,** with your place synced to Stremio |
| <img src=".github/screens/16-audio-subs.webp" alt="Audio and subtitles"><br>**Audio, subtitles and version** | |

## Install

About 20 minutes the first time. You need a Google TV or Android TV (Android 12 or newer) and a computer on the same Wi-Fi.

**Every step, one by one, is in [SETUP.md](SETUP.md):** the tools, preparing the TV, each key (TMDB, Stremio, YouTube) and how to get it, building, installing, switching it on and what to do if something fails.

Using [Claude Code](https://claude.com/claude-code)? Clone the repository, open Claude Code in the folder and say *"Install Free My TV on my TV at 192.168.1.50. I live in Bogotá."* It installs what's missing, builds the app and puts it on your TV ([CLAUDE.md](CLAUDE.md) tells it how). You paste the keys into `config.properties` yourself.

## Settings

Everything lives in `config.properties`, created by the first `./build.sh` and never committed to git:

| Key | What it does | Default |
|---|---|---|
| `tmdb_key` | Picks, images, logos and trailers. **Required** for the home screen. | empty |
| `tmdb_region` | Your country (two letters): which title is on which service | `US` |
| `latitude`, `longitude` | Your city, for the weather and the night look | New York |
| `city` | The name next to the weather | empty |
| `language` | `es`, `en` or `pt`: the app, TMDB's titles and the preferred dubs and subtitles. `auto` follows the TV | `auto` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `stremio_key` | Continue watching, My list, picks and the player. Optional. | empty |
| `youtube_key` | Trailers in your language that TMDB doesn't have. Optional. | empty |
| `currency` | Currency for crypto prices (not shown on this home screen) | `USD` |
| `disable_assistant` | Also turns off Google Assistant | `false` |

How to get each key: [SETUP.md](SETUP.md#3-the-keys). Changed something? Run `./build.sh` and install again. Keep `freemytv.keystore`: updates are signed with it.

## Going back

On the TV: *Free My TV* → *Home Screen* → *Original*. The factory home screen, screensaver and every app come back exactly as they were. If the new home screen crashes three times in five minutes, it switches back by itself.

To remove it for good, switch to *Original* first, then uninstall *Free My TV* (Settings → Apps).

## What it changes

- It becomes the home screen and the screensaver.
- It turns off, without uninstalling:
  - the stock home screen and screensaver, with the remote shortcuts they run on some TVs (like the Netflix button on TCL);
  - Google TV's recovery home, Play Games and YouTube Music;
  - on TCL TVs, the TCL Channel, messages, store demo, hotel menu, manuals, mirroring and logging apps;
  - Google Assistant, only with `disable_assistant=true`.
- Picture, sound, inputs, remote pairing, Google services and the Play Store are on a protected list, checked before every change.

## Other TVs

It runs on Google TV and Android TV: TCL, Hisense, Sony, Philips, Xiaomi and more, plus streamers like Chromecast with Google TV, the Google TV Streamer, onn 4K and NVIDIA Shield. **Samsung, LG or any other TV:** plug a Google TV streamer into HDMI and install it there.

To adapt it to another brand, the lists are in the code:

| What | Where |
|---|---|
| Apps to turn off | `DEBLOAT` in [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) |
| Apps never to touch | `PROTECTED` in the same file |
| Apps on the shelf | `SHELF` in [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) |
| Your brand's live TV app | `LIVE_TV_APPS` in the same file |

## How it works

- **A plain Android app in Java, no Gradle.** [`build.sh`](build.sh) runs aapt2 → javac → d8 → zipalign → apksigner. The only library is ExoPlayer (Media3), for the player; `build.sh` downloads it by itself the first time.
- **It uses the TV's own ADB, from inside the TV.** The app connects to `127.0.0.1:5555` with its own key (that's the *Allow USB debugging?* prompt) and runs the same commands you would: `cmd package set-home-activity`, `settings put secure screensaver_components`, `pm disable-user`. Nothing listens to the network.
- **A safety net.** A watchdog in a separate process brings back the stock home screen after three crashes in five minutes, and a boot check keeps your choice after TV updates.

| File | What it is |
|---|---|
| [`FlixView.java`](app/src/io/freemytv/FlixView.java) | The streaming-style home screen |
| [`Recs.java`](app/src/io/freemytv/Recs.java) | Picks and trailers (TMDB, Stremio, YouTube, Wikidata) |
| [`Streams.java`](app/src/io/freemytv/Streams.java) | Streams and your place on Stremio |
| [`PlayerActivity.java`](app/src/io/freemytv/PlayerActivity.java) | The player |
| [`SearchActivity.java`](app/src/io/freemytv/SearchActivity.java) | Search |
| [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) | Switching between Free My TV and Original |
| [`Adb.java`](app/src/io/freemytv/Adb.java) | The TV's own ADB, from inside the TV |
| [`Watchdog.java`](app/src/io/freemytv/Watchdog.java) | The safety net |

## Privacy

No accounts of its own, no analytics, no tracking. The app only talks to TMDB, Open-Meteo (with the coordinates you set), Wikidata (to open each title in its app), YouTube (the trailers, and the search if you set `youtube_key`) and, if you set `stremio_key`, your Stremio account and its add-ons. Your keys and settings stay on your computer and your TV.

## Credits

- **[Free My TV](https://github.com/simbasjungles/free-my-tv)** by simbasjungles and contributors: the original project this version is based on (MIT).
- Built with [Claude Code](https://claude.com/claude-code).
- This product uses the TMDB API but is not endorsed or certified by [TMDB](https://www.themoviedb.org/).
- Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Title identifiers from [Wikidata](https://www.wikidata.org/) (CC0).
- Player: [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) (Apache 2.0).
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Font: [Inter](https://rsms.me/inter/) (SIL OFL 1.1).
- Not affiliated with Google, TCL, Hisense, Stremio or any streaming service. App names and logos belong to their owners.

## License

[MIT](LICENSE), like the original: the original project's copyright notice and license are kept.
