# Installing Free My TV · streaming edition

**English** · [Español](SETUP.es.md)

This is a version of [Free My TV](https://github.com/simbasjungles/free-my-tv) (by simbasjungles, MIT license) with a
streaming-style home screen. Here are **all** the steps, from zero to having it on your TV. The first time takes about 20
minutes, mostly waiting for downloads.

In short:

1. [Install the tools on your computer](#1-the-tools)
2. [Prepare the TV](#2-prepare-the-tv)
3. [Get the keys](#3-the-keys) (TMDB required; Stremio and YouTube optional)
4. [Fill in `config.properties`](#4-fill-in-configproperties)
5. [Build and install](#5-build-and-install)
6. [Switch it on on the TV](#6-switch-it-on-on-the-tv)
7. [Updating, going back and common problems](#7-updating-going-back-and-problems)

> **Your keys are yours and secret.** They only go in `config.properties`, which is never committed to git (it's in
> `.gitignore`). Don't paste them into issues, chats or screenshots.

---

## 1. The tools

On your computer you need:

- **git**, to get the code.
- **A JDK 17 or newer** (Java), to build.
- **The Android SDK command-line tools**: `build-tools` 34 or newer and the `android-34` platform.
- **adb**, to install the app on the TV over Wi-Fi.
- **Python 3**, only if you'll get your Stremio key with an email and password.

### macOS

1. If you don't have [Homebrew](https://brew.sh), open the *Terminal* app and paste the install line from its page. When
   it's done, run the two lines it prints under "Next steps" to add it to your PATH.
2. Install Java, the SDK and adb:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   ```
3. Download the Android build-tools and platform (accept the licenses with `y` when asked):
   ```bash
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
4. Check everything is there:
   ```bash
   java -version
   ```
   ```bash
   adb version
   ```
   `java -version` must say 17 or newer. If it says another version (or you have several), build with version 17 using
   `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./build.sh`.

`git` and `python3` come with macOS: the first time you use them it may ask to install the *Command Line Tools*; accept.

### Linux

1. Install git, OpenJDK 17, adb and Python 3 from your package manager. On Ubuntu or Debian:
   ```bash
   sudo apt install git openjdk-17-jdk adb python3 unzip curl
   ```
2. Download the *Command line tools only* from [developer.android.com/studio](https://developer.android.com/studio#command-tools),
   unzip them into `~/Android/Sdk/cmdline-tools/latest/` and run:
   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   `build.sh` finds the SDK by itself in `~/Android/Sdk`. If you put it somewhere else, export `ANDROID_HOME` with that
   path.

### Windows

Install [WSL](https://learn.microsoft.com/windows/wsl/install) (Ubuntu) and follow the Linux steps inside it.

### Get the code

```bash
git clone https://github.com/javiermiz/free-my-tv
```
```bash
cd free-my-tv
```

---

## 2. Prepare the TV

1. **Turn on developer mode:** Settings → System → About → scroll to *Android TV OS build* and press OK **7 times** in a
   row, until it says you're a developer. (On some TVs it's under Settings → Device Preferences → About.)
2. **Turn on debugging:** Settings → System → Developer options → turn on **USB debugging**. If your TV also has a
   separate *Network debugging* or *Wireless debugging*, turn that on too.
3. **Write down the TV's IP address:** Settings → Network & Internet → your Wi-Fi (or Ethernet) network → *IP address*.
   Something like `192.168.1.50`. The computer and the TV must be on the same network.
4. **Install the apps you want to open from the home screen** from the TV's Play Store: Netflix, Disney+, Prime Video,
   Paramount+, Crunchyroll and **Stremio**. The ones you don't have simply don't show up.
5. **If you'll use Stremio**, open Stremio on the TV and sign in with **the same account** you'll take the key from
   (step 3).

---

## 3. The keys

| Key | Required? | What happens without it |
|---|---|---|
| TMDB (`tmdb_key`) | **Yes** | The home screen has no titles, images or trailers. |
| Stremio (`stremio_key`) | No | No Continue watching, My list, New episodes, picks based on what you watch, or player. |
| YouTube (`youtube_key`) | No | When TMDB has no trailer in your language, it uses whatever TMDB has (a clip, an anime's opening, or the original trailer muted). |

### TMDB (`tmdb_key`): required, free

TMDB is the movie and TV database the picks, posters, logos, trailers and "which service has it" come from.

1. Create an account at [themoviedb.org/signup](https://www.themoviedb.org/signup) and confirm your email (you'll get a
   link).
2. Sign in and go to [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api) (your avatar → Settings →
   API).
3. Click **Create** (or *Request an API Key*) and choose **Developer**.
4. Accept the terms and fill in the form: type of use *Personal*, an application name (say "Free My TV"), a URL (it can
   be `https://github.com/javiermiz/free-my-tv`) and a short summary ("Home screen for my TV"). TMDB asks for your
   contact details; they aren't shared with anyone else.
5. Once submitted you'll see two values: **API Key** (32 characters) and **API Read Access Token** (very long, starts
   with `eyJ`). Either one works; copy one.

### Stremio (`stremio_key`): optional

It's your Stremio session key. With it the home screen reads your library (Continue watching, My list, New episodes),
picks titles like the ones you watched last, and the player saves your place to your account (what you watch on the TV
shows up the same on Stremio and on your phone). It isn't your password: it's a session key. To revoke it some day, sign
out of Stremio on all your devices.

If you don't have an account yet, create one for free at [stremio.com](https://www.stremio.com).

**If you sign in to Stremio with an email and password** (easiest):

1. Run `./build.sh` once first (step 5) so `config.properties` exists. Whether the build succeeds doesn't matter here;
   what matters is that it creates the file.
2. In the project folder:
   ```bash
   python3 tools/stremio_key.py
   ```
3. Type your Stremio email and password (the password isn't shown as you type). They're sent only to `api.strem.io`, and
   never saved or printed. The key is written into `config.properties` for you.

**If you sign in with Facebook or Google** (you have no Stremio password):

1. On your computer, open [web.stremio.com](https://web.stremio.com) in Chrome, Edge or Firefox and sign in as usual.
2. Open the browser console: **F12** (on a Mac, **Cmd+Option+J** in Chrome or **Cmd+Option+K** in Firefox) → the
   *Console* tab. If the browser asks you to type `allow pasting` first, type it and press Enter.
3. Paste this and press Enter:
   ```js
   (() => { for (const k of Object.keys(localStorage)) { try { const o = JSON.parse(localStorage.getItem(k)); const key = o?.auth?.key || o?.profile?.auth?.key; if (key) { copy(key); return "Copied (from " + k + ")"; } } catch (e) {} } return "Not found"; })()
   ```
4. If it says *Copied*, the key is on your clipboard: paste it after `stremio_key=` in `config.properties`. If it says
   *Not found*, reload the page while signed in and try again.

**For the player** you also need add-ons on your Stremio account that give **direct links** (HTTP). The player uses the
add-ons already installed on your account and picks the best stream: Latin American Spanish audio, then the original
with Spanish subtitles, then Spain's Spanish, then anything else. Torrents don't play here (they need Stremio's engine):
if a title only has torrents, the home screen opens it in the Stremio app. Only use add-ons and sources you have the
right to watch.

### YouTube (`youtube_key`): optional, free

It's used to search YouTube for the trailer in Latin American Spanish (or with Spanish subtitles) when TMDB doesn't have
it.

1. Go to [console.cloud.google.com](https://console.cloud.google.com) with your Google account. If it's your first time,
   accept the terms (no card needed).
2. At the top, in the project picker → **New project** → give it a name (say "free-my-tv") → **Create**, and select it.
3. Menu ☰ → **APIs & Services** → **Library** → search for **YouTube Data API v3** → open it → **Enable**.
4. Menu ☰ → **APIs & Services** → **Credentials** → **Create credentials** → **API key**. Copy the key (it starts with
   `AIza`).
5. Recommended: on that key click **Edit API key** → *API restrictions* → **Restrict key** → tick only *YouTube Data API
   v3* → **Save**. That way, if anyone sees it, it's useless for anything else.

It's free: enough for about 100 trailer searches a day. When they run out, the home screen uses what TMDB has and
searches again the next day. Trailers it finds are saved on the TV, so each title is only searched once.

---

## 4. Fill in `config.properties`

If it doesn't exist yet, run `./build.sh` once: it creates it by copying `config.example.properties`. Open it in any text
editor (on a Mac: `open -e config.properties`) and fill in:

| Key | What to put | Example |
|---|---|---|
| `tmdb_key` | Your TMDB key (step 3) | `eyJhbGciOi…` |
| `tmdb_region` | Your country in two letters (decides which title is on which service) | `US`, `MX`, `CO`, `AR`, `ES`, `GB` |
| `latitude`, `longitude` | Your city's coordinates, in decimal degrees | Mexico City: `19.4326`, `-99.1332` |
| `city` | The name shown next to the weather | `CDMX` |
| `language` | `es` for titles, trailers and home screen in Spanish; `en` for English | `es` |
| `temperature` | `C` or `F` | `C` |
| `stremio_key` | Your Stremio key (step 3), or empty | |
| `youtube_key` | Your YouTube key (step 3), or empty | `AIza…` |
| `disable_assistant` | `true` also turns off Google Assistant (the remote's mic button stops working) | `false` |

**To get the coordinates:** open [Google Maps](https://maps.google.com), right-click your city and click the first line
(the two numbers): they're copied. The first is `latitude`, the second `longitude`. The city center is enough; don't use
your home.

Write each value right after the `=`, with no quotes or spaces. For example:

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

## 5. Build and install

1. **Build** in the project folder:
   ```bash
   ./build.sh
   ```
   The first time it downloads the player (ExoPlayer) into `libs/` and creates your signing key `freemytv.keystore`. It
   must end with `Built FreeMyTV.apk`. If it can't find the JDK, use `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./build.sh`
   (Mac) or install JDK 17 (Linux).
2. **Connect to the TV** (swap in your TV's IP):
   ```bash
   adb connect 192.168.1.50:5555
   ```
   The TV shows *Allow USB debugging?*: tick **Always allow from this computer** and press **OK**. Check with
   `adb devices`: it must say `device` (if it says `unauthorized`, accept the prompt on the TV).
3. **Install:**
   ```bash
   adb install -r FreeMyTV.apk
   ```
   It must say `Success`.

**Keep `freemytv.keystore` safe** (in your password manager or a backup, say): updates have to be signed with the same
key. If you lose it, you'll have to uninstall the app and install it again. It isn't committed to git either.

---

## 6. Switch it on on the TV

1. On the TV, open the **Free My TV** app (it's in your apps).
2. Go to **Home Screen** and choose **Free My TV**.
3. The TV asks *Allow USB debugging?* once more: it's the app using the TV's own ADB to make the switch. Tick **Always
   allow** and accept.
4. Press the remote's home button: you're on the new home screen.

The first load of picks takes about 3 minutes and repeats every 6 hours, in the background. To see what the switch did:
`adb logcat -s FreeMyTV`.

Don't switch modes from the computer: always do it from the app on the TV.

---

## 7. Updating, going back and problems

**Updating** (you changed `config.properties` or got new code with `git pull`): repeat step 5 (`./build.sh`,
`adb connect`, `adb install -r FreeMyTV.apk`). No need to switch it on again.

**Going back:** on the TV, *Free My TV* → *Home Screen* → *Original*. The factory home screen, screensaver and every app
come back exactly as they were. To remove it for good, switch to *Original* first, then uninstall *Free My TV* from
Settings → Apps. If the new home screen crashes three times in five minutes, it switches back by itself.

| Problem | What to do |
|---|---|
| `adb connect` says `failed to connect` or `timed out` | Check the IP and that the TV and the computer are on the same network. Check *USB debugging* (and *Network debugging*, if there is one) is still on. Turn the TV off and on and try again. |
| `adb devices` says `unauthorized` | Accept the prompt on the TV. If it doesn't show up: `adb disconnect`, then `adb connect` again. |
| `adb install` says `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app was signed with another key (you lost `freemytv.keystore`). Switch to *Original*, uninstall Free My TV and install again. |
| `Android SDK not found` | Install the SDK (step 1) or export `ANDROID_HOME` with its path. |
| `Android platform not found` | Run `sdkmanager "platforms;android-34"`. |
| `JDK not found` | Install JDK 17 or use `JAVA_HOME=…` (step 5). |
| `latitude must be a number` | In `config.properties`, use a decimal point and no quotes: `19.4326`. |
| The home screen is empty or says the TMDB key is missing | Check `tmdb_key`, rebuild and install. Give the first load about 3 minutes. |
| No Continue watching | Check `stremio_key` and that you have something started on Stremio. If you changed your Stremio password or signed out everywhere, get the key again. |
| A Stremio title won't play in the player | That title has no direct links in your add-ons; open it in the Stremio app. 4K and Dolby Vision streams are tried last because some TVs can't decode them. |
| Trailers are in English or muted | TMDB has no Spanish one. Add `youtube_key`; if you already have it, the day's searches may have run out. |
| The remote's Netflix button (or another shortcut) does nothing | On some TVs (TCL) those shortcuts belong to the factory home screen; they rest while Free My TV is on and come back with *Original*. |

## Notes

- **Hisense TVs** (and others without `cmd role get-role-holders`): already handled; the switch finds the factory home
  screen another way.
- **Never commit** `config.properties`, `freemytv.keystore` or any key. `.gitignore` already leaves them out.
- Something missing here? Open an issue on this repository (without pasting your keys).
