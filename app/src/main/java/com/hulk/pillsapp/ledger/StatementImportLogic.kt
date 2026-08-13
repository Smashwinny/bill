package com.hulk.pillsapp.ledger

import com.hulk.pillsapp.sha256Hex
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

const val STATEMENT_PARSER_VERSION = 1
const val MAX_STATEMENT_FILE_BYTES = 25 * 1024 * 1024
const val STATEMENT_ARTIFACT_CHUNK_BYTES = 256 * 1024
private const val MAX_STATEMENT_ROWS = 100_000
private const val MAX_STATEMENT_COLUMNS = 100
private const val MAX_CELL_CHARS = 4_096
private const val MAX_STATEMENT_UNCOMPRESSED_BYTES = 16 * 1024 * 1024

enum class StatementPreviewIssue {
    EMPTY_FILE,
    FILE_TOO_LARGE,
    ENCRYPTED_OR_UNREADABLE_ARCHIVE,
    AMBIGUOUS_CONTAINER,
    UNSUPPORTED_FORMAT,
    UNRECOGNIZED_STATEMENT_SOURCE,
    HEADER_NOT_FOUND,
    NO_VALID_ROWS,
    INVALID_ROWS_PRESENT,
    MALFORMED_TABLE,
    CELL_TOO_LARGE,
    UNSUPPORTED_CURRENCY,
    TOO_MANY_ROWS,
    IMPORT_FAILED,
}

data class NormalizedStatementRow(
    val sourceRowNumber: Int,
    val sourceKind: StatementSourceKind,
    val rowFingerprint: String,
    val externalIdHash: String?,
    val occurredAtMs: Long,
    val amountCents: Long,
    val currency: String,
    val direction: StatementDirection,
    val txType: TxType,
    val txStatus: String,
    val counterparty: String?,
    val itemDescription: String?,
    val rawRecord: String,
)

data class StatementPreview(
    val displayName: String,
    val fileHash: String,
    val sourceKind: StatementSourceKind,
    val format: StatementFormat,
    val rawRowCount: Int,
    val validRowCount: Int,
    val invalidRowCount: Int,
    val ignoredFooterRowCount: Int,
    /** 文件内最早/最晚有效交易行时间；不能据此宣称官方账期完整。 */
    val observedRowFromMs: Long?,
    val observedRowToMs: Long?,
    val issues: List<StatementPreviewIssue>,
    val rows: List<NormalizedStatementRow>,
    /** 仅在完整解析成功时保留；确认后分块写入 SQLCipher，不进入日志或报告。 */
    val sourceArtifact: ByteArray,
) {
    val canImport: Boolean
        get() = rows.isNotEmpty() && issues.isEmpty() && invalidRowCount == 0 &&
            observedRowFromMs != null && observedRowToMs != null

    fun toImportEntity(nowMs: Long): StatementImportEntity = StatementImportEntity(
        publicId = UUID.randomUUID().toString(),
        fileHash = fileHash,
        displayName = displayName.take(256),
        sourceKind = sourceKind,
        format = format,
        parserVersion = STATEMENT_PARSER_VERSION,
        authority = StatementAuthority.FORMAT_RECOGNIZED_UNVERIFIED,
        status = StatementImportStatus.IMPORTING,
        observedRowFromMs = requireNotNull(observedRowFromMs),
        observedRowToMs = requireNotNull(observedRowToMs),
        rawRowCount = rawRowCount,
        validRowCount = validRowCount,
        invalidRowCount = invalidRowCount,
        ignoredFooterRowCount = ignoredFooterRowCount,
        duplicateRowCount = 0,
        artifactSizeBytes = sourceArtifact.size,
        artifactChunkCount = (sourceArtifact.size + STATEMENT_ARTIFACT_CHUNK_BYTES - 1) /
            STATEMENT_ARTIFACT_CHUNK_BYTES,
        importedAtMs = nowMs,
    )

    fun toRowEntities(
        nowMs: Long,
        startIndex: Int = 0,
        endExclusive: Int = rows.size,
    ): List<Pair<Int, StatementRowEntity>> = rows.subList(startIndex, endExclusive).map { row ->
        row.sourceRowNumber to StatementRowEntity(
            sourceKind = row.sourceKind,
            rowFingerprint = row.rowFingerprint,
            externalIdHash = row.externalIdHash,
            occurredAtMs = row.occurredAtMs,
            amountCents = row.amountCents,
            currency = row.currency,
            direction = row.direction,
            txType = row.txType,
            txStatus = row.txStatus,
            counterparty = row.counterparty,
            itemDescription = row.itemDescription,
            rawRecord = row.rawRecord,
            createdAtMs = nowMs,
        )
    }
}

