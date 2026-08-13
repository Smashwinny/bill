package com.hulk.pillsapp.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StatementImportLogicTest {
    @Test
    fun parsesWechatCsvWithoutExposingRowsInPreviewSummary() {
        val csv = """
            #微信支付账单明细
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2026-08-01 12:30:00,商户消费,测试商户,午餐,支出,¥10.00,零钱,支付成功,WX-1,M-1,
            2026-08-02 13:00:00,退款,测试商户,退款,收入,5.00,零钱,已退款,WX-2,M-2,
        """.trimIndent()
        val preview = StatementFileParser.parse("wechat.csv", csv.toByteArray())
        assertTrue(preview.canImport)
        assertEquals(StatementSourceKind.WECHAT, preview.sourceKind)
        assertEquals(StatementFormat.WECHAT_CSV, preview.format)
        assertEquals(2, preview.validRowCount)
        assertEquals(0, preview.invalidRowCount)
        assertEquals(TxType.PAYMENT, preview.rows[0].txType)
        assertEquals(TxType.REFUND, preview.rows[1].txType)
        assertEquals(1000L, preview.rows[0].amountCents)
        assertEquals(500L, preview.rows[1].amountCents)
        assertFalse(preview.fileHash.contains("测试商户"))
        assertTrue(preview.sourceArtifact.contentEquals(csv.toByteArray()))
    }

    @Test
    fun parsesAlipayCsvWithMetadataBeforeHeaderAndGbkEncoding() {
        val csv = """
            #支付宝交易流水明细
            #账号:[已脱敏]
            交易创建时间,交易号,商户订单号,交易对方,商品名称,金额（元）,收/支,交易状态,资金状态
            2026-08-03 09:00:00,ALI-1,ORDER-1,测试店,早餐,12.34,支出,交易成功,已支出
        """.trimIndent()
        val preview = StatementFileParser.parse("alipay.csv", csv.toByteArray(charset("GB18030")))
        assertTrue(preview.canImport)
        assertEquals(StatementSourceKind.ALIPAY, preview.sourceKind)
        assertEquals(StatementFormat.ALIPAY_CSV, preview.format)
        assertEquals(1234L, preview.rows.single().amountCents)
        assertTrue(preview.rows.single().externalIdHash?.length == 64)
    }

    @Test
    fun genericBankRequiresDateAndAmountButNeverBecomesAuthoritative() {
        val csv = """
            #招商银行账户明细
            交易日期,交易摘要,借贷标志,交易金额,币种,流水号
            2026-08-04,网银支付,借,100.00,CNY,BANK-1
        """.trimIndent()
        val preview = StatementFileParser.parse("bank.csv", csv.toByteArray())
        assertTrue(preview.canImport)
        assertEquals(StatementSourceKind.BANK, preview.sourceKind)
        assertEquals(StatementDirection.OUT, preview.rows.single().direction)
        assertEquals(
            StatementAuthority.FORMAT_RECOGNIZED_UNVERIFIED,
            preview.toImportEntity(1).authority,
        )
    }

    @Test
    fun oneInvalidDataRowBlocksPartialImport() {
        val csv = """
            #微信支付账单明细
            交易时间,交易单号,当前状态,金额(元),收/支
            2026-08-01 12:00:00,WX-1,支付成功,10.00,支出
            时间坏了,WX-2,支付成功,20.00,支出
        """.trimIndent()
        val preview = StatementFileParser.parse("wechat.csv", csv.toByteArray())
        assertEquals(1, preview.validRowCount)
        assertEquals(1, preview.invalidRowCount)
        assertFalse(preview.canImport)
        assertTrue(StatementPreviewIssue.INVALID_ROWS_PRESENT in preview.issues)
    }

    @Test
    fun fractionalFenAndUnknownCurrencyBlockWholeImport() {
        val fractional = StatementFileParser.parse(
            "wechat.csv",
            ("#微信支付账单明细\n交易时间,交易单号,当前状态,金额(元),收/支\n" +
                "2026-08-01 12:00:00,WX-1,支付成功,10.001,支出").toByteArray(),
        )
        assertFalse(fractional.canImport)
        assertEquals(1, fractional.invalidRowCount)

        val foreign = StatementFileParser.parse(
            "bank.csv",
            ("#招商银行账户明细\n交易日期,交易摘要,借贷标志,交易金额,币种,流水号\n" +
                "2026-08-04,网银支付,借,100.00,USD,BANK-1").toByteArray(),
        )
        assertFalse(foreign.canImport)
        assertTrue(StatementPreviewIssue.UNSUPPORTED_CURRENCY in foreign.issues)
    }

    @Test
    fun unknownTableAndEmptyFileAreRejected() {
        val unknown = StatementFileParser.parse("unknown.csv", "a,b\n1,2".toByteArray())
        assertFalse(unknown.canImport)
        assertEquals(listOf(StatementPreviewIssue.HEADER_NOT_FOUND), unknown.issues)
        val empty = StatementFileParser.parse("empty.csv", byteArrayOf())
        assertEquals(listOf(StatementPreviewIssue.EMPTY_FILE), empty.issues)
    }

    @Test
    fun genericDateAmountTableNeverPretendsToBeBankStatement() {
        val generic = StatementFileParser.parse(
            "manual.csv",
            "交易时间,金额(元)\n2026-08-01 10:00:00,10.00".toByteArray(),
        )
        assertFalse(generic.canImport)
        assertEquals(
            listOf(StatementPreviewIssue.UNRECOGNIZED_STATEMENT_SOURCE),
            generic.issues,
        )
    }

    @Test
    fun extraOrUnnamedColumnsAndDuplicateHeadersBlockImport() {
        val extra = StatementFileParser.parse(
            "wechat.csv",
            ("#微信支付账单明细\n交易时间,交易单号,当前状态,金额(元),收/支\n" +
                "2026-08-01 12:00:00,WX-1,支付成功,10.00,支出,未声明值").toByteArray(),
        )
        assertFalse(extra.canImport)
        assertEquals(1, extra.invalidRowCount)

        val unnamed = StatementFileParser.parse(
            "wechat.csv",
            ("#微信支付账单明细\n交易时间,交易单号,当前状态,金额(元),收/支,\n" +
                "2026-08-01 12:00:00,WX-1,支付成功,10.00,支出,未命名值").toByteArray(),
        )
        assertFalse(unnamed.canImport)
        assertEquals(1, unnamed.invalidRowCount)

        val duplicateHeader = StatementFileParser.parse(
            "wechat.csv",
            ("#微信支付账单明细\n交易时间,交易单号,当前状态,金额(元),收/支,收/支\n" +
                "2026-08-01 12:00:00,WX-1,支付成功,10.00,支出,支出").toByteArray(),
        )
        assertEquals(listOf(StatementPreviewIssue.MALFORMED_TABLE), duplicateHeader.issues)
    }

    @Test
    fun malformedQuotePlacementAndInvalidEncodingFailClosed() {
        listOf(
            "支付\"成\"功",
            "\"支付成功\"x",
        ).forEach { status ->
            val malformed = StatementFileParser.parse(
                "wechat.csv",
                ("#微信支付账单明细\n交易时间,交易单号,当前状态,金额(元),收/支\n" +
                    "2026-08-01 12:00:00,WX-1,$status,10.00,支出").toByteArray(),
            )
            assertEquals(listOf(StatementPreviewIssue.MALFORMED_TABLE), malformed.issues)
        }
        val invalidEncoding = StatementFileParser.parse(
            "broken.csv",
            byteArrayOf(0x81.toByte(), 0x30, 0x81.toByte()),
        )
        assertEquals(listOf(StatementPreviewIssue.UNSUPPORTED_FORMAT), invalidEncoding.issues)
    }

    @Test
    fun smsServiceNumberMustMatchEntireSender() {
        fun summary(sender: String) = RawSourceCoverageSummary(
            source = ObservationSource.SMS,
            userHandle = 0,
            sourceNamespace = sender,
            observationCount = 1,
            firstSeenAtMs = 1,
            lastSeenAtMs = 2,
            transactionEvidenceCount = 0,
            debtEvidenceCount = 0,
        )
        assertEquals("招商银行", SourceCoverageAudit.build(listOf(summary("+8695555")), emptyList()).single().label)
        assertTrue(SourceCoverageAudit.build(listOf(summary("fake95555")), emptyList()).isEmpty())
        assertTrue(SourceCoverageAudit.build(listOf(summary("195555")), emptyList()).isEmpty())
    }

    @Test
    fun nonBlankUnknownRowBlocksImportButKnownFooterIsCounted() {
        val withUnknown = StatementFileParser.parse(
            "wechat.csv",
            ("#微信支付账单明细\n交易时间,交易单号,当前状态,金额(元),收/支\n" +
                "2026-08-01 12:00:00,WX-1,支付成功,10.00,支出\n" +
                "无法识别的尾行,,,,").toByteArray(),
        )
        assertFalse(withUnknown.canImport)
        assertEquals(1, withUnknown.invalidRowCount)

        val withFooter = StatementFileParser.parse(
            "wechat.csv",
            ("#微信支付账单明细\n交易时间,交易单号,当前状态,金额(元),收/支\n" +
                "2026-08-01 12:00:00,WX-1,支付成功,10.00,支出\n" +
                "交易合计：1笔,,,,").toByteArray(),
        )
        assertTrue(withFooter.canImport)
        assertEquals(1, withFooter.ignoredFooterRowCount)
        assertEquals(2, withFooter.rawRowCount)
    }

    @Test
    fun multipleSheetsAndMultipleZipCandidatesAreRejected() {
        val multiSheet = xlsx(
            sharedStrings = emptyList(),
            sheetXml = "<worksheet><sheetData/></worksheet>",
            secondSheetXml = "<worksheet><sheetData/></worksheet>",
        )
        assertEquals(
            listOf(StatementPreviewIssue.AMBIGUOUS_CONTAINER),
            StatementFileParser.parse("multi.xlsx", multiSheet).issues,
        )
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            listOf("first.csv", "second.csv").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("交易时间,金额(元)".toByteArray())
                zip.closeEntry()
            }
        }
        assertEquals(
            listOf(StatementPreviewIssue.AMBIGUOUS_CONTAINER),
            StatementFileParser.parse("multi.zip", output.toByteArray()).issues,
        )
    }

    @Test
    fun parsesXlsxSharedStringsAndNumericExcelDate() {
        val bytes = xlsx(
            sharedStrings = listOf("微信支付账单明细", "交易时间", "交易单号", "当前状态", "金额(元)", "收/支", "WX-1", "支付成功", "支出"),
            sheetXml = """
                <worksheet><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                  <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="s"><v>2</v></c><c r="C2" t="s"><v>3</v></c><c r="D2" t="s"><v>4</v></c><c r="E2" t="s"><v>5</v></c></row>
                  <row r="3"><c r="A3"><v>46235.5</v></c><c r="B3" t="s"><v>6</v></c><c r="C3" t="s"><v>7</v></c><c r="D3"><v>8.88</v></c><c r="E3" t="s"><v>8</v></c></row>
                </sheetData></worksheet>
            """.trimIndent(),
        )
        val preview = StatementFileParser.parse("wechat.xlsx", bytes)
        assertTrue(preview.canImport)
        assertEquals(StatementFormat.WECHAT_XLSX, preview.format)
        assertEquals(888L, preview.rows.single().amountCents)
    }

    @Test
    fun xlsxWithDoctypeOrEntityIsRejected() {
        val dangerous = xlsx(
            sharedStrings = emptyList(),
            sheetXml = "<!DOCTYPE x [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]>" +
                "<worksheet><sheetData><row><c r=\"A1\" t=\"inlineStr\"><is><t>&leak;</t></is></c></row></sheetData></worksheet>",
        )
        assertEquals(
            listOf(StatementPreviewIssue.MALFORMED_TABLE),
            StatementFileParser.parse("dangerous.xlsx", dangerous).issues,
        )
    }

    @Test
    fun coverageAuditShowsObservationGapAndImportedPeriodSeparately() {
        val raw = listOf(
            RawSourceCoverageSummary(
                source = ObservationSource.NOTIFICATION,
                userHandle = 0,
                sourceNamespace = "com.eg.android.AlipayGphone",
                observationCount = 5,
                firstSeenAtMs = 100,
                lastSeenAtMs = 500,
                transactionEvidenceCount = 2,
                debtEvidenceCount = 1,
            )
        )
        val without = SourceCoverageAudit.build(raw, emptyList()).single()
        assertNull(without.statementObservedRowFromMs)
        assertTrue(without.gapLabel.contains("缺少"))
        val imported = StatementImportSummary(
            id = 1,
            displayName = "bill.csv",
            sourceKind = StatementSourceKind.ALIPAY,
            format = StatementFormat.ALIPAY_CSV,
            authority = StatementAuthority.FORMAT_RECOGNIZED_UNVERIFIED,
            status = StatementImportStatus.IMPORTED_UNVERIFIED,
            observedRowFromMs = 200,
            observedRowToMs = 400,
            validRowCount = 3,
            invalidRowCount = 0,
            ignoredFooterRowCount = 0,
            duplicateRowCount = 0,
            linkedRowCount = 3,
            importedAtMs = 600,
        )
        val with = SourceCoverageAudit.build(raw, listOf(imported))
        assertEquals(2, with.size)
        val observed = with.first { it.channel == "通知观察" }
        assertNull(observed.statementObservedRowFromMs)
        assertTrue(observed.gapLabel.contains("归属"))
        val importedItem = with.first { it.channel == "文件导入" }
        assertEquals(200L, importedItem.statementObservedRowFromMs)
        assertEquals(400L, importedItem.statementObservedRowToMs)
        assertTrue(importedItem.gapLabel.contains("归属"))
    }

    private fun xlsx(
        sharedStrings: List<String>,
        sheetXml: String,
        secondSheetXml: String? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("xl/workbook.xml", "<workbook/>")
            entry(
                "xl/sharedStrings.xml",
                "<sst>${sharedStrings.joinToString("") { "<si><t>$it</t></si>" }}</sst>",
            )
            entry("xl/worksheets/sheet1.xml", sheetXml)
            secondSheetXml?.let { entry("xl/worksheets/sheet2.xml", it) }
        }
        return output.toByteArray()
    }
}
