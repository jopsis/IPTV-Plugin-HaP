# HaP for StreamVault and External IPTV Players

<p align="center">
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-HaP/releases/latest/download/StreamVault-HaP-Plugin.apk"><img src="https://img.shields.io/badge/Download-StreamVault--HaP--Plugin.apk-2ea44f?style=for-the-badge&logo=android" alt="Download StreamVault HaP Plugin APK" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-HaP/releases/latest"><img src="https://img.shields.io/github/v/release/jopsis/StreamVault-IPTV-Plugin-HaP?display_name=tag&style=for-the-badge&color=0f766e&cacheSeconds=60" alt="Latest StreamVault HaP Plugin release" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-HaP/releases"><img src="https://img.shields.io/github/downloads/jopsis/StreamVault-IPTV-Plugin-HaP/total?style=for-the-badge&color=8b5cf6&cacheSeconds=60" alt="Total Downloads" /></a>
	<a href="docs/Changelog.md"><img src="https://img.shields.io/badge/Changelog-View-2563eb?style=for-the-badge" alt="View changelog" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-HaP/actions/workflows/build-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/jopsis/StreamVault-IPTV-Plugin-HaP/build-apk.yml?branch=main&style=for-the-badge&label=CI" alt="GitHub Actions status" /></a>
	<a href="https://ko-fi.com/jopsis"><img src="https://img.shields.io/badge/Support%20jopsis-Ko--fi-ff5f5f?style=for-the-badge&logo=kofi" alt="Support jopsis on Ko-fi" /></a>
	<a href="https://ko-fi.com/yourace"><img src="https://img.shields.io/badge/Support%20yourace%20(AceServe)-Ko--fi-ff5f5f?style=for-the-badge&logo=kofi" alt="Support yourace (AceServe creator) on Ko-fi" /></a>
</p>

This repository builds HaP as a StreamVault companion plugin APK and standalone
IPTV player server.

HaP packages AceServe and HTTPAceProxy behind the StreamVault plugin API and a
launcher-accessible Android configuration screen. It can publish a local AIO M3U
provider, prepare AceStream playback, rewrite playback URLs for Google Cast, and
serve M3U/EPG URLs to external players such as TiviMate, OTT, and other IPTV
clients.

## Capabilities

The plugin exposes the `com.streamvault.plugin.API` bound service and advertises
these capabilities:

- `provider.m3u`: publishes the local HaP `/aio` playlist URL.
- `playback.prepare`: starts HaP before local HaP playback.
- `cast.rewriteUrl`: switches HaP to LAN mode and rewrites local URLs for Cast.
- `configuration.activity`: opens the native HaP configuration activity.

HaP also works standalone. Open the HaP launcher icon, enable External player
server, then copy the M3U and EPG URLs into an external IPTV player.

HaP uses `configurationMode: "activity"`. StreamVault should open
`com.streamvault.plugin.hap.CONFIGURE` instead of rendering a host schema. The
native activity is the supported configuration surface because HaP needs
interactive source management, runtime controls, channel testing, logs, and
TV-first custom layout behavior.

## Configuration UI

The configuration screen is implemented by
`com.streamvault.plugin.hap.HapConfigActivity`. It is exported for StreamVault
through `com.streamvault.plugin.hap.CONFIGURE` and is also advertised as a phone
and Android TV launcher activity so HaP can be configured without StreamVault.

The UI uses Android resources for localization. English is the default locale and
Spanish is provided through `values-es`; Android selects the language from the
system locale.

The screen contains:

- Runtime summary: color-coded runtime state, phase, AIO URL, LAN URL, and
  runtime actions.
- External players: a persistent server switch for standalone use.
- IPFS: a local IPFS gateway switch, with an advanced section for external
  node mode.
- AIO and LAN server: copyable local/LAN M3U and EPG URLs.
- Sources: collapsible and collapsed by default.
- Clients: connected HTTPAceProxy clients.
- Channel status: collapsible and collapsed by default.
- Logs: recent HaP runtime output.

### External Players

External player server mode is for TiviMate, OTT, and other IPTV players that
consume ordinary M3U URLs.

When enabled, HaP:

- Enables LAN server mode.
- Starts AceServe and HTTPAceProxy.
- Keeps the mode enabled after reboot or app update.
- Exposes copyable playlist and EPG endpoints:
  - Local device: `http://127.0.0.1:8888/aio`
  - Local EPG: `http://127.0.0.1:8888/aio/epg.xml`
  - LAN playlist: `http://<device-lan-ip>:8888/aio`
  - LAN EPG: `http://<device-lan-ip>:8888/aio/epg.xml`

Use the local URL when the IPTV player runs on the same Android device. Use the
LAN URL when the player runs on another device on the same network.

### IPFS

The IPFS switch starts a bundled `kubo` (IPFS) daemon and its HTTP gateway. It
does not add `ipfs://`/`ipns://` playback support by itself: add the
gateway's HTTP URL for a given CID or IPNS name as a regular M3U source in
Sources instead, for example
`http://127.0.0.1:8080/ipns/<name>/path/to/list.m3u`.

When enabled, HaP:

- Prepares and starts the bundled kubo binary for the device's ABI
  (`arm64-v8a` or `armeabi-v7a`), using kubo's `lowpower` profile (no
  reprovide, limited connection manager) since the node only consumes
  content.
- Exposes a local gateway (default `http://127.0.0.1:8080`, configurable
  under Advanced).
- Keeps the RPC API on `127.0.0.1:5001` and the libp2p swarm on `4001`,
  regardless of LAN mode — only the gateway is ever exposed to the network.
- Binds the gateway to the LAN when the "LAN server" switch (AIO and LAN
  server section) is on, so devices on the same network can also reach it.

