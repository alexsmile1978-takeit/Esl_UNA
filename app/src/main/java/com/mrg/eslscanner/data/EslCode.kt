package com.mrg.eslscanner.data

/**
 * Kotlin port of the Oracle PL/SQL function:
 *
 *   FUNCTION get_esl_code(els_barcode varchar2) RETURN VARCHAR2 IS
 *   l_esl_code varchar2(20);
 *   BEGIN
 *     l_esl_code := substr(els_barcode, length(els_barcode)-9, 10);
 *     select upper(trim(to_char(l_esl_code,'XXXXXXXXXX'))) into l_esl_code from dual;
 *     select substr(l_esl_code,1,2)||'-'||substr(l_esl_code,3,2)||'-'
 *            ||substr(l_esl_code,5,2)||'-'||substr(l_esl_code,7,2) into l_esl_code from dual;
 *     return l_esl_code;
 *   END;
 *
 * Takes the printed barcode from an ESL price tag, reads its last 10
 * characters as a decimal number, converts that to uppercase hex, and
 * formats the first 8 hex digits as four dash-separated byte pairs
 * (e.g. "C0-BF-3E-91") — matching the ID the tag's NFC chip reports
 * directly, so both scan paths (camera barcode vs NFC tap) resolve to the
 * same ESL identifier.
 *
 * Faithfully reproduces the original's behavior, including that it takes
 * the FIRST 8 hex characters (not zero-padded) rather than the last 8 — for
 * a normal 10-digit barcode this is a non-issue since the hex result is
 * 8 characters; only unusually large last-10-digit values (hex length 9)
 * would have their final hex digit silently dropped, same as in Oracle.
 */
fun eslCodeFromBarcode(barcode: String): String? {
    if (barcode.length < 10) return null
    val last10 = barcode.takeLast(10)
    val number = last10.toLongOrNull() ?: return null
    val hex = java.lang.Long.toHexString(number).uppercase()
    if (hex.length < 8) return null
    return "${hex.substring(0, 2)}-${hex.substring(2, 4)}-${hex.substring(4, 6)}-${hex.substring(6, 8)}"
}
