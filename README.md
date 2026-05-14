# StreamVault HaP Plugin

<p align="center">
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-HaP/releases/latest/download/StreamVault-HaP-Plugin.apk"><img src="https://img.shields.io/badge/Download-StreamVault--HaP--Plugin.apk-2ea44f?style=for-the-badge&logo=android" alt="Download StreamVault HaP Plugin APK" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-HaP/releases/latest"><img src="https://img.shields.io/github/v/release/jopsis/StreamVault-IPTV-Plugin-HaP?display_name=tag&style=for-the-badge&color=0f766e&cacheSeconds=60" alt="Latest StreamVault HaP Plugin release" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-HaP/releases"><img src="https://img.shields.io/github/downloads/jopsis/StreamVault-IPTV-Plugin-HaP/total?style=for-the-badge&color=8b5cf6&cacheSeconds=60" alt="Total Downloads" /></a>
	<a href="docs/Changelog.md"><img src="https://img.shields.io/badge/Changelog-View-2563eb?style=for-the-badge" alt="View changelog" /></a>
	<a href="https://github.com/jopsis/StreamVault-IPTV-Plugin-HaP/actions/workflows/build-apk.yml"><img src="https://img.shields.io/github/actions/workflow/status/jopsis/StreamVault-IPTV-Plugin-HaP/build-apk.yml?branch=main&style=for-the-badge&label=CI" alt="GitHub Actions status" /></a>
	<a href="https://ko-fi.com/jopsis"><img src="https://img.shields.io/badge/Support%20jopsis-Ko--fi-ff5f5f?style=for-the-badge&logo=kofi" alt="Support jopsis on Ko-fi" /></a>
	<a href="https://ko-fi.com/yourace"><img src="https://img.shields.io/badge/Support%20yourace%20(AceServe)-Ko--fi-ff5f5f?style=for-the-badge&logo=kofi" alt="Support yourace (AceServe creator) on Ko-fi" /></a>
</p>

This repository builds HaP as a StreamVault companion plugin APK.

HaP packages AceServe and HTTPAceProxy behind the StreamVault plugin API. It can
publish a local AIO M3U provider, prepare AceStream playback, rewrite playback
URLs for Google Cast, and expose its own native configuration screen.

Current plugin version: `1.1.3` (`versionCode` 5).

## Capabilities

The plugin exposes the `com.streamvault.plugin.API` bound service and advertises
these capabilities:

- `provider.m3u`: publishes the local HaP `/aio` playlist URL.
- `playback.prepare`: starts HaP before local HaP playback.
- `cast.rewriteUrl`: switches HaP to LAN mode and rewrites local URLs for Cast.
- `configuration.activity`: opens the native HaP configuration activity.

HaP uses `configurationMode: "activity"`. StreamVault should open
`com.streamvault.plugin.hap.CONFIGURE` instead of rendering a host schema. The
native activity is the supported configuration surface because HaP needs
interactive source management, runtime controls, channel testing, logs, and
TV-first custom layout behavior.

## Configuration UI

The configuration screen is implemented by
`com.streamvault.plugin.hap.HapConfigActivity`. It is exported for StreamVault
through `com.streamvault.plugin.hap.CONFIGURE`, but it is intentionally not
advertised as a launcher or Leanback launcher activity so the plugin stays
hidden from the app drawer and is opened from StreamVault.

The UI uses Android resources for localization. English is the default locale and
Spanish is provided through `values-es`; Android selects the language from the
system locale.

The screen contains:

- Runtime summary: color-coded runtime state, phase, AIO URL, LAN URL, and
  runtime actions.
- Sources: collapsible and collapsed by default.
- Clients: connected HTTPAceProxy clients.
- Channel status: expanded by default with TV-focusable controls.
- Logs: recent HaP runtime output.

### Sources

Sources are intentionally user-managed for public distribution. The plugin no
longer ships hardcoded playlist sources.

The Sources section allows users to add M3U lists only. Each list requires:

- Name.
- HTTP or HTTPS M3U URL.

When a list is added or updated, HaP downloads it and validates that:

- The playlist starts with `#EXTM3U`.
- It contains M3U channels.
- At least one channel points to AceStream content.

AceStream content is accepted when the channel URL is one of:

- `acestream://...`
- `infohash://...`
- A bare 40-character AceStream hash.
- HTTP or HTTPS URLs that point to AceStream-like media files, such as
  `.acelive`, `.acestream`, `.acemedia`, or `.torrent`.
- HTTP or HTTPS URLs with `id`, `infohash`, or `content_id` query parameters.

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

The Channel status section opens expanded so TV remote users can immediately
load lists, inspect channels, and run peer checks with visible focus states.

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
        <action android:name="com.streamvault.plugin.hap.CONFIGURE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Do not advertise `configuration.schema` for HaP unless a complete
StreamVault-rendered schema is intentionally supported. HaP's rich configure
experience is native, so the Activity is the source of truth.

## Build

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
appear as `HaP`, version `1.1.3`, with `configuration.activity`.

The configuration activity can also be opened directly:

```sh
$HOME/Library/Android/sdk/platform-tools/adb shell am start \
  -a com.streamvault.plugin.hap.CONFIGURE \
  -p com.streamvault.plugin.hap
```

## Test Targets

The current manual device targets are:

- Chromecast with Google TV.
- Nexus 5X.

Recommended smoke test:

- Install the plugin APK on both devices.
- Open HaP directly and from StreamVault's Plugins screen.
- Confirm Sources and Channel status start collapsed.
- Add a real M3U AceStream list.
- Load channel lists, run Test list, then click a channel row to test only that
  channel.
- Enable the plugin in StreamVault and confirm the HaP AIO provider URL is
  available.
