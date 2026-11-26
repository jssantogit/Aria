<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/branding/notation-2.1/aria-lockup-light.svg">
    <source media="(prefers-color-scheme: light)" srcset="docs/branding/notation-2.1/aria-lockup-dark.svg">
    <img src="docs/branding/notation-2.1/aria-lockup-dark.svg" alt="Aria" width="360">
  </picture>

  <h3>Your music. Your library. Your way.</h3>

  <p>A personal, local-first music player for Android.<br>
  An independent library, flexible playback sources and a thoughtfully crafted listening experience.</p>

  <p>
    <a href="https://github.com/jssantogit/Aria/releases"><img src="https://img.shields.io/badge/Releases-Aria-B74D0A?style=for-the-badge&logo=github&logoColor=white&labelColor=26211D" alt="Aria releases"></a>
    <a href="https://github.com/jssantogit/Aria/actions"><img src="https://img.shields.io/badge/Android-8%2B-B74D0A?style=for-the-badge&logo=android&logoColor=white&labelColor=26211D" alt="Android 8 and newer"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-B74D0A?style=for-the-badge&labelColor=26211D" alt="GPLv3 license"></a>
  </p>

  <p>
    <a href="#features">Features</a> ·
    <a href="#download">Download</a> ·
    <a href="#development">Development</a> ·
    <a href="#credits-and-license">Credits &amp; license</a>
  </p>
</div>

<br>

<div align="center">
  <h2 id="features">Features</h2>
</div>

- **Your library** — browse local music, albums, artists and playlists, pin favorites, and organize your collection with ratings and smart playlists.
- **Flexible sources** — connect compatible music servers and playback providers independently of your library. Source availability, authentication and audio quality depend on the configured service.
- **Playback control** — manage the queue, crossfade, equalizer and audio output.
- **Lyrics** — synchronized lyrics, multiple lyrics providers, translation and timing adjustment.
- **Listening history** — revisit your music through listening statistics and Replay.
- **Personalization** — light and dark themes, artwork-driven player backgrounds, and 13 selectable launcher icons.
- **Backup** — export and restore supported library listening statistics and application settings.

> [!NOTE]
> Aria is actively being developed. The next-generation provider engine and other experimental integrations are undergoing separate validation; their availability is not guaranteed in the current development build.

<!--
Add real Aria screenshots here after physical-device review.
Suggested selection: Home, Library, Search, album detail, Now Playing, Lyrics.
Do not reuse upstream screenshots or mockups as evidence of the released UI.
-->

<br>

<div align="center">
  <h2 id="download">Download</h2>
  <p>Android 8.0+ · ARM64, ARM32 and x86-64</p>

  <a href="https://github.com/jssantogit/Aria/releases"><img src="https://img.shields.io/badge/GitHub-Releases-B74D0A?style=for-the-badge&logo=github&logoColor=white&labelColor=26211D" alt="View Aria releases"></a>
  <a href="https://github.com/jssantogit/Aria/actions"><img src="https://img.shields.io/badge/Development-Builds-B74D0A?style=for-the-badge&logo=githubactions&logoColor=white&labelColor=26211D" alt="View development builds"></a>
</div>

The first official Aria release is pending physical-device validation and production signing. Development APKs may be available as temporary GitHub Actions artifacts; they are intended for testing, not stable distribution.

Aria uses its own Android application identity and release channel. Do not install upstream BitChord APKs as Aria updates. Development and production installations are separate.

<br>

<div align="center">
  <h2 id="development">Development</h2>
</div>

Aria is developed exclusively for Android, using Kotlin, Jetpack Compose and native audio components. Shared JVM tests support the Android application; they do not represent a separate desktop product.

**Requirements:** JDK 17, Android SDK (compileSdk 37, targetSdk 36, minSdk 26), Android NDK and CMake 3.22.1.

Build a development APK:

```bash
./gradlew :app:assembleDevDebug
```

Run the Android and shared tests:

```bash
./gradlew :app:testDevDebugUnitTest :shared:jvmTest :sharedUi:jvmTest
```

See [Contributing](CONTRIBUTING.md), [Maintainers](MAINTAINERS.md), [Additional documentation](ADDITIONAL.md), and the [Notation visual identity](docs/branding/notation-2.1/README.md).

<br>

<div align="center">
  <h2>Contribute</h2>
</div>

Bug reports, translations and focused improvements are welcome. Open an [issue](https://github.com/jssantogit/Aria/issues) to discuss a change, or follow the [contribution guide](CONTRIBUTING.md) before opening a pull request.

<br>

<div align="center">
  <h2 id="credits-and-license">Credits &amp; license</h2>
</div>

Aria is an independent derivative of [BitChord](https://github.com/kushagrasinghx/BitChord), originally created by Kushagra Singh and developed with its contributors. Upstream copyright and license notices, authorship and third-party attributions remain intact. The original project's maintainers do not operate or endorse Aria.

- **Lyrics animation heritage** — credit to [binimum](https://github.com/binimum/am-lyrics).
- **Lyrics providers** — credit to [lrc.red](https://lrc.red), [BiniLyrics](https://github.com/binimum), [BetterLyrics](https://github.com/better-lyrics/better-lyrics), [PaxSenix](https://lyrics.paxsenix.org), [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [Unison](https://unison.boidu.dev), [Megalobiz](https://www.megalobiz.com), [KuGou](https://www.kugou.com), [LRCLIB](https://lrclib.net), [Musixmatch](https://www.musixmatch.com) and [Genius](https://genius.com).

Licensed under the GNU General Public License v3.0; see [LICENSE](LICENSE). Distributed derivative builds must comply with the applicable license obligations, including corresponding source. Aria is not affiliated with the music services it interoperates with.

