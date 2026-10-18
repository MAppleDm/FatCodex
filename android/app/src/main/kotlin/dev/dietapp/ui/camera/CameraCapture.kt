package dev.dietapp.ui.camera

import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.dietapp.Texts
import dev.dietapp.coreui.CloseIcon
import dev.dietapp.coreui.IconAction
import dev.dietapp.coreui.ShutterButton
import dev.dietapp.coreui.TextAction
import dev.dietapp.media.GalleryImage
import dev.dietapp.media.ImageCompressor
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen viewfinder with one shutter button, and "галерея" beside it for a picture that is already on the phone (a
 * receipt, a menu). Either way the picture is shrunk to 1600 px / JPEG 80% before it leaves this file.
 */
@Composable
fun CameraCapture(
    onPhoto: (ByteArray) -> Unit,
    onFailed: () -> Unit,
    onGalleryFailed: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // no point in capturing 12 megapixels that we immediately shrink to ~2.5 MP
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .build()
    }
    val worker = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                try {
                    provider = future.get().also {
                        val preview = Preview.Builder().build().also { p -> p.surfaceProvider = previewView.surfaceProvider }
                        it.unbindAll()
                        it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    }
                } catch (e: Exception) {
                    onFailed()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            provider?.unbindAll()
            worker.shutdown()
        }
    }

    fun shoot() {
        imageCapture.takePicture(
            worker,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = try {
                        ImageCompressor.compress(image.toBitmap(), image.imageInfo.rotationDegrees)
                    } catch (e: Exception) {
                        null
                    } finally {
                        image.close()
                    }
                    main.post { if (bytes != null) onPhoto(bytes) else onFailed() }
                }

                override fun onError(exception: ImageCaptureException) {
                    main.post { onFailed() }
                }
            },
        )
    }

    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // nothing chosen: the viewfinder stays
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { GalleryImage.read(context.contentResolver, uri) }
            if (bytes != null) onPhoto(bytes) else onGalleryFailed()
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        IconAction(onClose, Texts.CD_CLOSE, Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart)) {
            CloseIcon(Color.White)
        }
        ShutterButton(::shoot, Texts.CD_SHUTTER, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp))
        TextAction(
            Texts.GALLERY, { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 12.dp, bottom = 54.dp).testTag("gallery"),
            color = Color.White,
        )
    }
}
