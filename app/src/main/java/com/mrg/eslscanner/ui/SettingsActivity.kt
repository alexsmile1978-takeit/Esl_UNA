package com.mrg.eslscanner.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mrg.eslscanner.data.ConfigStore
import com.mrg.eslscanner.data.OracleClient
import com.mrg.eslscanner.data.OracleResult
import com.mrg.eslscanner.data.OracleSettings
import com.mrg.eslscanner.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configStore: ConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = ConfigStore(this)

        val current = configStore.load()
        with(binding) {
            hostField.setText(current.host)
            portField.setText(current.port)
            serviceField.setText(current.serviceName)
            userField.setText(current.user)
            passwordField.setText(current.password)
            storeCodeField.setText(current.storeCode)
            eslGoodsTableField.setText(current.eslGoodsTable)
            newStatusField.setText(current.newStatusValue)
            lookupTableField.setText(current.productLookupTable)
            lookupBarcodeColField.setText(current.productLookupBarcodeColumn)
            lookupCodColField.setText(current.productLookupCodColumn)
            uploadProcField.setText(current.uploadProcedure)
        }

        binding.saveButton.setOnClickListener { saveAndMaybeTest(test = false) }
        binding.testButton.setOnClickListener { saveAndMaybeTest(test = true) }
    }

    private fun currentSettingsFromForm(): OracleSettings = OracleSettings(
        host = binding.hostField.text.toString().trim(),
        port = binding.portField.text.toString().trim().ifEmpty { "1521" },
        serviceName = binding.serviceField.text.toString().trim(),
        user = binding.userField.text.toString().trim(),
        password = binding.passwordField.text.toString(),
        storeCode = binding.storeCodeField.text.toString().trim(),
        eslGoodsTable = binding.eslGoodsTableField.text.toString().trim()
            .ifEmpty { "UNIMARKET.YLIN_EPRICE_GOODS" },
        newStatusValue = binding.newStatusField.text.toString().trim().ifEmpty { "NEW" },
        productLookupTable = binding.lookupTableField.text.toString().trim(),
        productLookupBarcodeColumn = binding.lookupBarcodeColField.text.toString().trim(),
        productLookupCodColumn = binding.lookupCodColField.text.toString().trim(),
        uploadProcedure = binding.uploadProcField.text.toString().trim()
            .ifEmpty { "PRISMART_API_SEND_NEW.PUSH_TO_ESL_WORK" }
    )

    private fun saveAndMaybeTest(test: Boolean) {
        val settings = currentSettingsFromForm()
        configStore.save(settings)
        binding.statusText.text = "Настройки сохранены."

        if (test) {
            binding.statusText.text = "Проверка подключения..."
            lifecycleScope.launch {
                when (val result = OracleClient(settings).testConnection()) {
                    is OracleResult.Success -> binding.statusText.text = result.message
                    is OracleResult.Failure -> binding.statusText.text = "Ошибка: ${result.error}"
                }
            }
        } else {
            finish()
        }
    }
}
