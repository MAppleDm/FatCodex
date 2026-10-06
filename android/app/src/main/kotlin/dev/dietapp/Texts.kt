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
    val RETRY get() = t("Повторить", "Try again")
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
    val NEXT get() = t("Далее", "Next")
    val CODE_HINT get() = t("Код придёт на почту.", "The code will come by email.")
    val CHANGE_EMAIL get() = t("другая почта", "another email")
    val NO_SERVER get() = t("без сервера", "without a server")
    val NO_SERVER_HINT get() = t(
        "Нет своего сервера? Всё считается на телефоне. В DeepSeek уходит только то, что ты пишешь или снимаешь.",
        "No server of your own? Everything is worked out on the phone. Only what you write or photograph goes to DeepSeek.",
    )
    val KEY_STEP_HINT get() = t(
        "Без ключа приложение не работает: агент читает всё, что ты пишешь или снимаешь, и ведёт твою базу продуктов. Хранится только на этом телефоне, уходит только в DeepSeek.",
        "The app does not work without a key: the agent reads everything you write or photograph and keeps your food database. Kept on this phone only, sent to DeepSeek only.",
    )
    val SKIP get() = t("пропустить", "skip")

    // about me: what the person says about themselves, and what a day costs them
    val ME get() = t("О себе", "About me")
    val ME_SEX get() = t("Пол", "Sex")
    val ME_MALE get() = t("Мужской", "Male")
    val ME_FEMALE get() = t("Женский", "Female")
    val ME_AGE get() = t("Возраст", "Age")
    val ME_YEARS get() = t("лет", "years")
    val ME_HEIGHT get() = t("Рост", "Height")
    val ME_CM get() = t("см", "cm")
    val ME_WEIGHT get() = t("Вес", "Weight")
    val ME_WEIGHT_HINT get() = t(
        "Это последнее взвешивание из дневника. Напиши в чате «вес 82.4», и здесь оно обновится само.",
        "This is the latest weigh-in of the diary. Write “weight 82.4” in the chat and it updates here by itself.",
    )
    val ME_ACTIVITY get() = t("Активность", "Activity")
    val ME_ENERGY get() = t("Средний расход в день", "Average energy use a day")
    val ME_KCAL_A_DAY get() = t("ккал в день", "kcal a day")
    val ME_INCOMPLETE get() = t(
        "Заполни все пункты ниже, и здесь появятся средний расход и цель.",
        "Fill in everything below and your average energy use and your goal appear here.",
    )
    val ME_GOAL get() = t("Цель на день", "Goal for the day")
    val ME_ADJUSTMENT get() = t("Корректировка", "Correction")
    val ME_ADJUSTMENT_HINT get() = t(
        "Минус, чтобы худеть, плюс, чтобы набирать. Ноль оставляет цель равной расходу.",
        "Minus to lose weight, plus to gain. Zero keeps the goal equal to what a day costs.",
    )

    /** "Расход 2 710, корректировка −300" */
    fun meGoalSum(tdee: Int, adjustment: Int): String = t(
        "Расход ${dev.dietapp.coreui.formatInt(tdee)}, корректировка ${signedKcal(adjustment)}",
        "Energy use ${dev.dietapp.coreui.formatInt(tdee)}, correction ${signedKcal(adjustment)}",
    )

    fun meGoalFloor(min: Int): String = t(
        "Ниже ${dev.dietapp.coreui.formatInt(min)} ккал цель не опускается: меньше без наблюдения врача не рекомендуется.",
        "The goal does not go below ${dev.dietapp.coreui.formatInt(min)} kcal: less is not recommended without a doctor's supervision.",
    )

    /** "+300", "−300" (a real minus sign), "0". */
    fun signedKcal(v: Int): String = when {
        v > 0 -> "+${dev.dietapp.coreui.formatInt(v)}"
        v < 0 -> "−${dev.dietapp.coreui.formatInt(-v)}"
        else -> "0"
    }
    val ME_FORMULA get() = t(
        "Считается по формуле Миффлина — Сан-Жеора: энергия в покое, умноженная на коэффициент активности. Это оценка для среднего взрослого человека, а не медицинская рекомендация.",
        "Worked out with the Mifflin–St Jeor equation: the energy at rest times an activity multiplier. It is an estimate for an average adult, not medical advice.",
    )

    fun meRest(bmr: Int, factor: String): String =
        t("В покое ${dev.dietapp.coreui.formatInt(bmr)} ккал, умножено на $factor", "${dev.dietapp.coreui.formatInt(bmr)} kcal at rest, times $factor")

    fun activityName(a: dev.dietapp.data.domain.Activity): String = when (a) {
        dev.dietapp.data.domain.Activity.Sedentary -> t("Почти не двигаюсь", "Hardly move")
        dev.dietapp.data.domain.Activity.Light -> t("Немного", "A little")
        dev.dietapp.data.domain.Activity.Moderate -> t("Умеренно", "Moderately")
        dev.dietapp.data.domain.Activity.High -> t("Много", "A lot")
        dev.dietapp.data.domain.Activity.Extreme -> t("Очень много", "A great deal")
    }

    fun activityHint(a: dev.dietapp.data.domain.Activity): String = when (a) {
        dev.dietapp.data.domain.Activity.Sedentary -> t("Сидячая работа, тренировок нет.", "A desk job, no workouts.")
        dev.dietapp.data.domain.Activity.Light -> t("Прогулки или лёгкие тренировки 1–3 раза в неделю.", "Walks or light workouts 1–3 times a week.")
        dev.dietapp.data.domain.Activity.Moderate -> t("Тренировки 3–5 раз в неделю или работа на ногах.", "Workouts 3–5 times a week, or a job on your feet.")
        dev.dietapp.data.domain.Activity.High -> t("Интенсивные тренировки 6–7 раз в неделю.", "Hard workouts 6–7 times a week.")
        dev.dietapp.data.domain.Activity.Extreme -> t("Тяжёлый физический труд или две тренировки в день.", "Heavy physical work, or two workouts a day.")
    }

    // the first-run questions, one at a time
    val WIZARD_BACK get() = t("назад", "back")
    val WIZARD_DONE get() = t("Готово", "Done")
    val WIZARD_SEX_Q get() = t("Какой у тебя пол?", "What is your sex?")
    val WIZARD_SEX_WHY get() = t("Нужно для формулы: у мужчин и женщин разный обмен веществ.", "The formula needs it: men and women burn energy differently.")
    val WIZARD_AGE_Q get() = t("Сколько тебе лет?", "How old are you?")
    val WIZARD_AGE_WHY get() = t("С возрастом обмен веществ замедляется.", "Metabolism slows down with age.")
    val WIZARD_HEIGHT_Q get() = t("Какой у тебя рост?", "How tall are you?")
    val WIZARD_HEIGHT_WHY get() = t("Чем выше человек, тем больше энергии уходит в покое.", "The taller a person is, the more energy goes at rest.")
    val WIZARD_WEIGHT_Q get() = t("Сколько ты весишь?", "How much do you weigh?")
    val WIZARD_WEIGHT_WHY get() = t(
        "Это станет первой записью веса в дневнике. Дальше его можно писать в чате: «вес 82.4».",
        "This becomes the first weight in the diary. Later you can write it in the chat: “weight 82.4”.",
    )
    val WIZARD_ACTIVITY_Q get() = t("Насколько ты активен?", "How active are you?")
    val WIZARD_ACTIVITY_WHY get() = t("Выбери то, что больше похоже на обычную неделю.", "Pick what looks most like an ordinary week.")
    val WIZARD_RESULT_Q get() = t("Твой средний расход", "Your average energy use")
    val WIZARD_RESULT_HINT get() = t(
        "Цель на день считается от него. Корректировку и всё остальное можно поменять потом в настройках, в разделе «О себе».",
        "The goal for the day is worked out from it. You can change the correction and all the rest later in settings, under “About me”.",
    )

    fun wizardProgress(step: Int, total: Int): String = t("$step из $total", "$step of $total")

    // about
    val ABOUT get() = t("О приложении", "About")
    val ABOUT_TAGLINE get() = t(
        "Дневник питания в одном поле ввода. Личный проект, бесплатный и некоммерческий.",
        "A food diary in a single text box. A personal project, free and non-commercial.",
    )
    val ABOUT_VERSION get() = t("Версия", "Version")
    val ABOUT_DATA get() = t("Данные", "Your data")
    val ABOUT_LOCAL_COPY get() = t(
        "Это единственная копия дневника. Если стереть всё, вернуть её нельзя. Дневник входит в автобэкап Android, если он включён на телефоне.",
        "This is the only copy of the diary. If you erase everything it cannot be brought back. The diary is part of Android's auto-backup, if that is on for the phone.",
    )
    val ABOUT_SERVER_COPY get() = t(
        "Дневник синхронизируется с твоим сервером, на телефоне лежит его копия.",
        "The diary is synchronised with your server; the phone holds a copy.",
    )
    val ABOUT_LOCAL_SENT get() = t(
        "В DeepSeek уходит только то, что ты пишешь или фотографируешь, в DuckDuckGo только название продукта при поиске. Ключ DeepSeek лежит на телефоне в зашифрованном виде и в резервные копии не попадает.",
        "Only what you write or photograph goes to DeepSeek, and only a food's name goes to DuckDuckGo when searching. The DeepSeek key is kept on the phone, encrypted, and is not part of backups.",
    )
    val ABOUT_SERVER_SENT get() = t(
        "Сообщения разбирает твой сервер, ключа DeepSeek на телефоне нет.",
        "Your server reads the messages; there is no DeepSeek key on the phone.",
    )
    val ABOUT_DANGER get() = t("Опасная зона", "Danger zone")
    val ABOUT_ERASE_DETAILS get() = t(
        "Удалит с этого телефона дневник, базу продуктов, вес и ключ DeepSeek. Вернуть их нельзя. Чтобы не стереть случайно, нажать нужно дважды.",
        "Deletes the diary, the food base, the weights and the DeepSeek key from this phone. They cannot be brought back. To keep it from happening by accident, you have to tap twice.",
    )
    val ABOUT_MODEL get() = t("Модель", "The model")
    val ABOUT_JOURNAL_HINT get() = t(
        "Что приложение отправило модели, что она ответила и почему что-то не получилось. Журнал можно сохранить или отправить.",
        "What the app sent to the model, what it answered and why something did not work. The journal can be saved or sent.",
    )
    val ABOUT_LINKS get() = t("Ссылки", "Links")
    val ABOUT_GITHUB get() = "GitHub"
    val ABOUT_LICENSE get() = t("Лицензия", "License")
    val ABOUT_LICENSE_NAME get() = "MIT"

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
        "Без ключа приложение не работает: агент читает каждое сообщение и фото и ведёт твою базу продуктов. Ключ хранится только на этом телефоне.",
        "The app does not work without a key: the agent reads every message and photo and keeps your food database. The key is kept on this phone only.",
    )
    val REMOVE get() = t("убрать", "remove")
    val ERASE get() = t("Стереть всё", "Erase all")
    val ERASE_CONFIRM get() = t("Точно? Нажми ещё раз", "Sure? Tap again")
    val LANGUAGE get() = t("Язык", "Language")
    val LANGUAGE_NAME get() = t("Русский", "English")

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

    // the history page
    val HISTORY_EXPORT get() = t("экспорт", "export")

    // export of the history
    val EXPORT get() = t("Экспорт истории", "Export history")
    val EXPORT_HINT get() = t(
        "Файл с дневником для разбора в другой модели или в таблице. Приложение само историю не анализирует: оно записывает, а сводки лучше делать там.",
        "The diary as a file to analyse in another model or a spreadsheet. The app does not analyse the history itself: it records, and summaries are better made there.",
    )
    val EXPORT_PERIOD get() = t("Период", "Period")
    val EXPORT_FORMAT get() = t("Формат", "Format")
    val EXPORT_SAVE get() = t("сохранить", "save")
    val EXPORT_SHARE get() = t("отправить", "send")
    val EXPORT_SAVED get() = t("Файл сохранён.", "File saved.")
    val EXPORT_SUBJECT get() = t("FatCodex: дневник", "FatCodex: diary")

    fun exportPeriod(p: dev.dietapp.data.export.ExportPeriod): String = when (p) {
        dev.dietapp.data.export.ExportPeriod.Week -> t("Последние 7 дней", "Last 7 days")
        dev.dietapp.data.export.ExportPeriod.Month -> t("Последние 30 дней", "Last 30 days")
        dev.dietapp.data.export.ExportPeriod.Quarter -> t("Последние 90 дней", "Last 90 days")
        dev.dietapp.data.export.ExportPeriod.All -> t("Всё время", "All time")
    }

    fun exportFormat(f: dev.dietapp.data.export.ExportFormat): String = when (f) {
        dev.dietapp.data.export.ExportFormat.Markdown -> "Markdown (.md)"
        dev.dietapp.data.export.ExportFormat.Json -> "JSON (.json)"
        dev.dietapp.data.export.ExportFormat.CsvFood -> t("CSV: еда (.csv)", "CSV: food (.csv)")
        dev.dietapp.data.export.ExportFormat.CsvWeight -> t("CSV: вес (.csv)", "CSV: weight (.csv)")
    }

    fun exportFormatHint(f: dev.dietapp.data.export.ExportFormat): String = when (f) {
        dev.dietapp.data.export.ExportFormat.Markdown -> t("Читается человеком и моделью: раздел на каждый день, таблицы.",
            "Readable by a person and a model: a section per day, tables.")
        dev.dietapp.data.export.ExportFormat.Json -> t("Всё, с пояснением полей: для программ и моделей.", "Everything, with the fields explained: for programs and models.")
        dev.dietapp.data.export.ExportFormat.CsvFood -> t("Одна строка на продукт: для таблиц.", "One row per food: for spreadsheets.")
        dev.dietapp.data.export.ExportFormat.CsvWeight -> t("Вес по дням и недельный тренд.", "Weight by day and the weekly trend.")
    }

    // a change to the food base that waits for a yes
    val BASE_CHANGE_YES get() = t("да", "yes")
    val BASE_CHANGE_NO get() = t("нет", "no")
    val BASE_CHANGE_FAILED get() = t("Не удалось изменить базу продуктов.", "Could not change the food base.")

    // agent settings
    val AGENT get() = t("Агент", "Agent")
    val AGENT_PROVIDER get() = t("Ключ", "Key")
    val AGENT_PROVIDER_DEEPSEEK get() = "DeepSeek API"
    val AGENT_WEB get() = t("Поиск в интернете", "Web search")
    val AGENT_WEB_ON get() = t("включён", "on")
    val AGENT_WEB_HINT get() = t(
        "Незнакомые продукты агент ищет через DuckDuckGo и предлагает варианты КБЖУ. В поиск уходит только название продукта. Всегда включён.",
        "The agent looks unfamiliar foods up on DuckDuckGo and offers their values. Only the food's name goes to the search. Always on.",
    )
    val AGENT_PROMPT get() = t("Системный промпт", "System prompt")
    val AGENT_PROMPT_HINT get() = t(
        "Это инструкция, которую агент получает перед каждым сообщением. Пока её нельзя менять.",
        "This is the instruction the agent gets before every message. It cannot be changed yet.",
    )

    // food base
    val FOODS get() = t("База продуктов", "Food base")
    val FOODS_SEARCH get() = t("Поиск", "Search")
    val FOODS_ADD get() = t("добавить", "add")
    val FOODS_EXPORT get() = t("экспорт", "export")
    val FOODS_IMPORT get() = t("импорт", "import")
    val FOODS_MINE get() = t("Мои продукты", "My foods")
    val FOODS_EMPTY get() = t(
        "Пока пусто. Продукты попадают сюда, когда агент находит их в интернете и ты подтверждаешь значения, или когда ты добавляешь их сам.",
        "Nothing yet. Foods land here when the agent finds them on the web and you confirm the values, or when you add them yourself.",
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
