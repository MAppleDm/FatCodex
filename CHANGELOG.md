# Changelog

User-facing releases follow `vMAJOR.MINOR.PATCH`. See [RELEASING.md](RELEASING.md).

## v0.4.0 — 2026-10-22

- Three appearance themes: Paper (warm light), Coal (charcoal dark), and Matcha (pistachio and cream).
- A theme picker in Settings with a saved choice and an option to follow the phone's light or dark appearance.
- A clearer diary header with larger calorie totals, quieter goals and macros, and a rounded input surface.
- Improved contrast, matching system bar icons, and a responsive summary for small screens and larger text.
- Android version code increases from 4 to 5; the package and signing identity remain compatible with v0.3.1.

## v0.3.1 — 2026-10-21

- Updated green-apple launcher icon with the shared SpendCodex and VibeNotes app-family styling, including adaptive and monochrome variants.
- Rebuilt the current food diary as an installable APK with an increased Android version code and the existing signing identity.

## v0.3.0 — 2026-10-18

- In-app checks for GitHub releases and APK downloads.
- Dishes with editable ingredients and matching calorie and macro totals.
- Health Connect integration for sleep, steps, workouts, and weight from connected devices.
- App lock using the system fingerprint, face, or device-credential prompt.
- Inter and custom fonts, optional food-entry sound, and animated daily totals.
- Photos from the gallery, including receipts and menus.
- Updated diary history, exports, and database migrations for the new data.

## v0.2.0 — 2026-10-06

- Local mode with a user-supplied DeepSeek key and a food database built from confirmed proposals.
- Food logging through text and photos, with explicit retry when the agent cannot process a message.
- Daily energy estimates from the initial questionnaire, with an adjustable correction.
- Photo thumbnails and diary export in Markdown, JSON, and CSV.
- In-app checks for GitHub releases and APK downloads.

Development build; device verification remains limited. See the [release notes](https://github.com/MAppleDm/FatCodex/releases/tag/v0.2.0) for the distributed APK and signing details.
