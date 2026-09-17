package com.mrg.eslscanner.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mrg.eslscanner.EslScannerApp
import com.mrg.eslscanner.data.ConfigStore
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
