<div align="center">

<img src="docs/branding/notation-2.1/aria-icon.svg" alt="Aria app icon" width="160" />

# Aria

Personal, Android-only music player. Local-first library, provider-independent catalog and playback.

[![License: GPLv3](https://img.shields.io/badge/license-GPLv3-blue.svg)](LICENSE)

</div>

## Development

Aria is developed exclusively for Android. Desktop, Wear OS and Android TV are not supported product targets. Shared JVM tests remain relevant to Android.

Use JDK 17 and the Android SDK (compileSdk 37, minSdk 26).

```bash
./gradlew :app:assembleDevDebug
./gradlew :app:testDevDebugUnitTest :shared:jvmTest :sharedUi:jvmTest
```

Development status and APKs: see [Aria pull requests](https://github.com/jssantogit/Aria/pulls) and [Actions](https://github.com/jssantogit/Aria/actions). Do not install upstream APKs as Aria updates.

See [CONTRIBUTING.md](CONTRIBUTING.md), [MAINTAINERS.md](MAINTAINERS.md), and [additional documentation](ADDITIONAL.md).

## Provenance and license

Aria is an independent derivative of [BitChord](https://github.com/kushagrasinghx/BitChord), originally created by Kushagra Singh and developed with its contributors. Upstream copyright and license notices, authorship and third-party attributions remain intact. The original project's maintainers do not operate or endorse Aria.

- **Lyrics animation heritage** — credit to [binimum](https://github.com/binimum/am-lyrics).
- **Lyrics providers** — credit to [lrc.red](https://lrc.red), [BiniLyrics](https://github.com/binimum), [BetterLyrics](https://github.com/better-lyrics/better-lyrics), [PaxSenix](https://lyrics.paxsenix.org), [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [Unison](https://unison.boidu.dev), [Megalobiz](https://www.megalobiz.com), [KuGou](https://www.kugou.com), [LRCLIB](https://lrclib.net), [Musixmatch](https://www.musixmatch.com) and [Genius](https://genius.com).

Licensed under the GNU General Public License v3.0; see [LICENSE](LICENSE). Distributed derivative builds must comply with the applicable license obligations, including corresponding source. Aria is not affiliated with the music services it interoperates with.
