package dev.dietapp

import dev.dietapp.data.domain.Lang.t

/**
 * All user-facing text in one place, in Russian and English. The language is the user's choice in settings (see
 * dev.dietapp.data.domain.Lang); the screens are rebuilt when it changes.
 */
object Texts {
    val INPUT_PLACEHOLDER get() = t("Что съел?", "What did you eat?")
    val EMPTY_HINT get() = t("Например: «овсянка 60 г, банан», «вес 82.4» или «как обычно».",
        "For example: “oatmeal 60 g, a banana”, “weight 82.4” or “the usual”.")

    // feed
    val WEIGHT_LABEL get() = t("вес", "weight")
    val TREND get() = t("тренд", "trend")
    val DELETE get() = t("удалить", "delete")
    val CANCEL get() = t("отмена", "cancel")
    val BACK_TO_TODAY get() = t("к сегодняшнему дню", "back to today")
    val STATUS_WAITING get() = "…"
    val STATUS_OFFLINE get() = t("нет сети", "offline")
    val STATUS_RETRY get() = t("повтор", "retrying")
    val STATUS_FAILED get() = t("ошибка", "failed")
    val TAP_TO_REMOVE get() = t("Нажми, чтобы убрать.", "Tap to remove.")
    val PHOTO get() = t("фото", "photo")

    // day summary details
    val LEFT get() = t("Осталось", "Left")
    val OVER get() = t("Выше цели на", "Over the goal by")
    val KCAL get() = t("ккал", "kcal")
    val PROTEIN get() = t("Белки", "Protein")
    val FAT get() = t("Жиры", "Fat")
    val CARBS get() = t("Углеводы", "Carbs")
    val WEIGHT get() = t("Вес", "Weight")
    val KG get() = t("кг", "kg")
    val GRAMS get() = t("г", "g")

    // voice / camera
    val VOICE_NO_MATCH get() = t("Не расслышал. Попробуй ещё раз.", "Didn't catch that. Try again.")
    val VOICE_NO_NETWORK get() = t("Для распознавания речи нужна сеть.", "Speech recognition needs a network.")
    val VOICE_AUDIO get() = t("Микрофон сейчас недоступен.", "The microphone is not available right now.")
    val VOICE_DENIED get() = t("Нужен доступ к микрофону.", "Microphone access is needed.")
    val VOICE_RECORDING_HINT get() = t("Идёт запись. Нажми микрофон, чтобы закончить.", "Recording. Tap the microphone to stop.")
    val CAMERA_DENIED get() = t("Нужен доступ к камере.", "Camera access is needed.")
    val CAMERA_FAILED get() = t("Не удалось сделать снимок.", "Could not take the picture.")

    // login
    val EMAIL get() = t("Почта", "Email")
    val CODE get() = t("Код из письма", "Code from the email")
    val GOAL get() = t("Цель, ккал в день", "Goal, kcal a day")
    val NEXT get() = t("Далее", "Next")
    val CODE_HINT get() = t("Код придёт на почту.", "The code will come by email.")
    val GOAL_HINT get() = t("Цель можно изменить позже.", "The goal can be changed later.")
    val GOAL_NOT_A_NUMBER get() = t("Введи число, например 1900.", "Enter a number, for example 1900.")
    val CHANGE_EMAIL get() = t("другая почта", "another email")
    val NO_SERVER get() = t("без сервера", "without a server")
    val NO_SERVER_HINT get() = t(
        "Нет своего сервера? Всё считается на телефоне. В DeepSeek уходит только то, что ты пишешь или снимаешь.",
        "No server of your own? Everything is worked out on the phone. Only what you write or photograph goes to DeepSeek.",
    )
    val KEY_STEP_HINT get() = t(
        "Нужен для фото и сложных фраз. Хранится только на этом телефоне, уходит только в DeepSeek.",
        "Needed for photos and free-form phrases. Kept on this phone only, sent to DeepSeek only.",
    )
    val SKIP get() = t("пропустить", "skip")

