# Why an APK from GitHub sometimes refuses to install

Android identifies an app by its package name *and* the certificate it was signed with. Same
package, different certificate, and the phone will not update it: it says the signatures do not
match, or simply "app not installed". Uninstalling first works, and takes your saved network
folders with it (the files on your NFS servers are never touched).

There is nothing wrong with the build. The problem is which key signed it.

## Where the keys come from

| Build | Key it is signed with |
|---|---|
| `make debug`, `make install-debug` | your own `~/.android/debug.keystore` |
| `make apk` / `make install` **with** `keystore.properties` | your upload key |
| `make apk` / `make install` **without** `keystore.properties` | your own `~/.android/debug.keystore` |
| GitHub Actions, no secret configured | **a brand-new debug key, generated on the runner for that run** |
| GitHub Actions, secrets configured | the key in the secrets, every time |

The fourth row is the one that bites. A runner starts with no keystore, so the Android Gradle
Plugin makes one on the spot: a different certificate on every run.

## The fix, once (maintainers)

Use one key for CI. The simplest choice is the same upload key the Play Store gets, so a build from
GitHub also installs over a build from your own machine.

```sh
# 1 - make a key, if you do not already have one
keytool -genkeypair -v -keystore vibenfs-upload.jks -alias upload \
        -keyalg RSA -keysize 4096 -validity 10000

# 2 - turn it into one line of text (works on Linux and macOS)
base64 < vibenfs-upload.jks | tr -d '\n' > vibenfs-upload.jks.base64
```

Then add four repository secrets in **Settings → Secrets and variables → Actions → New secret**:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the contents of `vibenfs-upload.jks.base64` |
| `SIGNING_STORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `upload` |
| `SIGNING_KEY_PASSWORD` | the key password |

The workflow writes `keystore.properties` from those before building, which is exactly what
`app/build.gradle.kts` looks for, and deletes it again right after. With no secrets set it still
builds and publishes; it just warns that the APK is throwaway-signed.

This repository is public. Secrets are never printed in logs, and the workflow only runs on pushes
to `master`, on published releases and on manual runs from `master`, never on pull requests from
forks. Actions are pinned to commit SHAs and the checkout keeps no token on disk. Keep the `.jks` file and
its `.base64` copy out of the repository (`.gitignore` refuses `keystore.properties`, `*.jks` and
`*.keystore`) and somewhere safe: losing the key means the app can never be updated in place again.

## Reading the fingerprint

Every release built by the workflow carries its signing certificate's SHA-256 in the release notes.
To read it off an APK yourself:

```sh
$ANDROID_HOME/build-tools/*/apksigner verify --print-certs vibenfs.apk
```

Two builds with the same fingerprint install over each other. Two with different fingerprints never
will, and the only way across is an uninstall.
