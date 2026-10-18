# Third-party material

## Inter (the app's font)

`android/core-ui/src/main/res/font/inter_regular.ttf`: Inter 3.019, Regular, with Latin and Cyrillic.
Copyright 2020 The Inter Project Authors (https://github.com/rsms/inter).
Licensed under the SIL Open Font License, Version 1.1: https://openfontlicense.org. The full notice and license are bundled in
`android/core-ui/src/main/assets/licenses/Inter-OFL.txt` and included in the APK. The font file is used unchanged.

The sound of a recorded food (`app/src/main/res/raw/added.wav`) is synthesized by `android/tools/make_added_sound.py` from plain sine
waves and noise: it is part of this project and has no third-party material in it.

Other fonts (Verdana Pro and the like) are not part of the app. A font file the person picks in About → Font is copied into the
app's private storage on that phone only.

The app's digits are Inter's tabular figures (`tnum`), not a monospace font. The journal and the agent's prompt, which are code,
use the system monospace.
