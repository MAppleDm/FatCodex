package dev.dietapp

import dev.dietapp.data.domain.Lang.t

/**
 * All user-facing text in one place, in Russian and English. The language is the user's choice in settings (see
 * dev.dietapp.data.domain.Lang); the screens are rebuilt when it changes.
 */
object Texts {
    val THEME get() = t("Тема", "Theme")
    val THEME_CLOSE get() = t("Готово", "Done")
    fun themeName(theme: dev.dietapp.coreui.AppAppearance): String = when (theme) {
        dev.dietapp.coreui.AppAppearance.System -> t("Как в системе", "Follow system")
        dev.dietapp.coreui.AppAppearance.Paper -> t("Бумага", "Paper")
        dev.dietapp.coreui.AppAppearance.Coal -> t("Уголь", "Coal")
        dev.dietapp.coreui.AppAppearance.Matcha -> t("Матча", "Matcha")
    }
    fun themeDescription(theme: dev.dietapp.coreui.AppAppearance): String = when (theme) {
        dev.dietapp.coreui.AppAppearance.System -> t("Светлая или тёмная — как на телефоне", "Light or dark, following your phone")
        dev.dietapp.coreui.AppAppearance.Paper -> t("Светлая · овёс и лён", "Light · oats and linen")
        dev.dietapp.coreui.AppAppearance.Coal -> t("Тёмная · керамика и янтарь", "Dark · ceramic and amber")
        dev.dietapp.coreui.AppAppearance.Matcha -> t("Фирменная · фисташка и сливки", "Signature · pistachio and cream")
    }
    val UPDATES get() = t("Обновления", "Updates")
    val UPDATE_HINT get() = t("Новые версии из GitHub. Проверка и скачивание — по нажатию.", "New versions from GitHub. Check and download when you choose.")
    val UPDATE_CHECK get() = t("Проверить обновления", "Check for updates")
    val UPDATE_CHECKING get() = t("Проверяю GitHub…", "Checking GitHub…")
    val UPDATE_CURRENT get() = t("Установлена актуальная версия.", "You're up to date.")
    val UPDATE_NO_RELEASES get() = t("На GitHub пока нет опубликованных стабильных релизов.", "No stable releases have been published on GitHub yet.")
    fun updateAvailable(tag: String) = t("Доступна версия $tag", "Version $tag is available")
    val UPDATE_DOWNLOAD get() = t("Скачать обновление", "Download update")
    val UPDATE_DOWNLOADING get() = t("Скачиваю обновление…", "Downloading update…")
    val UPDATE_WAITING get() = t("Загрузка приостановлена Android; продолжится автоматически.", "Download paused by Android; it will resume automatically.")
    val UPDATE_VERIFYING get() = t("Проверяю файл и подпись…", "Checking file and signature…")
    val UPDATE_READY get() = t("Обновление скачано и проверено.", "Update downloaded and verified.")
    val UPDATE_INSTALL get() = t("Установить обновление", "Install update")
    val UPDATE_INSTALL_HINT get() = t("Android попросит подтвердить установку. При необходимости разрешите установку из FatCodex.", "Android will ask you to confirm. If needed, allow installation from FatCodex.")
    val UPDATE_REMOVE get() = t("Удалить скачанный файл", "Remove downloaded file")
    val UPDATE_RELEASE_PAGE get() = t("Релизы на GitHub", "Releases on GitHub")
    val UPDATE_NO_APK get() = t("В релизе нет однозначно подходящего APK. Выберите файл на странице релизов.", "This release has no unambiguous APK. Choose a file on the releases page.")
    fun updateProblem(problem: dev.dietapp.ui.update.UpdateProblem): String = when (problem) {
        dev.dietapp.ui.update.UpdateProblem.Network -> t("Не удалось связаться с GitHub. Проверьте интернет и повторите.", "Could not reach GitHub. Check your connection and try again.")
        dev.dietapp.ui.update.UpdateProblem.RateLimit -> t("GitHub временно ограничил запросы. Попробуйте позже.", "GitHub is limiting requests. Try again later.")
        dev.dietapp.ui.update.UpdateProblem.Release -> t("Не удалось прочитать релиз. Откройте GitHub или повторите позже.", "Could not read the release. Open GitHub or try again later.")
        dev.dietapp.ui.update.UpdateProblem.Download -> t("Не удалось скачать файл. Можно повторить загрузку.", "Download failed. You can try again.")
        dev.dietapp.ui.update.UpdateProblem.Storage -> t("Не хватает места или хранилище недоступно. Освободите место и повторите.", "Storage is full or unavailable. Free some space and try again.")
        dev.dietapp.ui.update.UpdateProblem.Integrity -> t("Файл повреждён или не совпадает с релизом. Скачайте заново.", "The file is damaged or does not match the release. Download it again.")
        dev.dietapp.ui.update.UpdateProblem.Incompatible -> t("Этот APK не подходит приложению или версии Android. Проверьте страницу релиза.", "This APK does not match the app or Android version. Check the release page.")
        dev.dietapp.ui.update.UpdateProblem.Signature -> t("APK подписан другим ключом. Обновление поверх невозможно. Сохраните дневник; не удаляйте приложение ради установки.", "This APK has a different signing key and cannot update this installation. Save your diary; do not uninstall the app to install it.")
        dev.dietapp.ui.update.UpdateProblem.Permission -> t("Установка из FatCodex пока не разрешена. Нажмите «Установить» и разрешите её в настройках Android.", "Installation from FatCodex is not allowed yet. Tap Install and allow it in Android settings.")
        dev.dietapp.ui.update.UpdateProblem.Installer -> t("Не удалось открыть установщик Android. Скачанный файл сохранён, попробуйте ещё раз.", "Could not open the Android installer. The download is saved; try again.")
    }

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

