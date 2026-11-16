package ge.sigara.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var rescan: Button
    private lateinit var torch: ImageButton
    private lateinit var permPanel: View
    private var camera: Camera? = null
    private var torchOn = false

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var locked = false
    private var lastCandidate: String? = null
    private var stableCount = 0
    private var lastRun = 0L
    private var cooldownUntil = 0L
    private var lastDialed: String? = null
    private var dialed = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { permPanel.visibility = View.GONE; startCamera() } else permPanel.visibility = View.VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        rescan = findViewById(R.id.rescan)
        torch = findViewById(R.id.torch)
        permPanel = findViewById(R.id.permissionPanel)
        val topBar = findViewById<View>(R.id.topBar)
        val pill = findViewById<View>(R.id.pill)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(topBar.paddingLeft, b.top + 12.dp(), topBar.paddingRight, 0)
            (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = b.bottom + 24.dp()
            pill.requestLayout()
            insets
        }
        torch.setOnClickListener {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                torch.isSelected = torchOn
            }
        }
        rescan.setOnClickListener {
            rescan.visibility = View.GONE
            status.setText(R.string.status_searching)
            lastCandidate = null; stableCount = 0; dialed = false; locked = false
            cooldownUntil = SystemClock.elapsedRealtime() + 1500
        }
        findViewById<Button>(R.id.grant).setOnClickListener {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                permLauncher.launch(Manifest.permission.CAMERA)
            } else {
                // either first ask or "don't ask again": try the dialog, fall back to app settings
                permLauncher.launch(Manifest.permission.CAMERA)
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        if (hasCamera()) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (dialed) { // back from the dialer
            rescan.visibility = View.VISIBLE
            cooldownUntil = SystemClock.elapsedRealtime() + 1500
        }
        if (hasCamera() && camera == null) { permPanel.visibility = View.GONE; startCamera() }
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val selector = ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { analyze(it) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis)
                if (torchOn) camera?.cameraControl?.enableTorch(true)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = proxy.image
        if (media == null || locked || now < cooldownUntil || now - lastRun < 120) { proxy.close(); return }
        lastRun = now
        val rot = proxy.imageInfo.rotationDegrees
        val w = if (rot % 180 == 0) proxy.width else proxy.height
        val h = if (rot % 180 == 0) proxy.height else proxy.width
        recognizer.process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener(ContextCompat.getMainExecutor(this)) { onResult(PhoneExtractor.pick(it, w, h)) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onResult(number: String?) {
        if (locked) return
        if (number == null) {
            lastCandidate = null; stableCount = 0
            status.setText(R.string.status_searching)
            return
        }
        if (number == lastDialed && SystemClock.elapsedRealtime() < cooldownUntil) return
        if (number == lastCandidate) stableCount++ else { lastCandidate = number; stableCount = 1 }
        status.text = getString(R.string.status_found, number)
        if (stableCount >= 2) { // same number on 2 consecutive frames
            locked = true; dialed = true; lastDialed = number
            buzz()
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.no_dialer, Toast.LENGTH_LONG).show()
                rescan.visibility = View.VISIBLE
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        else v.vibrate(60)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close(); analysisExecutor.shutdown()
    }
}