private data class TabularInput(
    val rows: List<List<String>>,
    val container: String,
)

private data class HeaderMapping(
    val headerRowIndex: Int,
    val headers: List<String>,
    val dateIndex: Int,
    val amountIndex: Int,
    val amountHeader: String,
    val currencyIndex: Int?,
    val directionIndex: Int?,
    val statusIndex: Int?,
    val counterpartyIndex: Int?,
    val itemIndex: Int?,
    val externalIdIndex: Int?,
    val sourceKind: StatementSourceKind,
)

private class StatementParseException(
    val issue: StatementPreviewIssue,
) : IllegalArgumentException(issue.name)

object StatementFileParser {
    private val statementZone = ZoneId.of("Asia/Shanghai")
    private val dateHeaders = setOf(
        "交易时间", "创建时间", "交易创建时间", "付款时间", "入账时间", "交易日期", "记账日期",
    )
    private val amountHeaders = setOf(
        "金额(元)", "交易金额", "交易金额(元)", "订单金额(元)", "收支金额", "应结订单金额", "金额",
    )
    private val directionHeaders = setOf("收/支", "收支", "借贷标志", "收支类型")
    private val currencyHeaders = setOf("币种", "货币种类", "交易币种")
    private val statusHeaders = setOf("当前状态", "交易状态", "状态", "资金状态")
    private val counterpartyHeaders = setOf("交易对方", "对方", "商户名称", "卖家信息", "收款方", "对手户名")
    private val itemHeaders = setOf("商品", "商品名称", "交易摘要", "摘要", "交易类型")
    private val externalIdHeaders = listOf(
        "支付宝交易号", "微信支付订单号", "交易单号", "交易号", "银行流水号", "流水号", "商户订单号",
    )
    private val footerMarker = Regex(
        "^(?:注[:：]|说明[:：]|本次导出|交易合计|总交易|收入合计|支出合计|共\\s*[0-9]+\\s*笔)",
    )
    private val dateFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    )
    private val dayFormatters = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd"),
    )

    fun parse(displayName: String, bytes: ByteArray): StatementPreview {
        if (bytes.isEmpty()) return failed(displayName, bytes, StatementPreviewIssue.EMPTY_FILE)
        if (bytes.size > MAX_STATEMENT_FILE_BYTES) {
            return failed(displayName, bytes, StatementPreviewIssue.FILE_TOO_LARGE)
        }
        val fileHash = sha256Bytes(bytes)
        val tabular = try {
            decodeContainer(displayName, bytes)
        } catch (failure: StatementParseException) {
            return failed(displayName, bytes, failure.issue)
        } catch (_: ZipException) {
            return failed(
                displayName,
                bytes,
                StatementPreviewIssue.ENCRYPTED_OR_UNREADABLE_ARCHIVE,
            )
        } catch (_: Throwable) {
            return failed(displayName, bytes, StatementPreviewIssue.UNSUPPORTED_FORMAT)
        } ?: return failed(displayName, bytes, StatementPreviewIssue.UNSUPPORTED_FORMAT)

        if (tabular.rows.size > MAX_STATEMENT_ROWS) {
            return failed(displayName, bytes, StatementPreviewIssue.TOO_MANY_ROWS)
        }
        val mapping = try {
            findHeader(tabular.rows)
        } catch (failure: StatementParseException) {
            return failed(displayName, bytes, failure.issue)
        } ?: return failed(displayName, bytes, StatementPreviewIssue.HEADER_NOT_FOUND)
        if (mapping.sourceKind == StatementSourceKind.UNKNOWN) {
            return failed(displayName, bytes, StatementPreviewIssue.UNRECOGNIZED_STATEMENT_SOURCE)
        }
        val format = formatFor(mapping.sourceKind, tabular.container)
        var invalidRows = 0
        var ignoredFooterRows = 0
        var unsupportedCurrency = false
        var rawRows = 0
        val normalized = ArrayList<NormalizedStatementRow>()
        tabular.rows.drop(mapping.headerRowIndex + 1).forEachIndexed { index, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            rawRows++
            val hasExtraData = row.drop(mapping.headers.size).any { it.isNotBlank() }
            val hasUnnamedData = mapping.headers.indices.any { column ->
                mapping.headers[column].isBlank() && row.value(column).isNotBlank()
            }
            if (hasExtraData || hasUnnamedData) {
                invalidRows++
                return@forEachIndexed
            }
            val dateRaw = row.value(mapping.dateIndex)
            val amountRaw = row.value(mapping.amountIndex)
            if (isRecognizedFooter(dateRaw, amountRaw)) {
                ignoredFooterRows++
                return@forEachIndexed
            }
            val occurredAtMs = parseDate(dateRaw)
            val parsedAmount = parseAmount(amountRaw)
            val currency = parseCurrency(
                mapping.currencyIndex?.let { row.value(it) }.orEmpty(),
                mapping.amountHeader,
            )
            if (occurredAtMs == null || parsedAmount == null || currency == null) {
                if (currency == null) unsupportedCurrency = true
                invalidRows++
                return@forEachIndexed
            }
            val directionRaw = mapping.directionIndex?.let { row.value(it) }.orEmpty()
            val direction = parseDirection(directionRaw, parsedAmount.signum())
            val amountCents = runCatching {
                check(parsedAmount.stripTrailingZeros().scale() <= 2)
                parsedAmount.abs().movePointRight(2).longValueExact()
            }.getOrElse {
                invalidRows++
                return@forEachIndexed
            }
            val status = mapping.statusIndex?.let { row.value(it) }.orEmpty()
            val counterparty = mapping.counterpartyIndex?.let { row.value(it) }?.cleanOptional()
            val item = mapping.itemIndex?.let { row.value(it) }?.cleanOptional()
            val externalId = mapping.externalIdIndex?.let { row.value(it) }?.cleanOptional()
            val canonicalRaw = mapping.headers.mapIndexedNotNull { column, header ->
                row.value(column).takeIf { it.isNotBlank() }?.let { "$header=$it" }
            }.joinToString("\u001f")
            val externalIdHash = externalId?.let {
                sha256Hex("${mapping.sourceKind.name}:ID:$it")
            }
            val fingerprintMaterial = externalId?.let {
                "${mapping.sourceKind.name}:ID:$it"
            } ?: "${mapping.sourceKind.name}:ROW:$canonicalRaw"
            normalized += NormalizedStatementRow(
                sourceRowNumber = mapping.headerRowIndex + index + 2,
                sourceKind = mapping.sourceKind,
                rowFingerprint = sha256Hex(fingerprintMaterial),
                externalIdHash = externalIdHash,
                occurredAtMs = occurredAtMs,
                amountCents = amountCents,
                currency = currency,
                direction = direction,
                txType = inferType(direction, "$status ${item.orEmpty()}"),
                txStatus = status,
                counterparty = counterparty,
                itemDescription = item,
                rawRecord = canonicalRaw,
            )
        }
        val issues = buildList {
            if (normalized.isEmpty()) add(StatementPreviewIssue.NO_VALID_ROWS)
            if (invalidRows > 0) add(StatementPreviewIssue.INVALID_ROWS_PRESENT)
            if (unsupportedCurrency) add(StatementPreviewIssue.UNSUPPORTED_CURRENCY)
        }
        return StatementPreview(
            displayName = displayName,
            fileHash = fileHash,
            sourceKind = mapping.sourceKind,
            format = format,
            rawRowCount = rawRows,
            validRowCount = normalized.size,
            invalidRowCount = invalidRows,
            ignoredFooterRowCount = ignoredFooterRows,
            observedRowFromMs = normalized.minOfOrNull { it.occurredAtMs },
            observedRowToMs = normalized.maxOfOrNull { it.occurredAtMs },
            issues = issues,
            rows = normalized,
            sourceArtifact = bytes,
        )
    }

    private fun failed(
        displayName: String,
        bytes: ByteArray,
        issue: StatementPreviewIssue,
    ) = StatementPreview(
        displayName = displayName,
        fileHash = sha256Bytes(bytes),
        sourceKind = StatementSourceKind.UNKNOWN,
        format = StatementFormat.BANK_CSV,
        rawRowCount = 0,
        validRowCount = 0,
        invalidRowCount = 0,
        ignoredFooterRowCount = 0,
        observedRowFromMs = null,
        observedRowToMs = null,
        issues = listOf(issue),
        rows = emptyList(),
        sourceArtifact = byteArrayOf(),
    )

    internal fun artifactChunkHashes(bytes: ByteArray): List<String> = bytes
        .asList()
        .chunked(STATEMENT_ARTIFACT_CHUNK_BYTES)
        .map { chunk -> sha256Bytes(chunk.toByteArray()) }

    private fun decodeContainer(displayName: String, bytes: ByteArray): TabularInput? {
        val lower = displayName.lowercase()
        if (bytes.startsWithZipSignature()) {
            val entries = unzip(bytes)
            if (entries.containsKey("xl/workbook.xml") && entries.keys.any { it.startsWith("xl/worksheets/") }) {
                return TabularInput(parseXlsx(entries), "XLSX")
            }
            val candidates = entries.entries.filter { (name, _) ->
                name.lowercase().endsWith(".csv") || name.lowercase().endsWith(".tsv") ||
                    name.lowercase().endsWith(".txt") || name.lowercase().endsWith(".xlsx")
            }
            if (candidates.size > 1) {
                throw StatementParseException(StatementPreviewIssue.AMBIGUOUS_CONTAINER)
            }
            val candidate = candidates.singleOrNull() ?: return null
            return if (candidate.key.lowercase().endsWith(".xlsx")) {
                val nested = unzip(candidate.value)
                TabularInput(parseXlsx(nested), "XLSX")
            } else {
                TabularInput(parseDelimited(decodeText(candidate.value)), "CSV")
            }
        }
        if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt") ||
            bytes.take(512).toByteArray().any { it == ','.code.toByte() || it == '\t'.code.toByte() }
        ) {
            return TabularInput(parseDelimited(decodeText(bytes)), "CSV")
        }
        return null
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            var entries = 0
            var totalUncompressed = 0
            while (true) {
                val entry = input.nextEntry ?: break
                entries++
                if (entries > 300) throw ZipException("压缩包条目过多")
                if (entry.isDirectory) continue
                val normalized = entry.name.replace('\\', '/').removePrefix("/")
                if (normalized.contains("../")) throw ZipException("非法压缩包路径")
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_STATEMENT_FILE_BYTES) {
                        throw StatementParseException(StatementPreviewIssue.FILE_TOO_LARGE)
                    }
                    totalUncompressed += read
                    if (totalUncompressed > MAX_STATEMENT_UNCOMPRESSED_BYTES) {
                        throw StatementParseException(StatementPreviewIssue.FILE_TOO_LARGE)
                    }
                    out.write(buffer, 0, read)
                }
                if (result.put(normalized, out.toByteArray()) != null) {
                    throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
                }
            }
        }
        if (result.isEmpty()) throw ZipException("空或加密压缩包")
        return result
    }

    private fun parseXlsx(entries: Map<String, ByteArray>): List<List<String>> {
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val sheets = entries.entries
            .filter { it.key.startsWith("xl/worksheets/") && it.key.endsWith(".xml") }
        if (sheets.size > 1) {
            throw StatementParseException(StatementPreviewIssue.AMBIGUOUS_CONTAINER)
        }
        val sheet = sheets.singleOrNull() ?: throw IllegalArgumentException("工作表不存在")
        val document = secureDocument(sheet.value)
        val rowNodes = document.getElementsByTagName("row")
        if (rowNodes.length > MAX_STATEMENT_ROWS) {
            throw StatementParseException(StatementPreviewIssue.TOO_MANY_ROWS)
        }
        val rows = ArrayList<List<String>>(rowNodes.length)
        for (i in 0 until rowNodes.length) {
            val rowNode = rowNodes.item(i)
            val cells = rowNode.childNodes
            val values = ArrayList<String>()
            val seenColumns = BooleanArray(MAX_STATEMENT_COLUMNS)
            for (j in 0 until cells.length) {
                val cell = cells.item(j)
                if (cell.nodeName != "c") continue
                val ref = cell.attributes?.getNamedItem("r")?.nodeValue.orEmpty()
                val column = excelColumnIndex(ref)
                if (column !in 0 until MAX_STATEMENT_COLUMNS) {
                    throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
                }
                if (seenColumns[column]) {
                    throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
                }
                seenColumns[column] = true
                while (values.size <= column) values += ""
                val type = cell.attributes?.getNamedItem("t")?.nodeValue
                val raw = when (type) {
                    "inlineStr" -> descendantText(cell, "t")
                    else -> descendantText(cell, "v")
                }
                values[column] = when (type) {
                    "s" -> raw.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                    else -> raw
                }.cleanCell()
            }
            rows += values
        }
        return rows
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val document = secureDocument(bytes)
        val nodes = document.getElementsByTagName("si")
        if (nodes.length > MAX_STATEMENT_ROWS) {
            throw StatementParseException(StatementPreviewIssue.TOO_MANY_ROWS)
        }
        return List(nodes.length) { index -> descendantText(nodes.item(index), "t").cleanCell() }
    }

    private fun secureDocument(bytes: ByteArray): org.w3c.dom.Document {
        rejectDangerousXml(bytes)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // Android 各版本 XML 工厂支持的 feature 不一致；字节级 DTD/ENTITY 拒绝与
        // EntityResolver 是跨版本强制边界，平台支持的 feature 再作为纵深防御。
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isExpandEntityReferences = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("外部实体已禁用") }
        return builder.parse(ByteArrayInputStream(bytes))
    }

    private fun rejectDangerousXml(bytes: ByteArray) {
        val decoded = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
                String(bytes, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
                String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }.uppercase(Locale.ROOT)
        if ("<!DOCTYPE" in decoded || "<!ENTITY" in decoded) {
            throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
        }
    }

    private fun descendantText(node: org.w3c.dom.Node, tag: String): String {
        val children = node.childNodes
        val out = StringBuilder()
        fun visit(current: org.w3c.dom.Node) {
            if (current.nodeName == tag) out.append(current.textContent.orEmpty())
            val nested = current.childNodes
            for (i in 0 until nested.length) visit(nested.item(i))
        }
        for (i in 0 until children.length) visit(children.item(i))
        return out.toString()
    }

    private fun excelColumnIndex(reference: String): Int {
        var result = 0
        reference.takeWhile(Char::isLetter).uppercase().forEach { result = result * 26 + (it - 'A' + 1) }
        return result - 1
    }

    private fun parseDelimited(text: String): List<List<String>> {
        val commaCount = text.take(8_192).count { it == ',' }
        val tabCount = text.take(8_192).count { it == '\t' }
        val delimiter = if (tabCount > commaCount) '\t' else ','
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var quoteClosed = false
        var i = 0
        fun finishCell() {
            if (row.size >= MAX_STATEMENT_COLUMNS) {
                throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
            }
            row += cell.toString().cleanCell()
            cell.setLength(0)
            quoteClosed = false
        }
        fun finishRow() {
            finishCell()
            rows += row.toList()
            row.clear()
            if (rows.size > MAX_STATEMENT_ROWS) {
                throw StatementParseException(StatementPreviewIssue.TOO_MANY_ROWS)
            }
        }
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    if (cell.length >= MAX_CELL_CHARS) {
                        throw StatementParseException(StatementPreviewIssue.CELL_TOO_LARGE)
                    }
                    cell.append('"')
                    i++
                }
                ch == '"' && !quoted && cell.isEmpty() && !quoteClosed -> quoted = true
                ch == '"' && quoted -> {
                    quoted = false
                    quoteClosed = true
                }
                ch == '"' -> throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
                ch == delimiter && !quoted -> finishCell()
                (ch == '\n' || ch == '\r') && !quoted -> {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    finishRow()
                }
                !quoted && quoteClosed -> throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
                cell.length < MAX_CELL_CHARS -> cell.append(ch)
                else -> throw StatementParseException(StatementPreviewIssue.CELL_TOO_LARGE)
            }
            i++
        }
        if (quoted) throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { utf8.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse {
                charset("GB18030").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }
            .removePrefix("\uFEFF")
    }

    private fun findHeader(rows: List<List<String>>): HeaderMapping? {
        rows.take(100).forEachIndexed { rowIndex, rawHeaders ->
            val headers = rawHeaders.take(MAX_STATEMENT_COLUMNS).map(::normalizeHeader)
            val date = headers.firstIndexIn(dateHeaders)
            val amount = headers.firstIndexIn(amountHeaders)
            if (date == null || amount == null) return@forEachIndexed
            val preamble = rows.take(rowIndex + 1).flatten().joinToString(" ")
            val nonBlankHeaders = headers.filter { it.isNotBlank() }
            if (nonBlankHeaders.size != nonBlankHeaders.distinct().size) {
                throw StatementParseException(StatementPreviewIssue.MALFORMED_TABLE)
            }
            val source = when {
                preamble.contains("支付宝") &&
                    headers.contains("交易创建时间") && headers.contains("收/支") &&
                    (headers.contains("支付宝交易号") || headers.contains("交易号")) &&
                    (headers.contains("交易状态") || headers.contains("资金状态")) ->
                    StatementSourceKind.ALIPAY
                (preamble.contains("微信支付") && headers.contains("交易单号") &&
                    headers.contains("当前状态") && headers.contains("收/支")) ||
                    headers.containsAll(
                        setOf("交易类型", "交易对方", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号")
                    ) ->
                    StatementSourceKind.WECHAT
                Regex("银行|账户明细|银行卡流水").containsMatchIn(preamble) &&
                    headers.contains("借贷标志") && currencyHeaders.any(headers::contains) &&
                    (headers.contains("银行流水号") || headers.contains("流水号")) ->
                    StatementSourceKind.BANK
                else -> StatementSourceKind.UNKNOWN
            }
            return HeaderMapping(
                headerRowIndex = rowIndex,
                headers = headers,
                dateIndex = date,
                amountIndex = amount,
                amountHeader = headers[amount],
                currencyIndex = headers.firstIndexIn(currencyHeaders),
                directionIndex = headers.firstIndexIn(directionHeaders),
                statusIndex = headers.firstIndexIn(statusHeaders),
                counterpartyIndex = headers.firstIndexIn(counterpartyHeaders),
                itemIndex = headers.firstIndexIn(itemHeaders),
                externalIdIndex = externalIdHeaders.firstNotNullOfOrNull { target ->
                    headers.indexOf(target).takeIf { it >= 0 }
                },
                sourceKind = source,
            )
        }
        return null
    }

    private fun formatFor(source: StatementSourceKind, container: String): StatementFormat =
        when (source to container) {
            StatementSourceKind.ALIPAY to "XLSX" -> StatementFormat.ALIPAY_XLSX
            StatementSourceKind.ALIPAY to "CSV" -> StatementFormat.ALIPAY_CSV
            StatementSourceKind.WECHAT to "XLSX" -> StatementFormat.WECHAT_XLSX
            StatementSourceKind.WECHAT to "CSV" -> StatementFormat.WECHAT_CSV
            StatementSourceKind.BANK to "XLSX" -> StatementFormat.BANK_XLSX
            StatementSourceKind.BANK to "CSV" -> StatementFormat.BANK_CSV
            else -> error("未识别来源不能映射成银行格式")
        }

    private fun parseDate(raw: String): Long? {
        val clean = raw.cleanCell()
        dateFormatters.forEach { formatter ->
            try {
                return LocalDateTime.parse(clean, formatter).atZone(statementZone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // 尝试下一格式
            }
        }
        dayFormatters.forEach { formatter ->
            try {
                return LocalDate.parse(clean, formatter).atStartOfDay(statementZone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // 尝试下一格式
            }
        }
        val excel = clean.toDoubleOrNull()
        return excel?.takeIf { it in 20_000.0..100_000.0 }?.let {
            LocalDate.of(1899, 12, 30).atStartOfDay().plusSeconds((it * 86_400).toLong())
                .atZone(statementZone).toInstant().toEpochMilli()
        }
    }

    private fun parseAmount(raw: String): BigDecimal? = runCatching {
        val cleaned = raw.cleanCell()
            .replace("¥", "").replace("￥", "").replace("元", "")
            .replace(",", "").replace(" ", "")
            .removePrefix("`")
        val accounting = cleaned.startsWith("(") && cleaned.endsWith(")")
        BigDecimal(cleaned.removePrefix("(").removeSuffix(")")).let { if (accounting) it.negate() else it }
    }.getOrNull()

    private fun parseCurrency(raw: String, amountHeader: String): String? {
        val clean = raw.cleanCell().uppercase()
        return when {
            clean in setOf("CNY", "RMB", "人民币", "人民币元", "元") -> "CNY"
            clean.isBlank() && amountHeader.contains("(元)") -> "CNY"
            else -> null
        }
    }

    private fun parseDirection(raw: String, amountSign: Int): StatementDirection = when {
        Regex("支出|付出|借|扣款").containsMatchIn(raw) -> StatementDirection.OUT
        Regex("收入|收款|贷|入账").containsMatchIn(raw) -> StatementDirection.IN
        Regex("不计收支|中性").containsMatchIn(raw) -> StatementDirection.NEUTRAL
        amountSign < 0 -> StatementDirection.OUT
        else -> StatementDirection.UNKNOWN
    }

    private fun inferType(direction: StatementDirection, text: String): TxType = when {
        Regex("退款|退回|退货").containsMatchIn(text) -> TxType.REFUND
        Regex("冲正|撤销").containsMatchIn(text) -> TxType.REVERSAL
        Regex("转账|提现|充值|还款").containsMatchIn(text) -> TxType.TRANSFER
        Regex("手续费|服务费|利息|罚息").containsMatchIn(text) -> TxType.FEE
        direction == StatementDirection.OUT -> TxType.PAYMENT
        direction == StatementDirection.IN -> TxType.INCOME
        else -> TxType.UNKNOWN
    }

    private fun isRecognizedFooter(dateCell: String, amountCell: String): Boolean =
        amountCell.isBlank() && footerMarker.containsMatchIn(dateCell)

    private fun String.cleanCell(): String {
        if (length > MAX_CELL_CHARS) {
            throw StatementParseException(StatementPreviewIssue.CELL_TOO_LARGE)
        }
        return trim().removePrefix("`")
    }

    private fun String.cleanOptional(): String? = cleanCell().takeIf { it.isNotBlank() }
    private fun List<String>.value(index: Int): String = getOrNull(index).orEmpty().cleanCell()
    private fun normalizeHeader(raw: String): String = raw.cleanCell()
        .replace("（", "(").replace("）", ")").replace(" ", "").replace("\n", "")
    private fun List<String>.firstIndexIn(candidates: Set<String>): Int? =
        indexOfFirst { it in candidates }.takeIf { it >= 0 }
    private fun ByteArray.startsWithZipSignature(): Boolean =
        size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4b.toByte()

    private fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}

data class SourceCoverageItem(
    val stableKey: String,
    val label: String,
    val channel: String,
    val isObservationSource: Boolean,
    val observationCount: Long,
    val firstSeenAtMs: Long?,
    val lastSeenAtMs: Long?,
    val statementObservedRowFromMs: Long?,
    val statementObservedRowToMs: Long?,
    val authority: StatementAuthority?,
    val gapLabel: String,
)

object SourceCoverageAudit {
    private val knownPackages = mapOf(
        "com.eg.android.AlipayGphone" to ("支付宝" to StatementSourceKind.ALIPAY),
        "com.tencent.mm" to ("微信" to StatementSourceKind.WECHAT),
        "com.unionpay" to ("云闪付" to StatementSourceKind.BANK),
    )
    private val serviceNumbers = mapOf(
        "95588" to "工商银行", "95533" to "建设银行", "95599" to "农业银行",
        "95566" to "中国银行", "95559" to "交通银行", "95555" to "招商银行",
        "95580" to "邮储银行", "95528" to "浦发银行", "95558" to "中信银行",
        "95561" to "兴业银行", "95568" to "民生银行", "95511" to "平安银行",
        "95595" to "光大银行", "95577" to "华夏银行", "95508" to "广发银行",
    )

    fun build(
        raw: List<RawSourceCoverageSummary>,
        imports: List<StatementImportSummary>,
    ): List<SourceCoverageItem> {
        val items = raw.mapNotNull { summary ->
            val known = knownPackages[summary.sourceNamespace]
            val normalizedSmsSender = Regex("^(?:\\+?86)?(955[0-9]{2})$")
                .matchEntire(summary.sourceNamespace.trim())
                ?.groupValues?.get(1)
            val serviceLabel = if (summary.source == ObservationSource.SMS) {
                normalizedSmsSender?.let(serviceNumbers::get)
            } else {
                null
            }
            val isFinancial = summary.transactionEvidenceCount > 0 || summary.debtEvidenceCount > 0 ||
                known != null || serviceLabel != null
            if (!isFinancial) return@mapNotNull null
            val label = known?.first ?: serviceLabel ?: when (summary.source) {
                ObservationSource.SMS -> "短信金融来源 ••${summary.sourceNamespace.takeLast(4)}"
                else -> summary.sourceNamespace.take(80)
            }
            SourceCoverageItem(
                stableKey = "${summary.source}:${summary.userHandle}:${summary.sourceNamespace}",
                label = label,
                channel = when (summary.source) {
                    ObservationSource.NOTIFICATION -> "通知观察"
                    ObservationSource.SMS -> "短信观察"
                    ObservationSource.A11Y -> "受限页面观察"
                    ObservationSource.BILL_IMPORT -> "账单观察"
                },
                isObservationSource = true,
                observationCount = summary.observationCount,
                firstSeenAtMs = summary.firstSeenAtMs,
                lastSeenAtMs = summary.lastSeenAtMs,
                statementObservedRowFromMs = null,
                statementObservedRowToMs = null,
                authority = null,
                // M4.0 没有账户归属确认，即使来源种类相同也不自动把文件覆盖到该观察源。
                gapLabel = "缺少已归属到本账户的官方账单覆盖",
            )
        }.toMutableList()
        imports.filter { imported ->
            imported.status in setOf(
                StatementImportStatus.IMPORTED_UNVERIFIED,
                StatementImportStatus.PERIOD_VALIDATED,
                StatementImportStatus.RECONCILED,
            )
        }.forEach { imported ->
            items += SourceCoverageItem(
                stableKey = "IMPORT:${imported.id}",
                label = when (imported.sourceKind) {
                    StatementSourceKind.ALIPAY -> "疑似支付宝格式文件（账户待归属）"
                    StatementSourceKind.WECHAT -> "疑似微信格式文件（账户待归属）"
                    StatementSourceKind.BANK -> "疑似银行格式文件（账户待归属）"
                    StatementSourceKind.UNKNOWN -> "未识别账单"
                },
                channel = "文件导入",
                isObservationSource = false,
                observationCount = 0,
                firstSeenAtMs = null,
                lastSeenAtMs = null,
                statementObservedRowFromMs = imported.observedRowFromMs,
                statementObservedRowToMs = imported.observedRowToMs,
                authority = imported.authority,
                gapLabel = "缺少与实际账户的归属确认",
            )
        }
        return items.sortedByDescending { maxOf(it.lastSeenAtMs ?: 0, it.statementObservedRowToMs ?: 0) }
    }
}
