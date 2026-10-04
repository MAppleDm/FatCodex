package dev.dietapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.dietapp.coreui.AppTheme
import dev.dietapp.coreui.CoreTexts
import dev.dietapp.data.domain.Language
import dev.dietapp.data.local.ModeStore
import javax.inject.Inject
import dev.dietapp.ui.AppRoot
import dev.dietapp.ui.foods.FoodsViewModel
import dev.dietapp.ui.journal.JournalViewModel
import dev.dietapp.ui.login.LoginViewModel
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.settings.SettingsViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val main: MainViewModel by viewModels()
    private val login: LoginViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()
    private val foods: FoodsViewModel by viewModels()
    private val journal: JournalViewModel by viewModels()

    @Inject lateinit var modes: ModeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Follows the system light/dark setting; there is no dynamic (wallpaper) colour. A new language rebuilds the
        // screens (the view models and their state stay).
        setContent {
            val language by modes.language.collectAsState()
            CoreTexts.english = language == Language.En
            key(language) { AppTheme { AppRoot(main, login, settings, foods, journal) } }
        }
        if (savedInstanceState == null) takeSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedText(intent)
    }

    /** Text shared from another app ("Поделиться" → FatCodex) lands in the input, ready to send or edit. */
    private fun takeSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(main::onSharedText)
    }

    override fun onStart() {
        super.onStart()
        main.onForeground()
    }
}
