<img src="docs/icon-source.webp" width="96" alt="FatCodex app icon">

# FatCodex

**English** · [Русский](README.ru.md)

A food diary in a single text box.

Write what you ate, say it out loud, or take a photo of your plate. FatCodex works out what it was and how much, counts calories, protein, fat and carbs, and keeps your diary. No forms, no scrolling through product lists.

## How it feels

- `oatmeal 60 g, banana` → two diary entries with calories and macros
- `less oil`, `that was half`, `remove the bread` → fixes what you already logged
- `weight 82.4` → logs your weight and shows the trend
- a photo of your plate → the app recognizes the food

## Why you can trust the numbers

The AI only names foods and amounts. Calories come from real food databases (USDA, Open Food Facts), not from the AI's imagination. Rough estimates are marked with `~`, and when something is unclear the app asks one short question instead of guessing.

## Your data

No account and no server are needed: the diary lives on your phone and works offline. To understand photos and complex phrases the app uses DeepSeek with your own key, which needs internet. Only what you type or photograph is sent there.

The app records; it does not write reports. To analyse your history, export it (Settings → Export history) as Markdown, JSON or CSV and give the file to any model or spreadsheet you like.

## Status

An early personal project, free and non-commercial. It is not on Google Play yet and has not been tested on a real phone, so expect rough edges.

To try it, build the app from source: see [docs/development.md](docs/development.md).

## License

MIT, see [LICENSE](LICENSE).