    /** The line under a message while the agent works on it: what it does now, in two or three words. */
    fun agentStep(status: dev.dietapp.data.domain.AgentStatus): String = when (status.step) {
        dev.dietapp.data.domain.AgentStep.Reading -> t("читаю", "reading")
        dev.dietapp.data.domain.AgentStep.Looking -> t("смотрю фото", "looking at the photo")
        dev.dietapp.data.domain.AgentStep.Thinking -> t("думаю", "thinking")
        dev.dietapp.data.domain.AgentStep.SearchBase -> t("ищу в базе", "searching the base")
        dev.dietapp.data.domain.AgentStep.SearchWeb -> t("ищу в интернете", "searching the web")
        dev.dietapp.data.domain.AgentStep.ReadPage -> status.detail?.let { t("читаю $it", "reading $it") } ?: t("читаю страницу", "reading a page")
        dev.dietapp.data.domain.AgentStep.Proposing -> t("готовлю предложение", "preparing a proposal")
        dev.dietapp.data.domain.AgentStep.Adding -> t("добавляю в базу", "adding to the base")
        dev.dietapp.data.domain.AgentStep.Removing -> t("убираю из базы", "removing from the base")
        dev.dietapp.data.domain.AgentStep.Replying -> t("отвечаю", "replying")
        dev.dietapp.data.domain.AgentStep.Recording -> t("записываю", "recording")
    }
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
    val GALLERY get() = t("галерея", "gallery")
    val GALLERY_FAILED get() = t("Не удалось открыть эту картинку.", "Could not open that picture.")

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
    val SOUND_SECTION get() = t("Звук", "Sound")
    val SOUND get() = t("Звук записи", "Sound on recording")
    val SOUND_ON get() = t("включён", "on")
    val SOUND_OFF get() = t("выключен", "off")
    val SOUND_HINT get() = t(
        "Короткий глухой стук, когда еда записана в дневник окончательно, а не когда агент только спросил или ждёт «записать». " +
            "Громкость как у уведомлений: молчит в беззвучном режиме и в «Не беспокоить».",
        "A short, dull knock when a food is in the diary for good, not when the agent has only asked or waits for \"record\". " +
            "It follows the notification volume: silent in silent mode and Do Not Disturb.",
    )
    val LOCK_SECTION get() = t("Защита", "Protection")
    val LOCK get() = t("Блокировка", "Lock")
    val LOCK_ON get() = t("включена", "on")
    val LOCK_OFF get() = t("выключена", "off")
    val LOCK_HINT get() = t(
        "Приложение открывается по отпечатку, лицу или PIN телефона: при каждом запуске и после минуты в фоне. Содержимое прячется " +
            "в списке недавних. Проверку делает система, приложение не видит ни отпечаток, ни PIN.",
        "The app opens with the phone's fingerprint, face or PIN: on every start and after a minute in the background. Its contents " +
            "are hidden in the recent apps. The system does the check; the app never sees a fingerprint or a PIN.",
    )
    val LOCK_NOT_SET get() = t(
        "На телефоне не настроена блокировка экрана. Добавь отпечаток, лицо или PIN в настройках телефона, и она заработает здесь.",
        "This phone has no screen lock set. Add a fingerprint, face or PIN in the phone's settings and it will work here.",
    )
    val LOCK_BROKEN get() = t(
        "Блокировка экрана телефона снята, поэтому приложение открывается без проверки. Нажми, чтобы выключить блокировку здесь.",
        "The phone's screen lock was removed, so the app opens without a check. Tap to turn the lock off here.",
    )
    val LOCK_UNAVAILABLE get() = t("Этот телефон не умеет проверять отпечаток или PIN.", "This phone cannot check a fingerprint or a PIN.")
    val LOCKED get() = t("Закрыто", "Locked")
    val UNLOCK get() = t("Открыть", "Open")
    val LOCK_TITLE_OPEN get() = t("Открыть FatCodex", "Open FatCodex")
    val LOCK_TITLE_ON get() = t("Включить блокировку", "Turn the lock on")
    val LOCK_TITLE_OFF get() = t("Выключить блокировку", "Turn the lock off")
    val FONT get() = t("Шрифт", "Font")
    val FONT_INTER get() = "Inter"
    val FONT_SYSTEM get() = t("Системный", "System")
    val FONT_PICK get() = t("Выбрать файл…", "Choose a file…")
    val FONT_HINT get() = t(
        "Свой шрифт берётся из файла .ttf или .otf на телефоне, например Verdana Pro. Платные шрифты в приложение не вложены: " +
            "файл копируется в память приложения и никуда не отправляется.",
        "Your own font comes from a .ttf or .otf file on the phone, Verdana Pro for example. Licensed fonts are not bundled: " +
            "the file is copied into the app's own storage and never leaves the phone.",
    )
    val FONT_FAILED get() = t("Не удалось прочитать шрифт из этого файла.", "That file could not be read as a font.")
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
    val HISTORY_NOTHING get() = t("нет записей", "nothing recorded")
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
    val QUESTION_HINT get() = t("Ответь сообщением", "Answer with a message")

