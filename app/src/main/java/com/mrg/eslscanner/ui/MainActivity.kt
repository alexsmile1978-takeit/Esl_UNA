package com.mrg.eslscanner.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mrg.eslscanner.EslScannerApp
import com.mrg.eslscanner.data.ConfigStore
import com.mrg.eslscanner.data.NfcReader
import com.mrg.eslscanner.data.OracleClient
import com.mrg.eslscanner.data.OracleResult
import com.mrg.eslscanner.data.eslCodeFromBarcode
import com.mrg.eslscanner.databinding.ActivityMainBinding
import com.mrg.eslscanner.db.AppDatabase
import com.mrg.eslscanner.db.PendingScan
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: ConfigStore
    private var nfcAdapter: NfcAdapter? = null

    private var productCode: String? = null
    private var eslBarcode: String? = null

    private val scanProductLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            productCode = result.data?.getStringExtra(ScannerActivity.EXTRA_RESULT)
            refreshUi()
        }
    }

    private val scanEslLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val rawValue = result.data?.getStringExtra(ScannerActivity.EXTRA_RESULT)
            val source = result.data?.getStringExtra(ScannerActivity.EXTRA_SOURCE)
            eslBarcode = if (rawValue != null && source == ScannerActivity.SOURCE_CAMERA) {
                // Camera reads the printed barcode, which needs the get_esl_code()
                // transform to match the ID format the tag's NFC chip reports directly.
                val converted = eslCodeFromBarcode(rawValue)
                if (converted == null) {
                    Toast.makeText(
                        this,
                        "Не удалось преобразовать штрих-код в ESL ID, использую как есть",
                        Toast.LENGTH_LONG
                    ).show()
                }
                converted ?: rawValue
            } else {
                rawValue
            }
            refreshUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = ConfigStore(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        binding.scanProductButton.setOnClickListener {
            scanProductLauncher.launch(
                Intent(this, ScannerActivity::class.java)
                    .putExtra(ScannerActivity.EXTRA_MODE, "PRODUCT")
            )
        }

        binding.scanEslButton.setOnClickListener {
            scanEslLauncher.launch(
                Intent(this, ScannerActivity::class.java)
                    .putExtra(ScannerActivity.EXTRA_MODE, "ESL")
            )
        }

        binding.clearButton.setOnClickListener {
            productCode = null
            eslBarcode = null
            refreshUi()
        }

        binding.sendButton.setOnClickListener { sendCurrentPair() }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.pendingButton.setOnClickListener {
            startActivity(Intent(this, PendingActivity::class.java))
        }

        refreshUi()
        showCrashLogIfAny()
    }

    /** If the previous run crashed, show the saved stack trace so it can be read/copied. */
    private fun showCrashLogIfAny() {
        val prefs = getSharedPreferences(EslScannerApp.PREFS_NAME, Context.MODE_PRIVATE)
        val crash = prefs.getString(EslScannerApp.KEY_LAST_CRASH, null) ?: return
        AlertDialog.Builder(this)
            .setTitle("Приложение упало на прошлом запуске")
            .setMessage(crash)
            .setPositiveButton("Скопировать") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть") { _, _ ->
                prefs.edit().remove(EslScannerApp.KEY_LAST_CRASH).apply()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        checkNfcEnabled()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    /** Lets the ESL tag be tapped directly on this screen, without opening the scanner first. */
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
            // NFC not usable right now; camera scanning still works.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val value = NfcReader.readValue(intent) ?: return
        // NFC already returns the final ESL ID (e.g. "C0-BF-3E-91") directly — no
        // get_esl_code() transform needed here (that's only for camera barcode scans).
        eslBarcode = value
        refreshUi()
        Toast.makeText(this, "Ценник считан по NFC", Toast.LENGTH_SHORT).show()
    }

    /**
     * Android doesn't let apps toggle NFC on/off silently (security restriction
     * since 4.4) — the best an app can do is detect it's off and hand the user
     * straight to the system control for it. Settings.Panel.ACTION_NFC (API 29+)
     * shows a small overlay panel to flip it on without leaving the app; older
     * versions fall back to the full NFC settings screen.
     */
    private fun checkNfcEnabled() {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return // device has no NFC hardware
        if (adapter.isEnabled) return

        AlertDialog.Builder(this)
            .setTitle("Включите NFC")
            .setMessage("NFC выключен. Для сканирования ценников по NFC его нужно включить в настройках телефона.")
            .setPositiveButton("Включить") { _, _ ->
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_NFC)
                } else {
                    Intent(Settings.ACTION_NFC_SETTINGS)
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось открыть настройки NFC", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun refreshUi() {
        binding.productValue.text = productCode ?: "— не отсканирован —"
        binding.eslValue.text = eslBarcode ?: "— не отсканирован —"
        binding.sendButton.isEnabled = productCode != null && eslBarcode != null
    }

    private fun sendCurrentPair() {
        val product = productCode ?: return
        val esl = eslBarcode ?: return
        val settings = configStore.load()

        if (!settings.isComplete()) {
            Toast.makeText(this, "Сначала заполните настройки подключения", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        binding.sendButton.isEnabled = false
        binding.statusText.text = "Отправка..."

        lifecycleScope.launch {
            val db = AppDatabase.get(this@MainActivity)
            val pendingId = db.pendingScanDao().insert(
                PendingScan(
                    productCode = product,
                    eslBarcode = esl,
                    storeCode = settings.storeCode
                )
            )

            val client = OracleClient(settings)
            when (val result = client.sendScan(product, esl)) {
                is OracleResult.Success -> {
                    binding.statusText.text = result.message
                    val row = db.pendingScanDao().getRecent().find { it.id == pendingId }
                    if (row != null) db.pendingScanDao().update(row.copy(status = "SENT"))
                    productCode = null
                    eslBarcode = null
                    refreshUi()
                }
                is OracleResult.Failure -> {
                    binding.statusText.text = "Ошибка: ${result.error}\nЗапись сохранена локально, повторите позже."
                    val row = db.pendingScanDao().getRecent().find { it.id == pendingId }
                    if (row != null) db.pendingScanDao().update(row.copy(status = "FAILED", lastError = result.error))
                }
            }
            binding.sendButton.isEnabled = productCode != null && eslBarcode != null
        }
    }
}
