# Changelog

All notable changes to the StreamVault HaP Plugin will be documented here.

## [1.2.2] - 2026-06-28

### Added

- Local TS proxy (startTsfProxy) in `handlePreparePlayback` that strips AceStream 72-byte framing headers from streams before forwarding to ExoPlayer, using raw TCP sockets instead of HttpURLConnection
- Triple 0x47 sync-byte verification at 188-byte intervals to handle AceStream framing headers of any size
- Batch packet writes to reduce syscall overhead during TS streaming
- Automatic retry mechanism (up to 60 attempts, 2s interval) when the HaP proxy resets the connection — the ExoPlayer socket stays alive during retries
- HTTP redirect following (302) in the proxy thread
- `PeerResultInfo.errorResult()` public factory method for creating error results from outside the package
- Diagnostic logging with `HaP-TSF` tag for TS proxy debugging

### Fixed

- Channel list height now uses WRAP_CONTENT instead of dp(320), fixing scrolling on phone form factors where nested ScrollViews intercepted touch events
- Test errors now store a `PeerResultInfo` in `peerResults` map so each channel row displays the error (red pill + error text) instead of only showing it in the feedback area

## [1.2.1] - 2026-06-01

### Added

- Added transparent support for `api.acestream.me/all` and `api.acestream.me/search` sources, formatting AceStream API JSON results as M3U channels internally.

## [1.2.0] - 2026-06-01

### Added

- Added standalone launcher support for phones and Android TV/Leanback so HaP can be opened without StreamVault.
- Added an External player server mode that enables LAN exposure, starts HaP, and remembers the mode for automatic startup after boot or app update.
- Added local and LAN M3U/EPG URL rows in the configuration UI for players such as TiviMate, OTT, and other IPTV clients.
- Added the installed app version next to the HaP title in the configuration UI.

### Changed

- Updated app labeling and plugin metadata to describe HaP as both a StreamVault companion and an external IPTV player server.
- Refreshed the launcher icon and Android TV banner with HaP lettering and multimedia branding.
- Made Channel status collapsed by default.

## [1.1.5] - 2026-05-18

### Fixed

- Fixed a race condition on first start where the HTTP proxy could fail to open because AceServe's API port (62062) was not yet ready when the HTTP port (6878) responded first. The supervisor now requires both ports to be accepting connections before starting the proxy.

## [1.1.4] - 2026-05-17

### Added

- Transparent support for M3U sources protected by the TestCookie-Nginx JavaScript challenge. Sources that respond with a `slowAES`-based page now have the AES-CBC cookie puzzle solved locally using `javax.crypto` (no new dependencies) and are retried automatically with the resulting `__test` cookie. Solved cookies are cached per hostname for six hours, matching the server-side TTL. Both the runtime M3U downloader (`NativeHttpBridge`) and the source validator (`SourceValidator`) participate in challenge resolution.

## [1.1.3] - 2026-05-14

### Changed

- Advertise a canonical `x-tvg-url` in generated HaP playlists and serve `/aio/epg.xml` as a local XMLTV endpoint for StreamVault EPG sync.
- Preserve EPG URLs declared by user M3U sources and route StreamVault EPG requests to the first XMLTV source, falling back to a valid empty XMLTV document when no source EPG is available.

## [1.1.2] - 2026-05-14

### Changed

- Improved M3U source feedback so validation errors and successful additions are shown next to the form.
- Added direct gateway fallback for `dweb.link` IPFS/IPNS playlist URLs when Chromecast cannot connect to the gateway host.
- Made Channel status open by default, prepare HaP before loading lists, and show TV-focusable channel controls.

## [1.1.1] - 2026-05-12

### Changed

- Hide the plugin APK from Android and Android TV launchers while keeping StreamVault activity configuration available.

## [1.1.0] - 2026-05-12

### Added

- Initial public release of the StreamVault HaP Plugin APK.
- StreamVault plugin API service with support for M3U provider, playback preparation, Cast URL rewriting, and native activity configuration.
- Native Android/TV configuration activity for runtime controls, source management, connected clients, channel status testing, and logs.
- User-managed M3U sources with validation for AceStream-compatible content.
- Custom channel editor for manually adding AceStream IDs.
- Bundled AceServe and HTTPAceProxy runtime integration for local playback.
- Signed APK release automation through GitHub Actions.
- GitHub Release publishing with versioned and stable APK download assets.
