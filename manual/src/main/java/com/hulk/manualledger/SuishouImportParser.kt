package com.hulk.manualledger

object SuishouImportParser {
    fun parse(bytes: ByteArray): SuishouImportResult {
        val isZip = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return if (isZip) SuishouXlsxParser.parse(bytes) else SuishouCsvParser.parse(bytes)
    }
}
