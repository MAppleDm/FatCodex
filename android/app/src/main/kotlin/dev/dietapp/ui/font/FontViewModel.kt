package dev.dietapp.ui.font

import android.net.Uri
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The font of the app and the "Шрифт" part of the about page. */
@HiltViewModel
class FontViewModel @Inject constructor(private val store: FontStore) : ViewModel() {
    val choice: StateFlow<FontChoice> = store.choice

    private val _failed = MutableStateFlow(false)

    /** The last file the person picked was not a font that could be read. */
    val failed: StateFlow<Boolean> = _failed

    fun family(choice: FontChoice): FontFamily = store.family(choice)

    fun onKind(kind: FontKind) {
        _failed.value = false
        store.use(kind)
    }

    /** The person picked a file in the file picker. */
    fun onFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { _failed.value = !store.importFile(uri) }
    }
}
