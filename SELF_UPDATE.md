# Morphe YouTube self-update

This branch adds a non-root updater to the patched YouTube package.

## Runtime model

- GitHub Actions checks for official Morphe source updates every six hours.
- A verified arm64 APK is published as `youtube-arm64-v8a-morphe-selfupdate.apk`.
- The phone checks the latest public release with Android `JobScheduler`.
- The updater verifies HTTPS, GitHub's SHA-256 asset digest, package name, version code,
  and the installed signing certificate before opening a package-install session.
- The target app updates itself with `USER_ACTION_NOT_REQUIRED`.

After the one-time bootstrap, no computer, ADB connection, root, Shizuku, wireless
debugging, phone-side GitHub account, Obtainium, or F-Droid is part of the runtime path.
Android's per-app "Install unknown apps" access must remain enabled for the patched
YouTube package.

The workflow keeps the BKS keystore in the `MORPHE_KEYSTORE_B64` Actions secret. It
validates the APK package, arm64 ABI, signing certificate, updater manifest entries,
and updater DEX classes before publishing a release. GitHub build provenance is also
attached to each release artifact.
