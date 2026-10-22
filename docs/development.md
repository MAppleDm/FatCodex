# FatCodex: development notes

Everything technical lives here: how the app works, how to build and run it, the repository layout and known limitations. The short overview is in the [README](../README.md).

## How it works

```
Android (Compose, Room)  ──JWT──▶  gateway  ──▶  ai-parser ──▶ DeepSeek
   offline-first, queue              │     └──▶  nutrition  (USDA + Open Food Facts, SQLite)
   WorkManager                       └────────▶  stt        (faster-whisper small, CPU)
                              Postgres (users, diary)    Redis (parse cache)
```

- The model **only names foods and grams**. It does not invent calories: the numbers come from the `nutrition` service and its local food database. If a food is not found, the entry stays without numbers and the app asks one question.
- Below 0.6 confidence the app asks one short clarifying question. Your next message is the answer.
- "less oil", "that was half", "remove the bread" update existing entries instead of creating new ones.
- Everything is stored locally in Room and synced later. A message sent offline waits in a queue and goes out by itself. Resending is safe (idempotent by `client_id`).
- The DeepSeek key lives only in the `ai-parser` container. The app never sees it.
- The goal is never typed in: it is worked out in the app (what a day costs plus your own correction, see "About me") and never goes below 1,200 kcal, and the wording stays neutral.

## Local mode

On the login screen choose "without a server": no email is needed and the diary stays on the phone. Then paste **your own DeepSeek key** once and answer five questions about yourself (the goal is worked out from them, see below). The key is required: without the agent the app does not work.

