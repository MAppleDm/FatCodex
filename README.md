<img src="docs/icon-source.webp" width="96" alt="FatCodex app icon">

# FatCodex

**English** · [Русский](README.ru.md)

A food diary in a single text box.

Write what you ate, say it out loud, or take a photo of your plate. FatCodex works out what it was and how much, counts calories, protein, fat and carbs, and keeps your diary. No forms, no scrolling through product lists.

## How it feels

- `oatmeal 60 g, banana` → two diary entries with calories and macros
- `less oil`, `that was half`, `remove the bread` → fixes what you already logged
- `weight 82.4` → logs your weight and shows the trend
- first start → five short questions about you (sex, age, height, weight, activity), then an estimate of how many kcal a day you burn
- a photo of your plate → the app recognizes the food and keeps a small picture of it in the chat

## Why you can trust the numbers

The AI only names foods and amounts. Calories come from your own food database, which the AI builds: it looks a food up on the web, shows you the values with their source, and only what you confirm goes into the database. There is no built-in table of "similar" foods to fall back on. Rough estimates are marked with `~`, and when something is unclear the app asks one short question instead of guessing. If the AI cannot be reached, the message waits in the chat with a red **Try again**; nothing is recorded by guesswork.

Your daily goal is worked out, not typed: your answers give what a day costs you, and you add a correction (minus to lose weight, plus to gain).

## Your data

No account and no server are needed: the diary lives on your phone. The app needs internet and DeepSeek with your own key to read what you write or photograph, and only that is sent there.

The app records; it does not write reports. To analyse your history, export it (Settings → History → export) as Markdown, JSON or CSV and give the file to any model or spreadsheet you like.

## Status

An early personal project, free and non-commercial. It is not on Google Play yet and has not been tested on a real phone, so expect rough edges.

To try it, build the app from source: see [docs/development.md](docs/development.md).

## License

MIT, see [LICENSE](LICENSE).
