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
- A calorie goal below 1,200 kcal is rejected by both client and server, and the wording stays neutral.

## Local mode

On the login screen choose "without a server": no email is needed and the diary stays on the phone. Then paste **your own DeepSeek key** once (optional: without it only the built-in dictionary works) and set a goal.

- The key is not in the APK. You enter it yourself and it is stored in the app's private settings. The model receives only what you typed or photographed, and the request goes straight to `api.deepseek.com` and nowhere else. This needs internet. Offline, text is parsed with the dictionary.
- A **USDA SR Legacy** catalog (about 7,800 rows, 170 KB) ships inside the APK, with search ported from the server to Kotlin.
- A **Russian dictionary** (about 200 foods and dishes, such as borscht or dumplings) turns words into catalog queries and knows the weight of a piece, a spoon, a glass or a portion. Dishes with no USDA equivalent are calculated from a table and marked with "~": that is an estimate, not an exact figure.
- Complex phrases and **photos** are parsed by DeepSeek, which works as an agent over your food database. It can search your database, the dish table, USDA and the web (DuckDuckGo), then **proposes** values. Nothing is added to the database without your choice.
- Your own food database lives on the phone next to the diary. It can be edited in the app, managed from the chat, and exported to or imported from `dietapp-foods.json`.
- Voice uses on-device recognition only. For Russian you may need to download the offline pack in Android settings.
- The diary and the chosen mode are included in Android auto-backup. The token and the DeepSeek key are not.
- If the model is unavailable, text is parsed with the dictionary and the feed says so. Settings → **Journal** shows every request to DeepSeek and why the app fell back.

The full description of local mode, the agent and the food database is in Russian: [details.ru.md](details.ru.md).

## Export

Settings → **Export history** writes the diary to a file for another model, a script or a spreadsheet to read. The app does not analyse the history itself: it records, and summaries are better made elsewhere. Pick the period (last 7, 30 or 90 days, or all time), then **save** (a file you place yourself) or **send** (straight to another app). It works the same with a server and without: it reads the phone's own copy. Only recorded food is exported: deleted food and food still waiting for "record" are not in the diary.

| Format | For | Content |
|---|---|---|
| Markdown (`.md`) | a person, a chat with a model | one section per day: totals, weight, a table of foods; the text follows the app's language |
| JSON (`.json`) | programs, models that work with structured data | days with totals, entries (amounts, numbers, per-100 g values, matched food, status, source), weight and trend, and a legend explaining each field |
| CSV: food (`.csv`) | spreadsheets, pandas | one row per food: `date,time,meal_id,name,grams,kcal,protein_g,fat_g,carbs_g,kcal_per_100g,matched_food,status,source` |
| CSV: weight (`.csv`) | spreadsheets, pandas | `date,kg,trend_7d_kg`, one row per weigh-in day |

- Numbers use a dot as the decimal mark whatever the phone's language, one decimal at most. CSV is UTF-8 with commas (RFC 4180) and no BOM: in a Russian-locale Excel use *Data → From text/CSV* and pick UTF-8.
- `status`: `ok` numbers from a food database, `uncertain` approximate (typical values or a guessed amount; marked `~` in Markdown), `unmatched` the food was not found: no numbers, not counted in the day's total. Missing numbers are empty cells (CSV) or `null` (JSON), never zeros.
- Files are named `fatcodex-<diary|food|weight>-<7d|30d|90d|all>-<date>.<ext>`.

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
- The release APK is about 2.7 MB including the food catalog.

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

- Accuracy depends on how the model names a food. Search ranks USDA names by their structure ("Apples, raw", not "Croissants, apple"), but it is a heuristic: check the numbers if something looks odd. The app asks about anything unclear instead of guessing.
- Numbers the model adds to the database on its own are typical values for that kind of food, not your label. They are marked "~"; fix them in the food database if you have the package.
- The agent has not been tested against the real DeepSeek. Tool calls were checked against a stub that answers in the OpenAI-compatible format. If DeepSeek answers differently (for example without a function call), the app parses the message with the dictionary and says so.
- Not tested on a real device: all tests run on the JVM (Robolectric). The Docker images, the real DeepSeek and Postgres were checked as separate processes and stubs, but not as a whole `docker compose` stack.
- The server creates its database schema at startup (`create_all`). There are no migrations.
- The DeepSeek key is stored encrypted (AES-GCM, the key lives in the Android Keystore) and is not backed up. The Keystore code is not covered by the JVM tests, because Robolectric has no Keystore: check it on a phone. The server token is still in regular SharedPreferences: it only gives access to your own server.
- In local mode there is no sync between phones: the diary lives on one device (plus Android auto-backup).
- The local dictionary is small and Russian. English names are found directly in the USDA catalog; anything else needs the model (a key) or the server.