    // settings
    val SETTINGS get() = t("Настройки", "Settings")
    val SAVE get() = t("сохранить", "save")
    val HISTORY get() = t("История", "History")
    val LOG_OUT get() = t("выйти", "sign out")
    val NO_HISTORY get() = t("Пока пусто.", "Nothing yet.")
    val SAVED get() = t("Сохранено.", "Saved.")
    val LOCAL_MODE get() = t("Без сервера: всё считается на этом телефоне.", "No server: everything is worked out on this phone.")
    val MODEL_KEY get() = t("Ключ DeepSeek", "DeepSeek key")
    val MODEL_KEY_SET get() = t("Ключ DeepSeek задан", "DeepSeek key is set")
    val MODEL_KEY_HINT get() = t(
        "Нужен для фото и сложных фраз. Без него еда разбирается по встроенному словарю. Ключ хранится только на этом телефоне.",
        "Needed for photos and free-form phrases. Without it food is read with the built-in (Russian) dictionary. The key is kept on this phone only.",
    )
    val REMOVE get() = t("убрать", "remove")
    val ERASE get() = t("стереть всё", "erase all")
    val ERASE_CONFIRM get() = t("точно? нажми ещё раз", "sure? tap again")
    val ERASE_HINT get() = t("Это единственная копия дневника.", "This is the only copy of the diary.")
    val LANGUAGE get() = t("Язык", "Language")
    val LANGUAGE_NAME get() = t("Русский", "English")

    // record modes
    val RECORD_MODE get() = t("Режим", "Mode")
    val MODE_STANDARD get() = t("стандартный", "standard")
    val MODE_PRECISE get() = t("точный", "precise")
    val MODE_STANDARD_HINT get() = t(
        "Привычная еда с известными цифрами записывается сразу. Если цифры зависят от марки или рецепта, агент спросит.",
        "Familiar food with known values is recorded at once. When the values depend on the brand or the recipe, the agent asks.",
    )
    val MODE_PRECISE_HINT get() = t(
        "Любая еда записывается только после «записать». До этого она серая и не считается.",
        "Every food is recorded only after “record”. Until then it is grey and not counted.",
    )

    // proposals: values the model found, to confirm
    val PROPOSAL_OWN get() = t("свой вариант", "my own values")
    val PROPOSAL_SKIP get() = t("пропустить", "skip")
    val PROPOSAL_SAVE get() = t("занести", "save")
    val PROPOSAL_OWN_SOURCE get() = t("введено вручную", "entered by hand")
    val PROPOSAL_TYPICAL get() = t("типичные значения", "typical values")

    // "Записать?"
    val CONFIRM_QUESTION get() = t("Записать?", "Record?")
    val CONFIRM_RECORD get() = t("записать", "record")
    val CONFIRM_DROP get() = t("не записывать", "don't record")
    val QUESTION_HINT get() = t("Ответь сообщением ниже.", "Answer with a message below.")

    // an entry opened for editing
    val ENTRY_PER100 get() = t("на 100 г", "per 100 g")
    val ENTRY_UNKNOWN get() = t("КБЖУ неизвестны: такого продукта нет в базе.", "Values unknown: this food is not in the base.")
    val ENTRY_NAME get() = t("название", "name")
    val ENTRY_GRAMS get() = t("граммы", "grams")
    val ENTRY_HISTORY get() = t("Как выбрано", "How it was chosen")

    // web search
    val WEB_SEARCH get() = t("Поиск в интернете", "Web search")
    val WEB_SEARCH_HINT get() = t(
        "Незнакомые продукты модель ищет через DuckDuckGo и предлагает варианты КБЖУ. В поиск уходит только название продукта.",
        "The model looks unfamiliar foods up on DuckDuckGo and offers their values. Only the food's name goes to the search.",
    )
    val ON get() = t("вкл", "on")
    val OFF get() = t("выкл", "off")

