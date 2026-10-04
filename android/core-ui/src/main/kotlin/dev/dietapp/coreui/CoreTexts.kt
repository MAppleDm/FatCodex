package dev.dietapp.coreui

/** The few words the shared components write themselves. The app switches [english] with the user's language. */
object CoreTexts {
    @Volatile var english: Boolean = false

    private fun t(ru: String, en: String) = if (english) en else ru

    val kcal get() = t("ккал", "kcal")
    val p get() = t("Б", "P")
    val f get() = t("Ж", "F")
    val c get() = t("У", "C")
    val grams get() = t("г", "g")
    val name get() = t("название", "name")
    val gramsHint get() = t("граммы", "grams")
    val delete get() = t("удалить", "delete")
    val send get() = t("Отправить", "Send")
    val mic get() = t("Микрофон", "Microphone")
    val camera get() = t("Камера", "Camera")
}
