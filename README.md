<p align="center"><img src="docs/icon.svg" width="128" alt="VibeNFS app icon: a folder with a network signal on a lavender cookie shape"></p>

<h1 align="center">VibeNFS</h1>

<p align="center">
  <strong>A modern NFS client for Android: mount NFSv3 and NFSv4 shares and browse them in the system Files app. No root needed.</strong>
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-blue"></a>
  <img alt="Android 14+" src="https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white">
  <img alt="NFS v3 and v4" src="https://img.shields.io/badge/NFS-v3%20%7C%20v4-6B2FD1">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white">
  <a href="https://github.com/NicoNex/VibesNFS/releases/tag/latest"><img alt="Download APK" src="https://img.shields.io/badge/download-APK-2ea44f?logo=android&logoColor=white"></a>
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203%20Expressive-4285F4?logo=jetpackcompose&logoColor=white">
</p>

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01_list.png" width="240" alt="VibeNFS folder list with a mounted NFS share, free space and protocol version">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03_files.png" width="240" alt="An NFS share open in the Android system Files app">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/08_list_dark.png" width="240" alt="VibeNFS folder list in dark theme">
</p>

---

Your media server, home lab or Linux box already shares folders over NFS. Your Android phone can't open them, because Android doesn't support NFS, and mounting it in the kernel needs root.

**VibeNFS fixes that without root.** Add a network folder once, flip a switch, and the share shows up in the **Files app** next to your internal storage and Google Drive. Any app that opens files through the system picker can then read and save files on your NFS server: video players, photo editors, document apps.

## Features