    // food base
    val FOODS get() = t("База продуктов", "Food base")
    val FOODS_SEARCH get() = t("Поиск", "Search")
    val FOODS_ADD get() = t("добавить", "add")
    val FOODS_EXPORT get() = t("экспорт", "export")
    val FOODS_IMPORT get() = t("импорт", "import")
    val FOODS_MINE get() = t("Мои продукты", "My foods")
    val FOODS_BUILT_IN get() = t("Встроенная база. Нажми, чтобы сделать свою копию.", "Built-in base. Tap to make your own copy.")
    val FOODS_EMPTY get() = t(
        "Пока пусто. Сюда попадают продукты, которых нет во встроенной базе: их добавляет модель из чата или ты сам.",
        "Nothing yet. Foods the built-in base lacks land here: added by the model from the chat, or by you.",
    )
    val FOODS_NOTHING_FOUND get() = t("Среди своих продуктов такого нет.", "None of your foods match.")
    val FOODS_ESTIMATED get() = t("оценка", "estimate")
    val FOODS_ESTIMATED_HINT get() = t("цифры оценила модель; если поправишь их, они станут твоими",
        "the model estimated these values; once you correct them they are yours")
    val FOODS_PER100 get() = t("на 100 г", "per 100 g")
    val FOODS_NAME get() = t("Название", "Name")
    val FOODS_ALIASES get() = t("Другие названия, через запятую", "Other names, comma-separated")
    val FOODS_NOTE get() = t("Например: этикетка, сайт производителя", "For example: the label, the maker's site")
    val FOODS_NOTE_LABEL get() = t("Откуда цифры", "Where the values come from")
    val FOODS_ADDED get() = t("Добавлен", "Added")
    val FOODS_CHANGED get() = t("Изменён", "Changed")
    val FOODS_SOURCE get() = t("Источник:", "Source:")
    val ORIGIN_USER get() = t("вручную", "by hand")
    val ORIGIN_MODEL get() = t("моделью", "by the model")
    val ORIGIN_WEB get() = t("из интернета, подтверждено тобой", "from the web, confirmed by you")
    val ORIGIN_IMPORT get() = t("из файла", "from a file")
    val FOODS_EXPORTED get() = t("Файл сохранён.", "File saved.")
    val FOODS_IMPORTED get() = t("Добавлено:", "Added:")
    val FOODS_UPDATED get() = t("обновлено:", "updated:")
    val FOODS_SKIPPED get() = t("Пропущено:", "Skipped:")
    val FOODS_FILE_FAILED get() = t("Не удалось прочитать или записать файл.", "Could not read or write the file.")
    val P get() = t("Б", "P")
    val F get() = t("Ж", "F")
    val C get() = t("У", "C")

    // diagnostics journal
    val JOURNAL get() = t("Журнал", "Journal")
    val JOURNAL_SAVE get() = t("сохранить", "save")
    val JOURNAL_SAVED get() = t("Журнал сохранён в файл.", "The journal is saved to a file.")
    val JOURNAL_SHARE get() = t("отправить", "send")
    val JOURNAL_SUBJECT get() = t("FatCodex: журнал", "FatCodex: journal")
    val JOURNAL_CLEAR get() = t("очистить", "clear")
    val JOURNAL_EMPTY get() = t(
        "Пока пусто. Здесь будет видно, что приложение отправило модели, что она ответила и почему что-то не получилось.",
        "Nothing yet. Here you will see what the app sent to the model, what it answered and why something did not work.",
    )

    // accessibility
    val CD_SETTINGS get() = t("Настройки и история", "Settings and history")
    val CD_BACK get() = t("Назад", "Back")
    val CD_CLOSE get() = t("Закрыть", "Close")
    val CD_SHUTTER get() = t("Сделать снимок", "Take a picture")
    val CD_SUMMARY get() = t("Итог дня", "Day total")
    val CD_SEND get() = t("Отправить", "Send")
    val CD_MIC get() = t("Микрофон", "Microphone")
    val CD_CAMERA get() = t("Камера", "Camera")
}
