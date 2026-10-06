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
- If the agent cannot be reached (no network, a refused key, an answer that makes no sense, no answer in two minutes), the message stays in the chat with the reason in red and **Try again** (the agent reads it again) and *remove*. Nothing is recorded in its place. Without a key only a weigh-in ("weight 82.4") can be sent.
- Your own food database lives on the phone next to the diary. It can be edited in the app, managed from the chat, and exported to or imported from `dietapp-foods.json`.
- Voice uses on-device recognition only. For Russian you may need to download the offline pack in Android settings.
- The diary and the chosen mode are included in Android auto-backup. The token and the DeepSeek key are not.
- Settings → About → **Journal** shows every request to DeepSeek and every message the agent could not read, with the reason.

The full description of local mode, the agent and the food database is in Russian: [details.ru.md](details.ru.md).

## Export

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

## Run the backend

Only needed for server mode. Requires Docker and a [DeepSeek](https://platform.deepseek.com/) key.

```bash
cp .env.example .env            # fill in POSTGRES_PASSWORD, JWT_SECRET, DEEPSEEK_API_KEY
docker compose up -d --build    # the first start downloads the USDA data (~7 MB) and the Whisper model (~470 MB)
docker compose logs gateway | grep "LOGIN CODE"   # login code while SMTP is not configured
```

Generate `JWT_SECRET` with `python -c "import secrets; print(secrets.token_urlsafe(48))"`. The gateway refuses to start with the value `change-me`. The login code is sent by email when `SMTP_*` is set in `.env`, otherwise it is printed to the gateway log.

## Install the app

Requires JDK 17+ and the Android SDK. The easiest way is to open `android/` in Android Studio.

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
- Not tested on a real device: all tests run on the JVM (Robolectric). The Docker images, the real DeepSeek and Postgres were checked as separate processes and stubs, but not as a whole `docker compose` stack.
- The server creates its database schema at startup (`create_all`). There are no migrations.
- The DeepSeek key is stored encrypted (AES-GCM, the key lives in the Android Keystore) and is not backed up. The Keystore code is not covered by the JVM tests, because Robolectric has no Keystore: check it on a phone. The server token is still in regular SharedPreferences: it only gives access to your own server.
- In local mode there is no sync between phones: the diary lives on one device (plus Android auto-backup).
