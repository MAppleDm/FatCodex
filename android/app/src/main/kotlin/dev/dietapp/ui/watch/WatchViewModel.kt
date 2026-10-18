package dev.dietapp.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.dietapp.data.repo.WatchRepository
import dev.dietapp.data.repo.WatchState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The "Часы" page and the quiet reading of the watch whenever the app comes to the front. */
@HiltViewModel
class WatchViewModel @Inject constructor(private val watch: WatchRepository) : ViewModel() {
    val state: StateFlow<WatchState> = watch.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchState())

    /** What the permission dialog asks for. */
    val permissions: Set<String> get() = watch.permissions

    /** The page was opened: look at the phone again (is Health Connect there, what is allowed). */
    fun onOpened() {
        viewModelScope.launch { watch.refreshPhone() }
    }

    /** The person answered the permission dialog. */
    fun onGranted(granted: Set<String>) {
        viewModelScope.launch { watch.connect(granted) }
    }

    fun onDisconnect() = watch.disconnect()

    /** The app came to the front: the last days are read, if the watch is connected. Never asks for anything. */
    fun onForeground() {
        viewModelScope.launch { watch.refresh() }
    }
}
