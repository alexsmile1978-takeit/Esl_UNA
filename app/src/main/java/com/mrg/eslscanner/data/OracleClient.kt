package com.mrg.eslscanner.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager

sealed class OracleResult {
    data class Success(val message: String) : OracleResult()
    data class Failure(val error: String) : OracleResult()
}

/** Result of resolving a scanned product barcode to a COD value. */
sealed class CodLookupResult {
    data class Found(val cod: String) : CodLookupResult()
    object NotFound : CodLookupResult()
    data class Failure(val error: String) : CodLookupResult()
}

/**
 * Thin wrapper around the Oracle JDBC (ojdbc8) driver.
 *
 * Target table confirmed from the schema: UNIMARKET.YLIN_EPRICE_GOODS
 * (PK: MAG_COD + COD, plus ESL_BARCODE, UPDATETIME, STATUS, ...).
 * The row for (store, product) already exists, so assigning an ESL barcode
 * is an UPDATE, not an INSERT.
 *
 * Product barcode -> COD: if settings.hasProductLookup() is false, the
 * scanned "product" value is assumed to already be COD. Once you know the
 * real catalog table/columns, fill them in on the Settings screen (or as
 * defaults in AppConfig.kt) and the lookup step activates automatically.
 */
class OracleClient(private val settings: OracleSettings) {

    init {
        Class.forName("oracle.jdbc.OracleDriver")
    }

    private fun openConnection(): Connection =
        DriverManager.getConnection(settings.jdbcUrl(), settings.user, settings.password)

    /** Quick connectivity check, used from the Settings screen. */
    suspend fun testConnection(): OracleResult = withContext(Dispatchers.IO) {
        try {
            openConnection().use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT 1 FROM dual").use { rs ->
                        if (rs.next()) OracleResult.Success("Подключение успешно")
                        else OracleResult.Failure("Пустой ответ от БД")
                    }
                }
            }
        } catch (e: Exception) {
            OracleResult.Failure(e.message ?: "Неизвестная ошибка подключения")
        }
    }

    /**
     * Resolves a scanned product barcode to COD using the configured lookup
     * table. If no lookup table is configured, returns the scanned value
     * unchanged (assumes it already is COD).
     */
    suspend fun resolveCod(scannedProductValue: String): CodLookupResult = withContext(Dispatchers.IO) {
        if (!settings.hasProductLookup()) {
            return@withContext CodLookupResult.Found(scannedProductValue)
        }
        try {
            openConnection().use { conn ->
                val sql = "SELECT ${settings.productLookupCodColumn} " +
                    "FROM ${settings.productLookupTable} " +
                    "WHERE ${settings.productLookupBarcodeColumn} = ?"
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, scannedProductValue)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            CodLookupResult.Found(rs.getString(1))
                        } else {
                            CodLookupResult.NotFound
                        }
                    }
                }
            }
        } catch (e: Exception) {
            CodLookupResult.Failure(e.message ?: "Ошибка поиска товара по штрих-коду")
        }
    }

    /**
     * Attaches the scanned ESL barcode to the existing (MAG_COD, COD) row
     * in YLIN_EPRICE_GOODS and marks it for pickup by the upload procedure.
     */
    suspend fun assignEslBarcode(cod: String, eslBarcode: String): OracleResult =
        withContext(Dispatchers.IO) {
            try {
                val magCod = settings.storeCode.toIntOrNull()
                    ?: return@withContext OracleResult.Failure("Код магазина (MAG_COD) должен быть числом")
                val codInt = cod.toLongOrNull()
                    ?: return@withContext OracleResult.Failure("COD должен быть числом: '$cod'")

                openConnection().use { conn ->
                    val sql = """
                        UPDATE ${settings.eslGoodsTable}
                           SET ESL_BARCODE = ?,
                               UPDATETIME = SYSDATE,
                               STATUS = ?
                         WHERE MAG_COD = ?
                           AND COD = ?
                    """.trimIndent()
                    val rowsUpdated = conn.prepareStatement(sql).use { ps ->
                        ps.setString(1, eslBarcode)
                        ps.setString(2, settings.newStatusValue)
                        ps.setInt(3, magCod)
                        ps.setLong(4, codInt)
                        ps.executeUpdate()
                    }
                    conn.commit()

                    if (rowsUpdated == 0) {
                        OracleResult.Failure(
                            "Строка не найдена: MAG_COD=$magCod, COD=$codInt в ${settings.eslGoodsTable}"
                        )
                    } else {
                        OracleResult.Success("ESL_BARCODE обновлён (MAG_COD=$magCod, COD=$codInt)")
                    }
                }
            } catch (e: Exception) {
                OracleResult.Failure(e.message ?: "Ошибка обновления записи")
            }
        }

    /**
     * Calls the PL/SQL procedure that pushes marked rows into ESL_WORK.
     * Default assumption: procedure takes (store_code, cod, esl_barcode).
     * Edit the `{call ...}` string if the real signature differs.
     */
    suspend fun callUploadProcedure(cod: String, eslBarcode: String): OracleResult =
        withContext(Dispatchers.IO) {
            try {
                openConnection().use { conn ->
                    val call = "{call ${settings.uploadProcedure}(?, ?, ?)}"
                    conn.prepareCall(call).use { cs ->
                        cs.setString(1, settings.storeCode)
                        cs.setString(2, cod)
                        cs.setString(3, eslBarcode)
                        cs.execute()
                    }
                    conn.commit()
                }
                OracleResult.Success("Выгрузка в ESL_WORK запущена")
            } catch (e: Exception) {
                OracleResult.Failure(e.message ?: "Ошибка вызова процедуры")
            }
        }

    /**
     * Full flow used by MainActivity:
     * scanned product value -> resolve COD -> UPDATE YLIN_EPRICE_GOODS -> call upload procedure.
     */
    suspend fun sendScan(scannedProductValue: String, eslBarcode: String): OracleResult {
        val cod = when (val lookup = resolveCod(scannedProductValue)) {
            is CodLookupResult.Found -> lookup.cod
            is CodLookupResult.NotFound -> return OracleResult.Failure(
                "Товар со штрих-кодом '$scannedProductValue' не найден в справочнике"
            )
            is CodLookupResult.Failure -> return OracleResult.Failure(lookup.error)
        }

        val updateResult = assignEslBarcode(cod, eslBarcode)
        if (updateResult is OracleResult.Failure) return updateResult

        return callUploadProcedure(cod, eslBarcode)
    }
}
