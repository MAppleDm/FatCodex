package dev.dietapp

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import dev.dietapp.ui.lock.AppLock
import dev.dietapp.ui.lock.Biometrics
import dev.dietapp.ui.lock.LockScreen
import dev.dietapp.ui.lock.LockSupport
import dev.dietapp.ui.lock.PromptResult
import dev.dietapp.ui.lock.LockViewModel
import dev.dietapp.ui.sound.LocalAddedSound
import dev.dietapp.ui.sound.SoundPoolPlayer
import dev.dietapp.ui.sound.SoundViewModel
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.launch
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
import dev.dietapp.ui.body.BodyViewModel
import dev.dietapp.ui.export.ExportViewModel
import dev.dietapp.ui.font.FontViewModel
import dev.dietapp.ui.foods.FoodsViewModel
import dev.dietapp.ui.journal.JournalViewModel
import dev.dietapp.ui.login.LoginViewModel
import dev.dietapp.ui.main.MainViewModel
import dev.dietapp.ui.settings.SettingsViewModel
import dev.dietapp.ui.watch.WatchViewModel
import dev.dietapp.ui.update.UpdateViewModel
import dev.dietapp.ui.theme.ThemeStore

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val main: MainViewModel by viewModels()
    private val login: LoginViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()
    private val foods: FoodsViewModel by viewModels()
    private val journal: JournalViewModel by viewModels()
    private val export: ExportViewModel by viewModels()
    private val body: BodyViewModel by viewModels()
    private val watch: WatchViewModel by viewModels()
    private val fonts: FontViewModel by viewModels()
    private val lockModel: LockViewModel by viewModels()
    private val soundModel: SoundViewModel by viewModels()
    private val updates: UpdateViewModel by viewModels()

    @Inject lateinit var modes: ModeStore
    @Inject lateinit var lock: AppLock
    @Inject lateinit var sound: SoundPoolPlayer
    @Inject lateinit var themes: ThemeStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sound.warmUp()
        // Appearance is read before the first frame. Language changes rebuild screens while keeping their view models.
        setContent {
            val language by modes.language.collectAsState()
            CoreTexts.english = language == Language.En
            val font by fonts.choice.collectAsState()
            val appearance by themes.choice.collectAsState()
            val locked by lock.locked.collectAsState()
            // the system's prompt comes up by itself whenever the app is closed
            LaunchedEffect(locked) { if (locked) askToUnlock() }
            key(language) {
                CompositionLocalProvider(LocalAddedSound provides sound) {
                    AppTheme(font = fonts.family(font), appearance = appearance) {
                        val dark = AppTheme.colors.isDark
                        SideEffect {
                            WindowCompat.getInsetsController(window, window.decorView).apply {
                                isAppearanceLightStatusBars = !dark
                                isAppearanceLightNavigationBars = !dark
                            }
                        }
                        if (locked) LockScreen(onOpen = ::askToUnlock)
                        else AppRoot(
                            main, login, settings, foods, journal, export, body, watch, fonts, lockModel, soundModel, updates,
                            appearance = appearance,
                            onAppearance = themes::use,
                        )
                    }
                }
            }
        }
        // with the lock on the app's picture is not kept in the list of recent apps
        lifecycleScope.launch { lock.enabled.collect { hideInRecents(it) } }
        if (savedInstanceState == null) {
            takeSharedText(intent)
            takeHealthIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedText(intent)
        takeHealthIntent(intent)
    }

    /** Health Connect's "why does this app want my data" link, and Android 14's permission usage screen: both land on "Часы". */
    private fun takeHealthIntent(intent: Intent?) {
        when (intent?.action) {
            "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", "android.intent.action.VIEW_PERMISSION_USAGE" -> main.onOpenWatch()
        }
    }

    /** Text shared from another app ("Поделиться" → FatCodex) lands in the input, ready to send or edit. */
    private fun takeSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(main::onSharedText)
    }

    /**
     * The system's prompt; it opens the app when passed. A phone that cannot check anyone any more (its screen lock was removed
     * after the lock was turned on) opens it at once: a lock that cannot be opened would keep the person out of their own diary,
     * and whoever could remove the phone's lock could see everything on it anyway.
     */
    private fun askToUnlock() {
        if (Biometrics.support(this) != LockSupport.Ready) {
            lock.unlock()
            return
        }
        Biometrics.prompt(this, Texts.LOCK_TITLE_OPEN) { result -> if (result != PromptResult.Closed) lock.unlock() }
    }

    /** Android 13 can hide just the picture in the list of recent apps; before that only a secure window does, which also stops screenshots. */
    private fun hideInRecents(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStop() {
        super.onStop()
        lock.onBackground()
    }

    override fun onStart() {
        super.onStart()
        lock.onForeground()
        main.onForeground()
        watch.onForeground()
    }
}