- **Shows up in the Files app.** Each mounted share appears as its own location in the system file manager, using Android's Storage Access Framework (`DocumentsProvider`). There's no extra file browser to learn.
- **No root, no kernel module.** A native NFS client ([libnfs](https://github.com/sahlberg/libnfs)) runs inside the app.
- **NFSv3 and NFSv4.** *Auto* tries v4 first, since it needs only port 2049 and gets through firewalls more easily, and falls back to v3. NFSv4 speaks 4.2 and drops to 4.0 for servers that don't have it, so it also works with servers that turned 4.0 off.
- **Fast, seekable streaming.** Files open as seekable file descriptors served from the network, so you can skip through a 4K movie without downloading it first. Apps read a few KiB at a time and each network round trip costs the full latency, so VibeNFS reads 512 KiB blocks, and once you read sequentially it fetches the next blocks in parallel, like the kernel's readahead. A photo over a VPN takes a handful of round trips instead of hundreds.
- **Thumbnails in Files.** Photos, videos and album art get previews. VibeNFS uses the preview cameras embed in the photo header when there is one, so most phone photos never cross the network in full just to draw a tile. Otherwise it decodes straight at the requested size. Thumbnails are cached until the file changes.
- **Survives network changes.** Switch from Wi-Fi to mobile data and back: mounted folders reconnect on the new network in the background, with no stuck connection and no reboot.
- **Read and write.** Create, rename, move and delete files and folders from any app. The *Read only* switch protects shares you only want to browse.
- **Quick Settings tile.** Tap to mount or unmount everything. Long-press for a per-folder panel, like the Wi-Fi and Bluetooth tiles.
- **Home screen widget.** The same controls on your home screen. Each folder is a button that lights up while it's mounted and shows its free space, the server while it's off, or what went wrong.
- **Finds shares for you.** Type the address of an NFSv3 server and VibeNFS lists its exported paths; tap one to fill in the field. NFSv4-only servers don't publish that list, so type the path yourself.
- **Multiple servers and exports.** Add as many network folders as you want, each with its own protocol, port and UID/GID. Tick several exports of one server to add them all at once.
- **NFSv4 pseudo-root aware.** Use the path the server lists (`/srv/nfs4/media`) even when NFSv4 serves it as `/media`: if NFSv4 can't find the full path, VibeNFS tries the shorter ones.
- **Clear errors.** "Server unreachable" or "Access denied by the server" instead of raw RPC codes.
- **Material 3 Expressive.** Big editorial type and expressive shapes and motion. All colors come from your wallpaper (Material You), in light and dark. The back gesture previews the screen underneath, as it does between apps.
- **English and Italian.**

## Screenshots

| Your folders | Edit a folder | In the Files app |
|:---:|:---:|:---:|
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01_list.png" width="240" alt="Folder list showing mounted, error and off states"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02_editor.png" width="240" alt="Editor with server address, shared path and NFS version"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03_files.png" width="240" alt="NFS share browsed in the Android Files app"> |
| **Long-press panel** | **Home screen widget** | **Quick Settings tile** |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04_sheet.png" width="240" alt="Per-folder mount panel opened from the tile"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05_widget.png" width="240" alt="Home screen widget with lit buttons for mounted folders and free space"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06_tile.png" width="240" alt="VibeNFS tile in Android Quick Settings"> |
| **First launch** | | |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/07_empty.png" width="240" alt="Empty state inviting you to add a network folder"> | | |

### Dark theme

| Your folders | Edit a folder | In the Files app |
|:---:|:---:|:---:|
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/08_list_dark.png" width="240" alt="Folder list in dark theme"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/09_editor_dark.png" width="240" alt="Folder editor in dark theme"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/10_files_dark.png" width="240" alt="NFS share in the Files app, dark theme"> |
| **Long-press panel** | **Home screen widget** | **Quick Settings tile** |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/11_sheet_dark.png" width="240" alt="Per-folder mount panel in dark theme"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/12_widget_dark.png" width="240" alt="Home screen widget in dark theme"> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/13_tile_dark.png" width="240" alt="VibeNFS tile in dark Quick Settings"> |
| **First launch** | | |
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/14_empty_dark.png" width="240" alt="Empty state in dark theme"> | | |

## Getting started

### 1. Set up your NFS server

Android apps can't open privileged ports (below 1024), and many NFS servers refuse clients that connect from higher ports by default. Allow them on the server:

- **Linux (`/etc/exports`):** add `insecure` to the export, then run `exportfs -ra`.
  ```
  /srv/media  192.168.1.0/24(rw,insecure,no_subtree_check)
  ```
- **Synology DSM:** in the shared folder's NFS permissions, enable *Allow connections from non-privileged ports*.
- **Other servers:** look for an option named "insecure", "non-privileged ports" or "reserved ports".

VibeNFS authenticates with `AUTH_SYS`. To access files as a specific user, set **UID** and **GID** under *Advanced*.

### 2. Install the app

Download **[vibenfs.apk](https://github.com/NicoNex/VibesNFS/releases/download/latest/vibenfs.apk)** from the [latest release](https://github.com/NicoNex/VibesNFS/releases/tag/latest), which is rebuilt automatically from every commit to `master`. You can also build it yourself (see below). The app needs Android 14 or newer.

If the phone refuses to update an earlier install, the two builds were signed by different keys; [docs/signing.md](docs/signing.md) explains why.

### 3. Add a network folder

1. Tap **Add network folder**.
2. Enter the server address. VibeNFS lists the shares it finds; tap one, or type the path yourself.
3. Tap **Test connection**, then **Save and mount**.
4. Open the **Files** app. On Pixel phones, scroll to *All storage* and tap **Other storage**; in the system file manager the folder is in the side menu. **Open in Files** in VibeNFS takes you straight there.

On Android 17, the system asks for permission to access devices on your local network the first time. VibeNFS needs it to reach the server.

## Build from source

Requirements: JDK 21, the Android SDK (compile SDK 37), NDK `29.0.14206865` and CMake `4.1.2`. Gradle downloads everything else.

```sh
# clone with submodules (libnfs lives in app/src/main/cpp/libnfs)
git clone --recursive https://github.com/NicoNex/VibesNFS.git
cd VibesNFS
make debug          # builds vibenfs-debug.apk
make install-debug  # builds and installs on the connected phone
```

| Target | What it does |
|---|---|
| `make apk` | Release APK → `vibenfs.apk` (signed with `keystore.properties` if present, debug key otherwise) |
| `make debug` | Debug APK → `vibenfs-debug.apk` |
| `make test` | JVM unit tests |
| `make install` / `make install-debug` | Build and install on the USB-connected phone |
| `make reinstall` | Uninstall and reinstall, to fix a signature mismatch. Asks before deleting saved folders. |
| `make bundle` | Play Store bundle → `vibenfs.aab` (needs `keystore.properties`) |
| `make release` | Bump `VERSION_CODE`, then build the bundle |

Instrumented tests (`./gradlew connectedDebugAndroidTest`) expect an NFS server reachable from the emulator at `10.0.2.2:20490`. Any userspace server works, for example [go-nfs](https://github.com/willscott/go-nfs): `go run github.com/willscott/go-nfs/example/osnfs <dir> 20490`.

## How it works

```
Files app / any app ──SAF──▶ NfsDocumentsProvider ──▶ MountManager ──▶ NfsClient (JNI) ──▶ libnfs ──TCP──▶ NFS server
                                    │
                                    └─ seekable proxy file descriptors (StorageManager.openProxyFileDescriptor)
```

- **`nfs/`**: a thin JNI bridge over libnfs 8, built with multithreading. One NFS context per share, safe to call from any thread.
- **`mount/`**: keeps track of which folders should be mounted, survives process death, reconnects on demand and moves connections to a new network when the phone switches.
- **`provider/`**: the `DocumentsProvider` that puts each share in the Files app.
- **`provider/BlockReader.kt`**: block cache with parallel read-ahead, shared by the file descriptors handed to apps and by the thumbnailer.
- **`provider/Thumbnails.kt`**: previews read straight over NFS, never through the app's own file descriptors, via EXIF, `MediaMetadataRetriever` (with a `MediaDataSource`) and `ImageDecoder`.
- **`tile/`, `widget/`, `ui/`**: the Quick Settings tile, the Glance widget and the Compose UI.

## FAQ

**Does it need root?**
No. VibeNFS doesn't use the kernel NFS client. Everything runs in the app.

**Why isn't my folder on the Files home screen, next to my USB stick?**
That spot is only for disks Android mounts itself, which takes root for NFS. Your folder is under **Other storage** in Files by Google, or in the side menu of the system file manager, next to Google Drive.

**Can other apps open my NFS files?**
Yes, any app that uses the system file picker (Storage Access Framework). Apps that only scan local storage directly won't see the share.

**Which NFS versions are supported?**
NFSv3, NFSv4.0 and NFSv4.2. Kerberos (`sec=krb5`) isn't supported yet.

**Does it keep a connection open in the background?**
No background service or persistent notification. Mounting connects right away. If the app is closed, the connection reopens on the next use; if the phone changes network, it reconnects on the new one.

**"Server refused this device", "RPC Packet not accepted" or `MNT3ERR_ACCES`?**
The server rejects clients on non-privileged ports, and every Android app is one. Add `insecure` to each export, including the NFSv4 root (`fsid=0`), and make sure the client list includes your phone's address. On a VPN such as Tailscale or NetBird that's usually `100.64.0.0/10`. Then run `sudo exportfs -ra`. Listing exports still works without this, which is why the shares can show up while mounting fails.

**Is it tested on real NAS hardware?**
Partly. VibeNFS is tested with NFSv3 against a userspace server on the Android 17 emulator, and with NFSv4.2 against Linux `nfsd` over a VPN on a real phone. NAS boxes (Synology, TrueNAS, Unraid) still need testing. Reports are very welcome.

## Roadmap

- F-Droid listing (metadata is ready in `fastlane/`)
- Kerberos authentication

## Contributing

Issues and pull requests are welcome. Reports from real NFS servers help the most: include the server type, the NFS version and what you saw. Run `make test` before opening a pull request.

## License

VibeNFS is free software, licensed under the [GNU General Public License v3.0](LICENSE).

It builds on:
- [libnfs](https://github.com/sahlberg/libnfs) by Ronnie Sahlberg, LGPL-2.1-or-later
- [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex) and [Google Sans Code](https://fonts.google.com/specimen/Google+Sans+Code), SIL Open Font License 1.1
- [Jetpack Compose](https://developer.android.com/compose), [Material 3](https://m3.material.io) and [Glance](https://developer.android.com/develop/ui/compose/glance), Apache 2.0

<sub>Keywords: NFS client Android, mount NFS share on Android without root, NFSv4 Android, Android Files app NFS, Storage Access Framework NFS, DocumentsProvider NFS, NAS Android file manager, Synology NFS Android, TrueNAS NFS Android, libnfs Android.</sub>
