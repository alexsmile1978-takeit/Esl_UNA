package com.mrg.eslscanner.ui

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.mrg.eslscanner.databinding.ActivityScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Generic scanner screen. Launch with EXTRA_MODE = "PRODUCT" or "ESL".
 * Reads either a camera barcode (ML Kit, any format) or an NFC tag (most
 * Hanshow ESL price tags are NFC Forum Type 4 with an NDEF text record) —
 * whichever comes first wins. Returns the scanned value via EXTRA_RESULT,
 * and which method produced it via EXTRA_SOURCE (SOURCE_CAMERA / SOURCE_NFC
 * / SOURCE_MANUAL) — the caller needs this because a camera-scanned ESL
 * barcode still needs the get_esl_code() transform (see EslCode.kt) while
 * an NFC read already returns the final ID directly.
 */
class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT = "result"
        const val EXTRA_SOURCE = "source"
        const val SOURCE_CAMERA = "CAMERA"
        const val SOURCE_NFC = "NFC"
        const val SOURCE_MANUAL = "MANUAL"
        private const val REQUEST_CAMERA = 100
    }

    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var alreadyHandled = false
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: "PRODUCT"
        binding.scanLabel.text = if (mode == "PRODUCT")
            "Наведите камеру на штрих-код товара"
        else
            "Наведите камеру на ценник (ESL) или поднесите его к телефону (NFC)"

        binding.manualEntryButton.setOnClickListener {
            val value = binding.manualEntryField.text?.toString()?.trim()
            if (!value.isNullOrEmpty()) {
                returnResult(value, SOURCE_MANUAL)
            } else {
                Toast.makeText(this, "Введите код", Toast.LENGTH_SHORT).show()
            }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, "Без доступа к камере можно только ввести код вручную", Toast.LENGTH_LONG).show()
        }
    }

    // --- NFC ---------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)

        try {
            adapter.enableForegroundDispatch(
                this, pendingIntent, arrayOf(ndefFilter, tagFilter, techFilter), null
            )
        } catch (ignored: Exception) {
            // NFC not usable right now (e.g. screen state); camera/manual entry still work.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (alreadyHandled) return

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val value = readNfcValue(intent, tag) ?: return

        alreadyHandled = true
        returnResult(value, SOURCE_NFC)
    }

    /** Tries the NDEF text record first (what Hanshow ESL tags carry), falls back to the tag's serial number. */
    private fun readNfcValue(intent: Intent, tag: Tag?): String? {
        val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        val ndefMessage = rawMessages?.firstOrNull() as? NdefMessage

        val fromNdef = ndefMessage?.records?.firstNotNullOfOrNull { parseNdefTextRecord(it) }
        if (fromNdef != null) return fromNdef

        // No NDEF payload readable (or tag not formatted as expected) — fall back to reading
        // the tag's own NDEF data directly, then finally its raw serial number as a last resort.
        tag?.let { t ->
            try {
                Ndef.get(t)?.let { ndef ->
                    ndef.connect()
                    val message = ndef.ndefMessage
                    ndef.close()
                    message?.records?.firstNotNullOfOrNull { parseNdefTextRecord(it) }?.let { return it }
                }
            } catch (ignored: Exception) {
                // fall through to serial number
            }
            return t.id.joinToString(":") { String.format("%02X", it) }
        }
        return null
    }

    /** Decodes a Well-Known TNF "T" (text) NDEF record per the NFC Forum spec. */
    private fun parseNdefTextRecord(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            return null
        }
        return try {
            val payload = record.payload
            val isUtf16 = (payload[0].toInt() and 0x80) != 0
            val languageCodeLength = payload[0].toInt() and 0x3F
            val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
            String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1, charset).trim()
        } catch (ignored: Exception) {
            null
        }
    }

    // --- Camera / ML Kit -----------------------------------------------------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_ALL_FORMATS
                )
                .build()
            val scanner = BarcodeScanning.getClient(options)

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxy(scanner, imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка камеры: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImageProxy(
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        imageProxy: ImageProxy
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !alreadyHandled) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val value = barcodes.firstOrNull()?.rawValue
                    if (value != null && !alreadyHandled) {
                        alreadyHandled = true
                        runOnUiThread { returnResult(value, SOURCE_CAMERA) }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    private fun returnResult(value: String, source: String) {
        val data = intent
            .putExtra(EXTRA_RESULT, value)
            .putExtra(EXTRA_SOURCE, source)
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
