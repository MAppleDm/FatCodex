# Releasing FatCodex

## Version format

Use an exact `vMAJOR.MINOR.PATCH` Git tag and release title, such as `v0.2.1`. Do not use moving tags such as `latest`, dates, or build numbers as release names.

- **PATCH:** compatible fixes.
- **MINOR:** new functionality; while below 1.0, document any incompatible changes explicitly.
- **MAJOR:** a stable milestone or incompatible changes after 1.0.

Each version identifies one source commit and one set of APKs. Publish a new version to correct an already published build. Use GitHub's prerelease flag for experimental versions; the in-app updater offers stable releases only.

## Publish

1. Merge the intended changes into `main`. Update `versionName` (without `v`) and increase `versionCode` in `android/app/build.gradle.kts`. Update [CHANGELOG.md](CHANGELOG.md).
2. Run `./gradlew testDebugUnitTest assembleRelease` from `android/`. Use the **same signing key** as the APK already distributed; a different key prevents installing an update over it.
3. Install and check the APK on a phone. JVM tests do not replace device testing.
4. Create the matching tag from the tested commit. Create a draft release titled exactly like the tag. Attach `FatCodex-X.Y.Z.apk`, include its SHA-256 and install instructions, and describe changes and known limitations.
5. Verify that the APK's version name matches the tag, its version code increases, and its package and signing identity match the previous release. Publish only after all assets are attached. Mark a stable release as latest.

Example, after preparing and testing version `0.2.1`:

```bash
git tag v0.2.1
git push origin v0.2.1
gh release create v0.2.1 FatCodex-0.2.1.apk --draft --verify-tag --title v0.2.1 --notes-file release-notes.md
```

The current public APK uses a development signing key. Keep that identity for compatible updates; production signing and any migration need a separate plan. Do not commit signing credentials or API keys.
