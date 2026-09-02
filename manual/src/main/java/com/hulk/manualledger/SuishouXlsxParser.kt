package com.hulk.manualledger

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 只读取 XLSX 中迁移所需的 XML，不执行宏、公式或外部链接。 */
object SuishouXlsxParser {
    private const val MAX_UNCOMPRESSED_BYTES = 100 * 1024 * 1024
    private const val MAX_ENTRIES = 256

    fun parse(bytes: ByteArray): SuishouImportResult {
        val entries = unzip(bytes)
        require("[Content_Types].xml" in entries) { "文件不是有效的 XLSX" }
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::xml)?.let(::sharedStrings).orEmpty()
        val dateStyles = entries["xl/styles.xml"]?.let(::xml)?.let(::dateStyleIndexes).orEmpty()
        val candidates = entries.entries
            .filter { (name, _) -> name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.key }
            .map { (_, data) ->
                val rows = sheetRows(xml(data), sharedStrings, dateStyles)
                SuishouCsvParser.parse(rows.joinToString("\n") { row -> row.joinToString(",", transform = ::csvCell) })
            }
        require(candidates.isNotEmpty()) { "XLSX 中没有工作表" }
        val validSheets = candidates.filter { it.rows.isNotEmpty() }
        if (validSheets.isEmpty()) {
            val bestFailure = candidates.minByOrNull { it.rejectedRows }!!
            return bestFailure.copy(sourceEncoding = "XLSX")
        }
        val combined = validSheets.flatMap { it.rows }.distinctBy { it.stableId }
        return SuishouImportResult(
            rows = combined,
            rejectedRows = validSheets.sumOf { it.rejectedRows },
            rejectedReasons = validSheets.flatMap { it.rejectedReasons }.distinct().take(10),
            sourceEncoding = "XLSX",
        )
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(result.size < MAX_ENTRIES) { "XLSX 文件条目过多" }
                if (!entry.isDirectory) {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_UNCOMPRESSED_BYTES) { "XLSX 解压后超过 100 MiB" }
                        output.write(buffer, 0, count)
                    }
                    result[entry.name] = output.toByteArray()
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun xml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun sharedStrings(document: Document): List<String> {
        val nodes = document.getElementsByTagNameNS("*", "si")
        return List(nodes.length) { index ->
            val texts = (nodes.item(index) as Element).getElementsByTagNameNS("*", "t")
            buildString { for (i in 0 until texts.length) append(texts.item(i).textContent) }
        }
    }

    private fun dateStyleIndexes(document: Document): Set<Int> {
        val customFormats = mutableMapOf<Int, String>()
        val formatNodes = document.getElementsByTagNameNS("*", "numFmt")
        for (index in 0 until formatNodes.length) {
            val element = formatNodes.item(index) as Element
            customFormats[element.getAttribute("numFmtId").toIntOrNull() ?: continue] = element.getAttribute("formatCode")
        }
        val xfs = document.getElementsByTagNameNS("*", "cellXfs").item(0) as? Element ?: return emptySet()
        val xfNodes = xfs.getElementsByTagNameNS("*", "xf")
        return buildSet {
            for (index in 0 until xfNodes.length) {
                val id = (xfNodes.item(index) as Element).getAttribute("numFmtId").toIntOrNull() ?: continue
                if (id in 14..22 || id in 45..47 || customFormats[id]?.let(::looksLikeDateFormat) == true) add(index)
            }
        }
    }

    private fun looksLikeDateFormat(raw: String): Boolean {
        val clean = raw.replace(Regex("\"[^\"]*\"|\\[[^]]*]"), "").lowercase()
        return clean.contains('y') && clean.contains('d') || clean.contains("h:")
    }

    private fun sheetRows(document: Document, shared: List<String>, dateStyles: Set<Int>): List<List<String>> {
        val rows = document.getElementsByTagNameNS("*", "row")
        return List(rows.length) { rowIndex ->
            val cells = (rows.item(rowIndex) as Element).getElementsByTagNameNS("*", "c")
            val values = sortedMapOf<Int, String>()
            for (index in 0 until cells.length) {
                val cell = cells.item(index) as Element
                val column = columnIndex(cell.getAttribute("r"))
                val type = cell.getAttribute("t")
                val value = when (type) {
                    "s" -> cellValue(cell).toIntOrNull()?.let(shared::getOrNull).orEmpty()
                    "inlineStr" -> cell.getElementsByTagNameNS("*", "t").let { texts ->
                        buildString { for (i in 0 until texts.length) append(texts.item(i).textContent) }
                    }
                    "b" -> if (cellValue(cell) == "1") "是" else "否"
                    else -> {
                        val raw = cellValue(cell)
                        val style = cell.getAttribute("s").toIntOrNull()
                        if (style in dateStyles) excelDate(raw) else raw
                    }
                }
                values[column] = value
            }
            if (values.isEmpty()) emptyList() else List(values.lastKey() + 1) { values[it].orEmpty() }
        }.filter { row -> row.any(String::isNotBlank) }
    }

    private fun cellValue(cell: Element): String = cell.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()

    private fun columnIndex(reference: String): Int {
        var result = 0
        for (character in reference.takeWhile(Char::isLetter).uppercase()) result = result * 26 + (character - 'A' + 1)
        return (result - 1).coerceAtLeast(0)
    }

    private fun excelDate(raw: String): String {
        val serial = raw.toDoubleOrNull() ?: return raw
        val wholeDays = kotlin.math.floor(serial).toLong()
        val seconds = kotlin.math.round((serial - wholeDays) * 86_400.0).toLong()
        val dateTime = LocalDateTime.of(1899, 12, 30, 0, 0).plusDays(wholeDays).plusSeconds(seconds)
        return if (seconds == 0L) dateTime.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        else dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }

    private fun csvCell(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value
}
