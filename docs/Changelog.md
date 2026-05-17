# Changelog

All notable changes to the StreamVault HaP Plugin will be documented here.

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
