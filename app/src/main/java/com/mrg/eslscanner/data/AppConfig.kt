package com.mrg.eslscanner.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the Oracle connection settings and the table/procedure names used to
 * assign an ESL barcode to a product and trigger the upload to ESL_WORK.
 * All of this is editable from the Settings screen; the values below are
 * pre-filled defaults for the real MRG/Linella environment.
 */
data class OracleSettings(
    val host: String = "192.168.0.40",
    val port: String = "1521",
    val serviceName: String = "uniback",   // e.g. UNIMARKET / CEN510 service or SID
    val user: String = "unimarket",
    val password: String = "unimarket",
    val storeCode: String = "122",         // MAG_COD

    // The real table confirmed from the schema: UNIMARKET.YLIN_EPRICE_GOODS
    // Composite key MAG_COD + COD already exists per store/product, so we
    // UPDATE this row (not INSERT) to attach the scanned ESL barcode.
    val eslGoodsTable: String = "UNIMARKET.YLIN_EPRICE_GOODS",
    // Value written to STATUS so the existing upload procedure picks the row up.
    // Not yet confirmed — adjust to whatever value your process expects.
    val newStatusValue: String = "NEW",

    // Product barcode -> COD lookup. Left blank until the real catalog table
    // is known; when blank, the scanned product code is used as COD directly.
    val productLookupTable: String = "",
    val productLookupBarcodeColumn: String = "",
    val productLookupCodColumn: String = "",

    // PL/SQL procedure/package that reads YLIN_EPRICE_GOODS and pushes to ESL_WORK
    // (this is where prismart_api_send_new or a wrapper over it would hang).
    val uploadProcedure: String = "PRISMART_API_SEND_NEW.PUSH_TO_ESL_WORK"
) {
    fun jdbcUrl(): String = "jdbc:oracle:thin:@//$host:$port/$serviceName"

    fun isComplete(): Boolean =
        host.isNotBlank() && serviceName.isNotBlank() && user.isNotBlank() &&
            password.isNotBlank() && storeCode.isNotBlank()

    fun hasProductLookup(): Boolean =
        productLookupTable.isNotBlank() && productLookupBarcodeColumn.isNotBlank() &&
            productLookupCodColumn.isNotBlank()
}

class ConfigStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "esl_scanner_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): OracleSettings {
        val defaults = OracleSettings()
        return OracleSettings(
            host = prefs.getString("host", defaults.host) ?: defaults.host,
            port = prefs.getString("port", defaults.port) ?: defaults.port,
            serviceName = prefs.getString("service", defaults.serviceName) ?: defaults.serviceName,
            user = prefs.getString("user", defaults.user) ?: defaults.user,
            password = prefs.getString("password", defaults.password) ?: defaults.password,
            storeCode = prefs.getString("store_code", defaults.storeCode) ?: defaults.storeCode,
            eslGoodsTable = prefs.getString("esl_goods_table", defaults.eslGoodsTable) ?: defaults.eslGoodsTable,
            newStatusValue = prefs.getString("new_status_value", defaults.newStatusValue) ?: defaults.newStatusValue,
            productLookupTable = prefs.getString("lookup_table", "") ?: "",
            productLookupBarcodeColumn = prefs.getString("lookup_barcode_col", "") ?: "",
            productLookupCodColumn = prefs.getString("lookup_cod_col", "") ?: "",
            uploadProcedure = prefs.getString("upload_proc", defaults.uploadProcedure) ?: defaults.uploadProcedure
        )
    }

    fun save(settings: OracleSettings) {
        prefs.edit()
            .putString("host", settings.host)
            .putString("port", settings.port)
            .putString("service", settings.serviceName)
            .putString("user", settings.user)
            .putString("password", settings.password)
            .putString("store_code", settings.storeCode)
            .putString("esl_goods_table", settings.eslGoodsTable)
            .putString("new_status_value", settings.newStatusValue)
            .putString("lookup_table", settings.productLookupTable)
            .putString("lookup_barcode_col", settings.productLookupBarcodeColumn)
            .putString("lookup_cod_col", settings.productLookupCodColumn)
            .putString("upload_proc", settings.uploadProcedure)
            .apply()
    }
}