- The key is not in the APK. You enter it yourself and it is stored encrypted in the app's private settings. The model receives only what you typed or photographed, and the request goes straight to `api.deepseek.com` and nowhere else. This needs internet.
- **Only the agent reads what is written.** There is no dictionary, no USDA catalog and no "parse without the model" path: a substitute that picks a "similar" food gives numbers that are not the food's. The only source of numbers is **your own food database, which the agent builds and uses**: it looks a food up in your database, and when it is not there, on the web (DuckDuckGo), then **proposes** values and nothing is added without your choice. The base starts empty and fills as you eat.
- The day summary's numbers count to a new value instead of jumping (`rememberCounted` in `core-ui/Counted.kt`: an `Animatable` per number, fast-start easing, 260 to 900 ms by the size of the change, a tint towards `AppColors.time` that fades as it settles). `DaySummary(resetKey = day to loaded)` shows a number at once, uncounted, whenever the key changes: another day, or the first read (`MainUiState.loaded` is false until the content of the shown day has arrived, so the app does not count up from 0 on start). The calories' count is passed to `details`, so "left" and the progress line follow it. It follows the system animation scale.
- A recorded food shows the time it was recorded under its name (`EntryRow(time = Texts.clock(entry.eatenAt))`, the phone's time zone, small, in `AppColors.time`, the one orange of the app); a food waiting for "записать" has none. The conversation about a food is only in the opened food (`FeedItem.EntryItem.history`), not as a grey line under the row.
- About → **Sound** (`ui/sound/Sound.kt`): a dull knock (`res/raw/added.wav`, 0.22 s, synthesized by `tools/make_added_sound.py` from a falling damped sine and a low-passed click, no third-party material) when a food is in the diary for good. `MainViewModel` watches the shown day's content and emits `UiEffect.Added` when an id appears among what counts (`!pending && kcal != null`, or a weight); the first look at a day only learns what is there. `MainScreen` plays it through `LocalAddedSound` (`SoundPoolPlayer`: `SoundPool`, `USAGE_NOTIFICATION_COMMUNICATION_INSTANT`, so it follows the notification volume and silent mode and Do Not Disturb; loaded in `MainActivity.onCreate`). The choice is the `sound` prefs (`SoundStore`, on by default); `SoundViewModel` plays it once when turned on.
- Testing on an emulator against the mock agent: `python android/tools/mock_deepseek.py`, then `adb reverse tcp:8090 tcp:8090` and `./gradlew assembleDebug -PmodelBaseUrl=http://127.0.0.1:8090` (on some emulators `10.0.2.2` does not reach the host's `127.0.0.1`; the adb tunnel always does). The mock answers "борщ" with a dish of six ingredients, the brands it knows with proposals, a photo with an omelette, and prints in UTF-8 so that any text a monkey types cannot break it.
- About → **Lock** (`AppLock`, `Biometrics`, `LockScreen`, `LockViewModel`): the system's prompt (`androidx.biometric`, `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`: under-screen fingerprint, face, else the phone's PIN) before the diary, off by default. Only one flag is stored (prefs `lock`); the app never sees a fingerprint or a PIN. With the lock on the app is closed on every start (`AppLock.locked` starts as `enabled`) and when it returns after `GRACE_MS` (60 s) in the background (`MainActivity.onStop/onStart`); a closed app composes `LockScreen` instead of `AppRoot` and asks by itself. Turning the lock on or off needs the same prompt. `MainActivity` is a `FragmentActivity` for the prompt's sake. The recents picture is hidden with `setRecentsScreenshotEnabled(false)` on Android 13+, `FLAG_SECURE` before. The prompt cannot be run on the JVM: the logic around it is tested (`LockTest`), the prompt on a phone.
- About → **Font** offers Inter, the system font, and a font file of the person's own (`FontStore`, `FontViewModel`). Licensed fonts such as Verdana Pro cannot ship in a public APK, so a picked `.ttf`/`.otf` is copied (up to 30 MB, first bytes checked, then loaded with `Typeface.Builder`) into the app's private `files/fonts/custom.font`; it is never uploaded or exported. The choice lives in the `font` preferences; a missing file means Inter. `AppTheme(font = …)` takes the family.
- Text is set in Inter (Latin and Cyrillic, SIL OFL, `core-ui/src/main/res/font/inter_regular.ttf`, notice in `docs/licenses.md`). The "mono" styles are Inter with tabular figures (`fontFeatureSettings = "tnum"`), so digits line up without a monospace face; the journal and the agent's prompt keep the system monospace because they are code.
- While the agent reads a message, one quiet mono line under it says what it is doing ("searching the web", "reading health-diet.ru", "recording"). `ModelParser` reports each round and each tool call through `ParseRequest.onStep`; `LocalMessageProcessor` keeps the current step per message in `AgentActivity` (memory only, cleared when the message is done or failed); `DiaryRepositoryImpl.observeDay` puts it on `OutboxMessage.agent`. Only the kind of step and the site of a page being read are reported, never the text of the message or of a query.
- If the agent cannot be reached (no network, a refused key, an answer that makes no sense, no answer in two minutes), the message stays in the chat with the reason in red and **Try again** (the agent reads it again) and *remove*. Nothing is recorded in its place. Without a key only a weigh-in ("weight 82.4") can be sent.
- Your own food database lives on the phone next to the diary. It can be edited in the app, managed from the chat, and exported to or imported from `dietapp-foods.json`.
- Voice uses on-device recognition only. For Russian you may need to download the offline pack in Android settings.
- The diary and the chosen mode are included in Android auto-backup. The token and the DeepSeek key are not.
- Settings → About → **Journal** shows every request to DeepSeek and every message the agent could not read, with the reason.

The full description of local mode, the agent and the food database is in Russian: [details.ru.md](details.ru.md).

## Export

Settings → **History** is a calendar of weeks (`HistoryScreen`): the oldest at the top and today's week at the bottom (it opens there, like the chat), a square per day, Monday to Sunday, today ringed in orange, at least four weeks. A square is filled by `lerp(heatLow, heatHigh, kcal / goal)` (Oklab, as Compose does; against the fullest day when there is no goal; over the goal is the same as at it): the same green as recorded food, evenly spaced to the eye (dE about 5), the date inside is black or white by luminance 0.18 so it reads at 4.5:1 or better on every fill, in both themes (`HistoryTest` checks it). A day with no entries is `heatEmpty`. Every day up to today can be tapped, empty ones too: that is how yesterday's dinner is added. The numbers of a day are in its content description, and the legend says what the fullest fill is.

Settings → History → **export** writes the diary to a file for another model, a script or a spreadsheet to read. The app does not analyse the history itself: it records, and summaries are better made elsewhere. Pick the period (last 7, 30 or 90 days, or all time), then **save** (a file you place yourself) or **send** (straight to another app). It works the same with a server and without: it reads the phone's own copy. Only recorded food is exported: deleted food and food still waiting for "record" are not in the diary.

| Format | For | Content |
|---|---|---|
| Markdown (`.md`) | a person, a chat with a model | one section per day: totals, weight, a table of foods; the text follows the app's language |
| JSON (`.json`) | programs, models that work with structured data | days with totals, entries (amounts, numbers, per-100 g values, matched food, status, source), weight and trend, and a legend explaining each field |
| CSV: food (`.csv`) | spreadsheets, pandas | one row per food: `date,time,meal_id,name,grams,kcal,protein_g,fat_g,carbs_g,kcal_per_100g,matched_food,status,source` |
| CSV: weight (`.csv`) | spreadsheets, pandas | `date,kg,trend_7d_kg`, one row per weigh-in day |

- Numbers use a dot as the decimal mark whatever the phone's language, one decimal at most. CSV is UTF-8 with commas (RFC 4180) and no BOM: in a Russian-locale Excel use *Data → From text/CSV* and pick UTF-8.
- `status`: `ok` numbers from a food database, `uncertain` approximate (typical values or a guessed amount; marked `~` in Markdown), `unmatched` the food was not found: no numbers, not counted in the day's total. Missing numbers are empty cells (CSV) or `null` (JSON), never zeros.
- Files are named `fatcodex-<diary|food|weight>-<7d|30d|90d|all>-<date>.<ext>`.

## About me, and the energy a day costs

On the first start (after the key) five questions are asked one at a time, each with a control that suits it: two boxes for sex, a dial (minus, plus and a slider) for age, height and weight, five described levels for activity. None of them can be skipped, because the goal is worked out from all of them. Settings → **About me** is the same data on one page, saved as it is changed.

- **Formula.** Mifflin–St Jeor for the energy at rest (10 · kg + 6.25 · cm − 5 · years, +5 for a man, −161 for a woman) times the activity multiplier (1.2, 1.375, 1.55, 1.725, 1.9), rounded to ten kcal. It is an estimate for an average adult, not medical advice, and the screens say so. Nothing is computed until all five answers are known, so none of the questions can be skipped.
- **The goal is that figure plus a correction of your own.** It is never typed in. The last step of the questions and the About me page have a **correction** (−1000 to +1000 kcal in steps of 50: minus to lose weight, plus to gain) and show the sum ("Energy use 2,710, correction −300"). The goal is held between 1,200 and 6,000 kcal (the page says so when the floor is what sets it), and follows the weight when it changes. The diary opens once the result has been seen ("Готово"); someone who had typed a goal in an older version is asked the questions once.
- **Where it lives.** Sex, year of birth (the age is worked out, so it does not go stale), height, activity and the correction are in the `app` preferences file, which travels with Android's backup and is wiped by "Стереть всё". The weight is not stored there: it is the diary's latest weigh-in, so "вес 82.4" in the chat updates the page, and dialling it on the page makes (or corrects) a weigh-in of today. The server's own stored goal is not used for display.

## A photo in the chat

A photo message keeps a small picture of itself (at most 360 px on the long side, JPEG) in the app's private files (`files/thumbs`, not in a backup) and shows it in the chat in a rounded rectangle. It goes when the message is taken back and with "Стереть всё". Without it (another phone, a restore) the chat shows the word "фото".

## A dish and its ingredients

A complex dish (borscht, a sandwich, a salad) is proposed by the agent as one dish with `parts`: 3–12 ingredients for a typical serving, each with grams and its own values per 100 g. The person confirms the whole dish with one yes. The dish's own numbers are never stored apart from its ingredients: they are what the ingredients add up to (`Dish.per100`), so the two cannot disagree. A food of the base keeps the ingredients in `foods.parts` (JSON, amounts of a typical serving); a diary entry made from it keeps its own copy in `entries.parts`, scaled to the portion eaten. In the feed the row has a chevron after the name; the ingredients open under it, and a tap on one changes its grams: the dish weighs what its ingredients now weigh and the entry's numbers follow (`DiaryRepository.updatePart`). The recipe in the base is not touched. Changing the grams of the whole dish scales the ingredients in proportion. Editing a dish's numbers by hand on the food screen makes it a plain food. Ingredients go into both exports. Schema 6 added the two `parts` columns and the `day_facts` table (`Migration5To6`); schema 7 rebuilt `day_facts` without the pulse columns (`Migration6To7`, because test builds had already made the table). Both are covered by `MigrationTest`.

## The watch

Settings → **Watch & accessories** (the `Watch*` classes; one permission dialog for everything) connects Health Connect. Besides the watch it reads `WeightRecord` (the first weigh-in of a day becomes a weight row `scales-<date>` unless a weight was typed that day or that row was removed). Calories burned are not read: they were tried (schema 8 and 9 added `activeKcal` and `totalKcal`), but what the watches put into Health Connect was not right (OHealth writes only per-minute "total" records for the minutes that moved, and Health Connect's aggregates fill the gaps with an estimate of their own), so schema 10 (`Migration9To10`) takes the columns out again; the brownish raspberry chosen for them stays in the theme as `AppColors.cherry`. Every read of today writes one line to the journal (`watch: day … sleep … steps … workouts … weight`). It (a watch writes there through its own app; the phone needs the Health Connect app on Android 13 and older). The app only reads, only what the system permission dialog allowed, and only when it comes to the front, for today and the two days before (30 days on connecting); there is no background work and no background permission. Per day: sleep of the night that ended that day (18:00 to 18:00), steps, workouts (kind and minutes; under 10 minutes ignored, the same workout from two apps counted once); no pulse and no HRV, they are not wanted. Health Connect does not hand over a watch's own wellbeing scores. A day the watch has nothing for never overwrites what was read before. The values live in `day_facts`, show in the day's summary when it is opened, and go into the exports (JSON `watch`, a Markdown line, a separate CSV). `WatchSource` is the interface; `HealthConnectSource` is the only class that knows the SDK, and everything around it is tested with a fake.

## Run the backend

Only needed for server mode. Requires Docker and a [DeepSeek](https://platform.deepseek.com/) key.

```bash
cp .env.example .env            # fill in POSTGRES_PASSWORD, JWT_SECRET, DEEPSEEK_API_KEY
docker compose up -d --build    # the first start downloads the USDA data (~7 MB) and the Whisper model (~470 MB)
docker compose logs gateway | grep "LOGIN CODE"   # login code while SMTP is not configured
```

Generate `JWT_SECRET` with `python -c "import secrets; print(secrets.token_urlsafe(48))"`. The gateway refuses to start with the value `change-me`. The login code is sent by email when `SMTP_*` is set in `.env`, otherwise it is printed to the gateway log.

## Appearance

Settings → **Theme** offers Paper (warm light), Coal (charcoal dark), Matcha (pistachio and cream), and Follow system
(the default, resolving to Paper or Coal). `ui/theme/ThemeStore` loads the choice synchronously from the `appearance`
preferences before the first frame and exposes it as a StateFlow. The choice is included in Android backup and transfer.
`AppTheme(appearance = …)` supplies the same palette to shared components and Material controls. System bar icon contrast
follows the resolved app palette, including a light theme selected while the phone is dark.

The diary has a larger calorie total, quieter goal and macros, and a rounded input surface. Coal uses an inset summary
panel; Matcha uses a pistachio header. Recorded food remains green, timestamps warm, and totals neutral beyond the goal.
`ThemeTest` covers persistence, system following, the picker, and text contrast on all surfaces. `HistoryTest` checks the
calendar in all three palettes; `ScreenshotTest` includes Matcha and a 320 dp screen with 150% text.

## Install the app

Requires JDK 21 and the Android SDK. The Robolectric Android 16+ tests require Java 21; application bytecode still targets Java 17. The easiest way is to open `android/` in Android Studio.

```bash
cd android
./gradlew assembleRelease                                         # phone only, no server
./gradlew assembleRelease -PapiBaseUrl=http://192.168.1.50:8080   # with your own server
adb install -r app/build/outputs/apk/release/app-release.apk
```

- Emulator: `./gradlew assembleDebug`. The default address is `http://10.0.2.2:8080` (the host machine).
- Phone on the same Wi-Fi: set `GATEWAY_BIND=0.0.0.0` in `.env` and pass the computer's IP in `-PapiBaseUrl`. The app talks plain HTTP, so do this only on a network you trust. For the internet put HTTPS in front of the gateway (Caddy, nginx) and pass an `https://…` address: cleartext traffic is disabled in such a build.
- The release APK is signed with the debug key, which is enough for personal use. For your own key create `android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (the file is in `.gitignore`).
- The release APK is about 3 MB: there is no food catalog in it.

## GitHub updates

Settings → About → **Updates** checks `MAppleDm/FatCodex` on GitHub when tapped. No periodic checks or automatic downloads run. The latest stable release is read through the public GitHub API using a separate HTTP client without diary tokens or model keys. Stable `vMAJOR.MINOR.PATCH` tags are compared numerically; drafts, prereleases and downgrades are not offered. Notes and APK size are shown before downloading. With several APK assets, a single explicitly named `universal` APK is preferred; otherwise the person is directed to the release page instead of guessing an ABI.

Android's DownloadManager downloads into the app's `external-files/updates/` folder, with progress, cancellation and a system notification. Its ID and release metadata are kept in `updates` preferences (excluded from backup), so a download survives closing or restarting the app. Before installation the app verifies the size, GitHub's SHA-256 digest when provided, package name, release version, increasing version code, minimum Android version and signing certificate (including key rotation). A failed check deletes the tracked download and offers retry. A verified APK is shared read-only with the system installer through the existing FileProvider. Android asks for permission to install from this source if needed, then asks to confirm the installation; canceling leaves the downloaded file available.

To publish an update, increase **both** `versionCode` and `versionName` in `android/app/build.gradle.kts`, build with the **same signing key** as the installed application, and attach the APK to a published stable GitHub release with the matching version tag, such as `v0.3.0`. A tag or source archive alone is not an installable update. A different signing key is reported explicitly; uninstalling the diary is never offered as a workaround. No release is published by the app or this change.

Implementation and tests are in `app/.../ui/update/`. API references: [GitHub Releases](https://docs.github.com/en/rest/releases/releases#get-the-latest-release), [Android DownloadManager](https://developer.android.com/reference/android/app/DownloadManager).

## Repository layout

```
android/            Gradle project (app, core-ui, data), version catalog in gradle/libs.versions.toml
services/gateway/   REST API for the app, email-code login, JWT, sync
services/ai-parser/ DeepSeek calls, function calling, Redis cache, retries
services/nutrition/ food search and calorie/macro calculation (SQLite + FTS5), USDA and Open Food Facts import
services/stt/       faster-whisper
contracts/          OpenAPI of each service; tests fail if the code drifts from the file
docs/               details (in Russian) and the app icon
```

More food data (USDA archives or an Open Food Facts dump) can be imported with `python -m nutrition.cli import-usda` and `import-off`, see [details.ru.md](details.ru.md). Open Food Facts is licensed under ODbL, USDA FoodData Central is in the public domain.

## Tests

```bash
# backend, in each service: python -m venv .venv && .venv/bin/pip install -e ".[dev]" && .venv/bin/pytest
# Android (JVM, no emulator needed):
cd android && ./gradlew testDebugUnitTest
```

## Limitations

- Accuracy depends on what the agent finds on the web: calorie sites differ from each other. That is why a new food is always offered with its source and waits for your pick, and the app asks about anything unclear instead of guessing. Since the base starts empty, in the first days almost every food needs one confirmation.
- Local mode has no built-in numbers at all. The server mode (`services/nutrition`) still works from USDA and Open Food Facts; it is a separate part.
- If a food has no page on the web the agent offers typical values. They are marked "~"; fix them in the food database if you have the package.
- The agent has not been tested against the real DeepSeek. Tool calls were checked against a stub that answers in the OpenAI-compatible format. If DeepSeek answers differently (for example without a function call), the message stays with the red line and **Try again**, and the reason is in the journal.
- Health Connect is covered on the JVM only through a fake source: the SDK itself, the permission dialog and the data of a real watch have to be checked on a phone.
- Not tested on a real device: all tests run on the JVM (Robolectric). The Docker images, the real DeepSeek and Postgres were checked as separate processes and stubs, but not as a whole `docker compose` stack.
- The server creates its database schema at startup (`create_all`). There are no migrations.
- The DeepSeek key is stored encrypted (AES-GCM, the key lives in the Android Keystore) and is not backed up. The Keystore code is not covered by the JVM tests, because Robolectric has no Keystore: check it on a phone. The server token is still in regular SharedPreferences: it only gives access to your own server.
- In local mode there is no sync between phones: the diary lives on one device (plus Android auto-backup).
