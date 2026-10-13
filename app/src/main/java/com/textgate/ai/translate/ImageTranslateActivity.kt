package com.textgate.ai.translate

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import com.textgate.ai.LocaleHelper
import com.textgate.ai.R
import com.textgate.ai.databinding.ActivityImageTranslateBinding
import com.textgate.ai.model.Languages
import com.textgate.ai.model.SupportedLanguage
import com.textgate.ai.network.GeminiClient
import com.textgate.ai.security.AppSettingsStore
import com.textgate.ai.security.SecureApiKeyStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Activity for full-screen photo translation with in-place text overlay.
 * Uses Gemini Vision API to detect text bounding boxes and translate
 * directly onto the image canvas.
 */
class ImageTranslateActivity : Activity() {

    private lateinit var binding: ActivityImageTranslateBinding
    private val apiKeyStore by lazy { SecureApiKeyStore(applicationContext) }
    private val settingsStore by lazy { AppSettingsStore(applicationContext) }
    private var executor: ExecutorService? = Executors.newSingleThreadExecutor()

    private var currentBitmap: Bitmap? = null
    private var currentImageUri: Uri? = null
    private var currentCameraFile: File? = null

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private val sourceLanguageValues: List<SupportedLanguage?> = listOf(null) + Languages.ALL
    private val targetLanguageValues: List<SupportedLanguage> = Languages.ALL

    private var selectedSourceLanguage: SupportedLanguage? = null
    private var selectedTargetLanguage: SupportedLanguage = Languages.ALL[0]

    enum class AutoLaunch { NONE, CAMERA, GALLERY }

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_SOURCE_LANG_CODE = "extra_source_lang_code"
        const val EXTRA_TARGET_LANG_CODE = "extra_target_lang_code"
        const val EXTRA_AUTO_LAUNCH = "extra_auto_launch"

        private const val REQUEST_CODE_CAMERA = 1001
        private const val REQUEST_CODE_GALLERY = 1002
        private const val REQUEST_CODE_CAMERA_PERMISSION = 2001
        private const val MAX_IMAGE_DIMENSION = 1600

        fun intent(
            context: Context,
            imageUri: Uri? = null,
            sourceLanguageCode: String? = null,
            targetLanguageCode: String? = null,
            autoLaunch: AutoLaunch = AutoLaunch.NONE
        ): Intent {
            return Intent(context, ImageTranslateActivity::class.java).apply {
                imageUri?.let { putExtra(EXTRA_IMAGE_URI, it) }
                sourceLanguageCode?.let { putExtra(EXTRA_SOURCE_LANG_CODE, it) }
                targetLanguageCode?.let { putExtra(EXTRA_TARGET_LANG_CODE, it) }
                putExtra(EXTRA_AUTO_LAUNCH, autoLaunch.name)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyOverride(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageTranslateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle edge-to-edge window insets so bottom action bar is not covered
        // by the system navigation bar (3-button or gesture bar).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            applyInsetsAsPadding(binding.rootImageTranslate)
        }

        initLanguagesFromIntent()
        setupLanguageSpinners()
        setupButtons()
        setupOverlayInteractions()
        updateEmptyState()

        textToSpeech = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_IMAGE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(EXTRA_IMAGE_URI)
        }

        val autoLaunchName = intent.getStringExtra(EXTRA_AUTO_LAUNCH)
        val autoLaunch = autoLaunchName?.let { runCatching { AutoLaunch.valueOf(it) }.getOrNull() } ?: AutoLaunch.NONE

        if (initialUri != null) {
            loadImageAndTranslate(initialUri)
        } else {
            when (autoLaunch) {
                AutoLaunch.CAMERA -> checkCameraPermissionAndLaunch()
                AutoLaunch.GALLERY -> launchGalleryPicker()
                AutoLaunch.NONE -> {
                    // Show initial prompt screen with [Aparat] and [Galeria] buttons
                }
            }
        }
    }