    // an entry opened for editing
    val ENTRY_PER100 get() = t("на 100 г", "per 100 g")
    val ENTRY_UNKNOWN get() = t("КБЖУ неизвестны: такого продукта нет в базе.", "Values unknown: this food is not in the base.")
    val ENTRY_NAME get() = t("название", "name")
    val ENTRY_GRAMS get() = t("граммы", "grams")
    fun deleted(name: String) = t("Удалено: $name", "Deleted: $name")
    val UNDO get() = t("вернуть", "undo")

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
        dev.dietapp.data.export.ExportFormat.CsvWatch -> t("CSV: часы (.csv)", "CSV: watch (.csv)")
        dev.dietapp.data.export.ExportFormat.Markdown -> "Markdown (.md)"
        dev.dietapp.data.export.ExportFormat.Json -> "JSON (.json)"
        dev.dietapp.data.export.ExportFormat.CsvFood -> t("CSV: еда (.csv)", "CSV: food (.csv)")
        dev.dietapp.data.export.ExportFormat.CsvWeight -> t("CSV: вес (.csv)", "CSV: weight (.csv)")
    }

    fun exportFormatHint(f: dev.dietapp.data.export.ExportFormat): String = when (f) {
        dev.dietapp.data.export.ExportFormat.CsvWatch -> t("Сон, шаги и тренировки по дням: для таблиц.", "Sleep, steps and workouts by day: for spreadsheets.")
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

    // a dish and its ingredients
    val PART_DONE get() = t("готово", "done")
    fun dishOf(parts: List<dev.dietapp.data.domain.DishPart>): String {
        val names = parts.take(4).joinToString(", ") { it.name } + if (parts.size > 4) "…" else ""
        return t("из ${parts.size} ингредиентов: $names", "of ${parts.size} ingredients: $names")
    }
    val FOODS_PARTS get() = t("Состав (на порцию)", "Ingredients (per serving)")
    val FOODS_PARTS_HINT get() = t(
        "Цифры выше — сумма ингредиентов. Если поправить их руками, блюдо станет обычным продуктом.",
        "The numbers above are the ingredients' sum. If you change them by hand, the dish becomes a plain food.",
    )

    // the watch
    val WATCH get() = t("Часы", "Watch")
    /** The page and its row in the settings: the watch, smart scales and whatever else writes to Health Connect. */
    val DEVICES get() = t("Часы и устройства", "Watch & accessories")
    val WATCH_ABOUT get() = t(
        "Сон, шаги, тренировки и вес с умных весов подтягиваются из Health Connect сами одной кнопкой, когда ты открываешь приложение. Часы пишут туда через своё приложение (Samsung Health, Google Health, Pixel Watch и другие). Приложение только читает и только то, что ты разрешил. Данные остаются на телефоне и уходят только в твой экспорт.",
        "Sleep, steps, workouts and the weight from smart scales are read from Health Connect by themselves, with one button, when you open the app. The watch writes there through its own app (Samsung Health, Google Health, the Pixel Watch app and others). The app only reads, and only what you allow. The data stays on the phone and leaves it only in your export.",
    )
    val WATCH_WHAT get() = t(
        "Что читается: сон прошлой ночи, шаги, тренировки и утреннее взвешивание с весов (то, что ты записал сам, главнее). " +
            "Сожжённые калории не читаются: то, что часы пишут в Health Connect, неверно. Готовые «индексы самочувствия» часов Health Connect не отдаёт.",
        "What is read: last night's sleep, steps, workouts and the morning weigh-in of smart scales (one you wrote yourself wins). " +
            "Calories burned are not read: what watches put into Health Connect is not right. Health Connect does not hand over a watch's own wellbeing scores.",
    )
    val WATCH_CONNECT get() = t("подключить", "connect")
    val WATCH_ALLOW get() = t("разрешить", "allow")
    val WATCH_PARTIAL get() = t(
        "Подключено, но разрешено не всё: часть данных (например, вес) не читается. Разреши, и она подтянется.",
        "Connected, but not everything is allowed: some data (the weight, say) is not read. Allow it and it comes in.",
    )
    val WATCH_DISCONNECT get() = t("отключить", "disconnect")
    val WATCH_INSTALL get() = t("установить Health Connect", "install Health Connect")
    val WATCH_UPDATE get() = t("обновить Health Connect", "update Health Connect")
    val WATCH_ON get() = t("Подключено", "Connected")
    val WATCH_OFF get() = t("Не подключено", "Not connected")
    val WATCH_KEPT get() = t("Уже записанное остаётся в дневнике.", "What has been recorded stays in the diary.")
    val WATCH_NOT_INSTALLED get() = t("На этом телефоне нет Health Connect.", "This phone has no Health Connect.")
    val WATCH_UPDATE_NEEDED get() = t("Health Connect нужно обновить.", "Health Connect needs an update.")
    val WATCH_NO_PERMISSION get() = t(
        "Разрешений нет. Включи их в Health Connect → Доступ приложений → FatCodex.",
        "No permissions. Turn them on in Health Connect → App permissions → FatCodex.",
    )
    /** "08:15": the time of day a food was recorded, on the phone's clock. */
    fun clock(at: java.time.Instant): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(at)

    fun watchRead(at: java.time.Instant): String = t("Прочитано ", "Read ") +
        java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", dev.dietapp.data.domain.Lang.current.locale)
            .withZone(java.time.ZoneId.systemDefault()).format(at)

    // what the watch said about a day, in the summary
    val FACT_SLEEP get() = t("Сон", "Sleep")
    val FACT_STEPS get() = t("Шаги", "Steps")
    val MIN get() = t("мин", "min")

    /** "7 ч 20 м" */
    fun sleepText(minutes: Int): String = t("${minutes / 60} ч ${minutes % 60} м", "${minutes / 60} h ${minutes % 60} m")

    /** "Бег", or "Тренировка ×4" when the day had several sessions of that kind. */
    fun workoutName(kind: dev.dietapp.data.domain.WorkoutKind, sessions: Int = 1): String =
        workoutKindName(kind) + if (sessions > 1) " ×$sessions" else ""

    private fun workoutKindName(kind: dev.dietapp.data.domain.WorkoutKind): String = when (kind) {
        dev.dietapp.data.domain.WorkoutKind.Running -> t("Бег", "Run")
        dev.dietapp.data.domain.WorkoutKind.Walking -> t("Ходьба", "Walk")
        dev.dietapp.data.domain.WorkoutKind.Cycling -> t("Велосипед", "Ride")
        dev.dietapp.data.domain.WorkoutKind.Strength -> t("Силовая", "Strength")
        dev.dietapp.data.domain.WorkoutKind.Swimming -> t("Плавание", "Swim")
        dev.dietapp.data.domain.WorkoutKind.Yoga -> t("Йога", "Yoga")
        dev.dietapp.data.domain.WorkoutKind.Hiking -> t("Поход", "Hike")
        dev.dietapp.data.domain.WorkoutKind.Other -> t("Тренировка", "Workout")
    }

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
