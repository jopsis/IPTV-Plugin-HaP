# Changelog

All notable changes to the StreamVault HaP Plugin will be documented here.

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
