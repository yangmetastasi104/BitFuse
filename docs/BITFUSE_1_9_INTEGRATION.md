# BitFuse / BitChord 1.9 integration

Upstream: `https://github.com/kushagrasinghx/BitChord`, release `v1.9`,
commit `04f64c9f11bd2714d01bbc6da401f146f3d9399f`.

This integration merges the actual release, rather than changing only the
manifest version. Upstream includes the video player, Top Picks, offline
playlists/folders, onboarding, Wear OS companion, loop mixing and bit-perfect
output implementations. Its removal of the desktop module follows upstream.

## BitFuse adaptations

- Phone and watch production application ID: `com.music.bitfuse`; same release
  signing configuration for Data Layer compatibility.
- Parametric EQ retained alongside Dynamic and Manual, including live updates
  to both playback processors.
- Spotify cookie/session persistence, provider catalogue search, provider
  diagnostics, Spotify scrobbling and Connect presence retained.
- Upstream playback resolver enabled for video and Automix. Source substitutes
  and read-ahead obey the provider order and do not replace music videos.
- Independent appearance settings: Home Top Picks, immersive player layout,
  and square versus portrait motion artwork. These are presentation controls,
  not a complete restoration of every 1.8 screen.
- Recents precedes Top Picks; BitFuse logo used by shared Home/Replay UI.
- New downloads are written to `Music/BitFuse`; the pre-existing `Music/BitChord`
  folder is still read for library listing and duplicate detection, so files
  downloaded before the rename are not hidden.
- Update endpoint points to BitFuse, not the official BitChord APK.
- Removed the misleading system-wide EQ toggle. The app DSP does not process
  other applications' audio.
- CMake Oboe download uses the direct codeload endpoint with the upstream hash;
  external native downloads have a bounded timeout.

## Verification and outstanding work

Verified by build, not by device:

- `assembleProdRelease` + `wearos:assembleProdRelease` succeed; APKs are
  release-signed (v2/v3), `com.music.bitfuse`, versionCode 35 / versionName 1.9,
  label BitFuse, and the phone APK embeds a byte-identical copy of the watch APK.
- 104 unit tests green: parametric EQ, float DSP chain, bit-perfect PCM, DAC
  format conversion, Spotify cookies, provider governance, download store.
- Multi-volume delivery to Telegram was round-trip checked: reassembling the
  split APK yields a byte-identical file.

No phone was connected via ADB and no emulator AVD was configured. USB/DAP
exact output, Wear OS pairing, Home/player toggles and video playback require
real-hardware verification.

Spotify library album and artist links open internal BitFuse detail pages.
Album tracks and artist top tracks are matched to the configured playback
providers, like playlists; they never stream from Spotify.

The merged Spotify Home feed includes the synced library, mixes, saved albums,
followed artists, editorial picks, charts, top tracks and recently played
tracks. It keeps cached/local sections if live endpoints are refused.

Replay stories export a 1080×1920 PNG with the card's artwork-derived mesh
gradient and use Android's image share chooser, including Instagram when it is
installed. No credentials or Instagram API are used.

No global/system-wide EQ implementation is claimed: Android app DSP cannot
process another app's audio without privileged/root-level audio routing.