Advanced options (collapsed by default):

- Use external node: point HaP at an already-running IPFS node's
  host/port instead of starting the bundled one (a Termux `ipfs daemon`, a
  NAS, a PC on the same LAN, etc.).
- Gateway port and disk storage quota (`Datastore.StorageMax`) for the
  embedded node.

The `libipfs.so` binaries are not committed to git — building them requires
compiling kubo from source, which is too heavy to check in and update on
every kubo release. Run `tools/fetch-kubo.sh` before building; it fetches
kubo, and needs only Go for `arm64-v8a` (no cgo) but the Android NDK for
`armeabi-v7a` (`android/arm` requires cgo linking, enforced by the Go
toolchain itself). `:app:preBuild` fails with a clear message if the
binaries are missing from `app/src/main/jniLibs/`.

### Sources

Sources are intentionally user-managed for public distribution. The plugin no
longer ships hardcoded playlist sources.

The Sources section allows users to add M3U lists or AceStream API endpoints.
Each source requires:

- Name.
- HTTP or HTTPS M3U URL, or an AceStream API URL.

When a list is added or updated, HaP downloads it and validates that:

- M3U playlists start with `#EXTM3U`.
- M3U playlists contain channels.
- AceStream API responses contain at least one `infohash`.
- At least one channel points to AceStream content.

AceStream content is accepted when the channel URL is one of:

- `acestream://...`
- `infohash://...`
- A bare 40-character AceStream hash.
- HTTP or HTTPS URLs that point to AceStream-like media files, such as
  `.acelive`, `.acestream`, `.acemedia`, or `.torrent`.
- HTTP or HTTPS URLs with `id`, `infohash`, or `content_id` query parameters.

AceStream API sources are accepted for:

- `https://api.acestream.me/all`
- `https://api.acestream.me/search`

HaP preserves the user-provided query filters, adds the public AceStream API
defaults when `api_version` or `api_key` are omitted, and formats JSON results
as M3U channels internally.

### Custom Channels

The Sources section also contains a `Custom` source. When `Custom` is enabled,
the UI expands a nested custom-channel editor.

Each custom channel requires:

- Channel name.
- AceStream ID.

The AceStream ID may be entered as a plain 40-character hash, an
`acestream://...` URL, or a URL that contains an `id`, `infohash`, or
`content_id` query value. HaP normalizes it and writes a generated `custom.m3u`
playlist for HTTPAceProxy.

### Channel Status

The Channel status section starts collapsed. Open it to load lists, inspect
channels, and run peer checks with visible focus states.

Available actions:

- Load lists: reads the current HaP playlists and shows available channel lists.
- Test list: tests all channels in the selected list and marks each result.
- Stop: cancels an active list test.
- Channel row click: selecting a channel row tests that single channel and
  updates the row status.

There is no separate top-level `Test channel` button; single-channel tests are
driven by the selected row.

## StreamVault Integration

The service manifest should declare Activity configuration mode:

```xml
<service
    android:name=".StreamVaultHapPluginService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.streamvault.plugin.API" />
    </intent-filter>

    <meta-data
        android:name="com.streamvault.plugin.CONFIGURATION_MODE"
        android:value="activity" />
    <meta-data
        android:name="com.streamvault.plugin.CONFIGURATION_ACTIVITY_ACTION"
        android:value="com.streamvault.plugin.hap.CONFIGURE" />
    <meta-data
        android:name="com.streamvault.plugin.CAPABILITIES"
        android:value="provider.m3u,playback.prepare,cast.rewriteUrl,configuration.activity" />
</service>
```

The configuration activity must be exported and include the `DEFAULT` category:

```xml
<activity
    android:name=".HapConfigActivity"
    android:exported="true"
    android:label="@string/app_name">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
    </intent-filter>
    <intent-filter>
        <action android:name="com.streamvault.plugin.hap.CONFIGURE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Do not advertise `configuration.schema` for HaP unless a complete
StreamVault-rendered schema is intentionally supported. HaP's rich configure
experience is native, so the Activity is the source of truth.

## Build

Build the bundled IPFS (kubo) binaries once before the first Gradle build (see
[IPFS](#ipfs) below for what this needs):

```sh
tools/fetch-kubo.sh
```

Use the Android SDK and a Java runtime compatible with the Android Gradle
plugin:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install

Install the debug APK on a connected device:

```sh
$HOME/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installation, refresh StreamVault's Plugins screen. The HaP plugin should
appear as `HaP`, version `1.3.0`, with `configuration.activity`.

The configuration activity can also be opened directly:

```sh
$HOME/Library/Android/sdk/platform-tools/adb shell am start \
  -a com.streamvault.plugin.hap.CONFIGURE \
  -p com.streamvault.plugin.hap
```

The standalone launcher entry can be opened with:

```sh
$HOME/Library/Android/sdk/platform-tools/adb shell monkey \
  -p com.streamvault.plugin.hap 1
```

## Test Targets

The current manual device targets are:

- Chromecast with Google TV.
- Nexus 5X.

Recommended smoke test:

- Install the plugin APK on both devices.
- Open HaP from the launcher and from StreamVault's Plugins screen.
- Enable External player server and confirm M3U/EPG URLs are shown for local and
  LAN playback.
- Enable IPFS and confirm the status pill reaches Online and the gateway URL
  serves a known CID (`curl http://127.0.0.1:8080/ipfs/<CID>` via `adb
  forward`).
- Confirm Sources and Channel status start collapsed.
- Add a real M3U AceStream list.
- Load channel lists, run Test list, then click a channel row to test only that
  channel.
- Enable the plugin in StreamVault and confirm the HaP AIO provider URL is
  available.
