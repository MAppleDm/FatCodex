# Contributing

Bug reports, focused pull requests, and documentation improvements are welcome. English and Russian are both fine.

Before starting a substantial feature, open an issue describing the problem and intended behavior. For bugs, include the app version, Android version, and steps to reproduce. Remove API keys and personal data from screenshots and logs.

## Development

Use JDK 21 and the Android SDK. Robolectric's Android 16+ test sandbox requires Java 21; application bytecode still targets Java 17. Build instructions and project details are in the README and `docs/`.

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

Keep changes focused. Add or update tests when behavior changes, and update the documentation when setup or user-facing behavior changes. In a pull request, explain what changed and how you checked it; distinguish JVM checks from testing on a phone.

## Releases

Releases use `vMAJOR.MINOR.PATCH`, for example `v0.2.1`. See [RELEASING.md](RELEASING.md) for versioning and publishing instructions.

## License

Contributions are distributed under the repository's existing MIT license. See [LICENSE](LICENSE).