    override fun onDestroy() {
        executor?.shutdownNow()
        executor = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun applyInsetsAsPadding(root: View) {
        val basePaddingLeft = root.paddingLeft
        val basePaddingTop = root.paddingTop
        val basePaddingRight = root.paddingRight
        val basePaddingBottom = root.paddingBottom

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bars.bottom
            )
            insets
        }
        root.requestApplyInsets()
    }

    private fun initLanguagesFromIntent() {
        val srcCode = intent.getStringExtra(EXTRA_SOURCE_LANG_CODE)
        selectedSourceLanguage = if (srcCode != null) Languages.byCode(srcCode) else null

        val tgtCode = intent.getStringExtra(EXTRA_TARGET_LANG_CODE)
        selectedTargetLanguage = if (tgtCode != null) {
            Languages.byCode(tgtCode) ?: Languages.ALL[0]
        } else {
            Languages.ALL.find { it.code.equals("pl", ignoreCase = true) } ?: Languages.ALL[0]
        }
    }

    private fun setupLanguageSpinners() {
        val sourceLabels = listOf(getString(R.string.label_language_auto)) +
            Languages.ALL.map { it.nativeName }
        val sourceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sourceLabels)
        sourceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSourceLanguage.adapter = sourceAdapter

        val initialSourceIndex = if (selectedSourceLanguage == null) {
            0
        } else {
            Languages.ALL.indexOfFirst { it.code.equals(selectedSourceLanguage?.code, ignoreCase = true) } + 1
        }
        binding.spinnerSourceLanguage.setSelection(initialSourceIndex.coerceAtLeast(0), false)

        binding.spinnerSourceLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newSource = sourceLanguageValues[position]
                if (newSource != selectedSourceLanguage) {
                    selectedSourceLanguage = newSource
                    retranslateCurrentBitmap()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val targetLabels = Languages.ALL.map { it.nativeName }
        val targetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, targetLabels)
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerTargetLanguage.adapter = targetAdapter

        val initialTargetIndex = Languages.ALL.indexOfFirst {
            it.code.equals(selectedTargetLanguage.code, ignoreCase = true)
        }.coerceAtLeast(0)
        binding.spinnerTargetLanguage.setSelection(initialTargetIndex, false)

        binding.spinnerTargetLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newTarget = targetLanguageValues[position]
                if (newTarget != selectedTargetLanguage) {
                    selectedTargetLanguage = newTarget
                    retranslateCurrentBitmap()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.buttonSwapLanguages.setOnClickListener {
            val currentSource = selectedSourceLanguage
            if (currentSource == null) {
                Toast.makeText(this, R.string.translate_swap_needs_source, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentTarget = selectedTargetLanguage
            selectedSourceLanguage = currentTarget
            selectedTargetLanguage = currentSource

            val newSrcPos = Languages.ALL.indexOfFirst { it.code.equals(currentTarget.code, ignoreCase = true) } + 1
            val newTgtPos = Languages.ALL.indexOfFirst { it.code.equals(currentSource.code, ignoreCase = true) }
            binding.spinnerSourceLanguage.setSelection(newSrcPos, false)
            binding.spinnerTargetLanguage.setSelection(newTgtPos, false)
            retranslateCurrentBitmap()
        }
    }

    private fun setupButtons() {
        binding.buttonBack.setOnClickListener {
            finish()
        }

        binding.buttonToggleOverlay.setOnClickListener {
            val newState = !binding.imageOverlayView.showOverlay
            binding.imageOverlayView.showOverlay = newState
            binding.buttonToggleOverlay.setImageResource(
                if (newState) R.drawable.ic_visibility else R.drawable.ic_visibility_off
            )
        }

        binding.buttonCamera.setOnClickListener {
            checkCameraPermissionAndLaunch()
        }

        binding.buttonGallery.setOnClickListener {
            launchGalleryPicker()
        }

        binding.buttonPromptCamera.setOnClickListener {
            checkCameraPermissionAndLaunch()
        }

        binding.buttonPromptGallery.setOnClickListener {
            launchGalleryPicker()
        }

        binding.buttonCopyAll.setOnClickListener {
            val allBlocks = binding.imageOverlayView.blocks
            if (allBlocks.isEmpty()) {
                Toast.makeText(this, R.string.image_translate_no_text, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val text = allBlocks.joinToString("\n") { it.translatedText }
            copyToClipboard(text, getString(R.string.translate_copied))
        }

        binding.buttonCloseBlockCard.setOnClickListener {
            binding.cardSelectedBlock.visibility = View.GONE
            binding.imageOverlayView.selectedBlock = null
        }
    }

    private fun setupOverlayInteractions() {
        binding.imageOverlayView.onBlockClickListener = { block ->
            if (block != null) {
                binding.cardSelectedBlock.visibility = View.VISIBLE
                binding.textBlockOriginal.text = getString(R.string.image_translate_original_prefix, block.originalText)
                binding.textBlockTranslated.text = block.translatedText

                binding.buttonCopyBlock.setOnClickListener {
                    copyToClipboard(block.translatedText, getString(R.string.translate_copied))
                }

                binding.buttonSpeakBlock.setOnClickListener {
                    speakText(block.translatedText, selectedTargetLanguage.code)
                }
            } else {
                binding.cardSelectedBlock.visibility = View.GONE
            }
        }
    }

    private fun updateEmptyState() {
        val hasImage = currentBitmap != null
        binding.layoutEmptyPrompt.visibility = if (hasImage) View.GONE else View.VISIBLE
        binding.imageOverlayView.visibility = if (hasImage) View.VISIBLE else View.GONE
    }

    private fun loadImageAndTranslate(uri: Uri) {
        currentImageUri = uri
        binding.layoutNoText.visibility = View.GONE
        binding.cardSelectedBlock.visibility = View.GONE
        binding.imageOverlayView.blocks = emptyList()

        try {
            val bitmap = loadOptimizedBitmap(uri)
            if (bitmap == null) {
                Toast.makeText(this, R.string.image_translate_load_failed, Toast.LENGTH_SHORT).show()
                updateEmptyState()
                return
            }
            currentBitmap = bitmap
            binding.imageOverlayView.bitmap = bitmap
            updateEmptyState()
            translateBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("TextGate", "Failed to load image", e)
            Toast.makeText(this, R.string.image_translate_load_failed, Toast.LENGTH_SHORT).show()
            updateEmptyState()
        }
    }

    private fun retranslateCurrentBitmap() {
        val bitmap = currentBitmap ?: return
        translateBitmap(bitmap)
    }

    private fun translateBitmap(bitmap: Bitmap) {
        val apiKey = apiKeyStore.getActiveKeyPlaintext()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(this, R.string.setup_key_missing, Toast.LENGTH_LONG).show()
            return
        }

        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutNoText.visibility = View.GONE
        binding.cardSelectedBlock.visibility = View.GONE

        // Prepare Base64 JPEG
        val base64Jpeg = bitmapToBase64Jpeg(bitmap)
        val targetName = selectedTargetLanguage.nativeName
        val requestedModel = settingsStore.selectedModel.ifBlank { "gemini-2.5-flash" }

        executor?.execute {
            var result = GeminiClient.translateImageBlocking(
                apiKey = apiKey,
                model = requestedModel,
                imageBase64 = base64Jpeg,
                targetLanguageName = targetName
            )

            if (result is GeminiClient.ImageTranslationResult.Failure.InvalidModel ||
                (result is GeminiClient.ImageTranslationResult.Failure.HttpError && result.code == 404)
            ) {
                result = GeminiClient.translateImageBlocking(
                    apiKey = apiKey,
                    model = "gemini-2.5-flash",
                    imageBase64 = base64Jpeg,
                    targetLanguageName = targetName
                )
            }

            runOnUiThread {
                binding.layoutLoading.visibility = View.GONE
                when (result) {
                    is GeminiClient.ImageTranslationResult.Success -> {
                        binding.imageOverlayView.blocks = result.blocks
                        binding.layoutNoText.visibility = View.GONE
                    }
                    is GeminiClient.ImageTranslationResult.Failure.NoTextDetected -> {
                        binding.imageOverlayView.blocks = emptyList()
                        binding.layoutNoText.visibility = View.VISIBLE
                    }
                    is GeminiClient.ImageTranslationResult.Failure.QuotaExceeded -> {
                        Toast.makeText(this, R.string.live_error_quota, Toast.LENGTH_LONG).show()
                    }
                    is GeminiClient.ImageTranslationResult.Failure.NetworkError -> {
                        Toast.makeText(this, R.string.image_translate_network_error, Toast.LENGTH_LONG).show()
                    }
                    is GeminiClient.ImageTranslationResult.Failure.Timeout -> {
                        Toast.makeText(this, R.string.image_translate_timeout, Toast.LENGTH_LONG).show()
                    }
                    is GeminiClient.ImageTranslationResult.Failure.HttpError -> {
                        val msg = result.detail ?: "HTTP ${result.code}"
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(this, R.string.image_translate_generic_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun loadOptimizedBitmap(uri: Uri): Bitmap? {
        val context = applicationContext
        val tempFile = File(context.cacheDir, "temp_img_decode_${System.currentTimeMillis()}.jpg")
        try {
            // Copy URI content stream to a local cache file ONCE to avoid ephemeral permission or re-read errors.
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            if (!tempFile.exists() || tempFile.length() == 0L) return null

            // 1. Decode bounds
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOptions)

            val outWidth = boundsOptions.outWidth
            val outHeight = boundsOptions.outHeight
            if (outWidth <= 0 || outHeight <= 0) return null

            val maxDim = max(outWidth, outHeight)
            var sampleSize = 1
            while (maxDim / (sampleSize * 2) >= MAX_IMAGE_DIMENSION) {
                sampleSize *= 2
            }

            // 2. Decode sampled bitmap
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions) ?: return null

            // 3. Read EXIF rotation from file path
            val rotation = try {
                val exif = ExifInterface(tempFile.absolutePath)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) {
                0f
            }

            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated != decoded) {
                    decoded.recycle()
                }
                return rotated
            }

            return decoded
        } catch (e: Exception) {
            Log.e("TextGate", "Failed to load optimized bitmap", e)
            return null
        } finally {
            tempFile.delete()
        }
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun checkCameraPermissionAndLaunch() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCameraCapture()
            } else {
                Toast.makeText(this, R.string.image_translate_camera_permission_needed, Toast.LENGTH_SHORT).show()
                updateEmptyState()
            }
        }
    }

    private fun launchCameraCapture() {
        try {
            val photoFile = File(cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            currentCameraFile = photoFile
            val photoUri = LocalFileProvider.getUriForFile(this, photoFile)

            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(cameraIntent, REQUEST_CODE_CAMERA)
        } catch (e: Exception) {
            Log.e("TextGate", "Failed to launch camera", e)
            Toast.makeText(this, R.string.image_translate_camera_error, Toast.LENGTH_SHORT).show()
            updateEmptyState()
        }
    }

    private fun launchGalleryPicker() {
        val pickerIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
            }
        } else {
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
            }
        }
        try {
            startActivityForResult(pickerIntent, REQUEST_CODE_GALLERY)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            try {
                startActivityForResult(fallback, REQUEST_CODE_GALLERY)
            } catch (e: Exception) {
                Log.e("TextGate", "Failed to launch gallery picker", e)
                Toast.makeText(this, R.string.image_translate_load_failed, Toast.LENGTH_SHORT).show()
                updateEmptyState()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            updateEmptyState()
            return
        }

        when (requestCode) {
            REQUEST_CODE_CAMERA -> {
                val file = currentCameraFile
                if (file != null && file.exists() && file.length() > 0) {
                    val uri = Uri.fromFile(file)
                    loadImageAndTranslate(uri)
                } else if (data?.data != null) {
                    loadImageAndTranslate(data.data!!)
                } else {
                    updateEmptyState()
                }
            }
            REQUEST_CODE_GALLERY -> {
                val selectedUri = data?.data
                if (selectedUri != null) {
                    loadImageAndTranslate(selectedUri)
                } else {
                    updateEmptyState()
                }
            }
        }
    }

    private fun copyToClipboard(text: String, toastMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Translation", text))
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
    }

    private fun speakText(text: String, languageCode: String) {
        if (!ttsReady || textToSpeech == null) return
        val locale = Locale.forLanguageTag(languageCode)
        textToSpeech?.language = locale
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "image_tts")
    }
}
