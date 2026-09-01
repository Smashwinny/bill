package com.hulk.manualledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManualLedgerContractTest {
    @Test
    fun amountParsingIsExact() {
        assertEquals(1234L, ManualLedgerRepository.parseCents("12.34"))
        assertEquals(null, ManualLedgerRepository.parseCents("12.345"))
        assertEquals(null, ManualLedgerRepository.parseCents("0"))
    }

    @Test
    fun suishouCsvHandlesQuotedCommaAndIsDeterministic() {
        val csv = "日期,类型,金额,分类,账户,备注\n" +
            "2026-08-31 12:30:00,支出,16.00,餐饮,支付宝,\"午餐,两人\""
        val first = SuishouCsvParser.parse(csv)
        val second = SuishouCsvParser.parse(csv)
        assertEquals(1, first.rows.size)
        assertEquals("午餐,两人", first.rows.single().note)
        assertEquals(first.rows.single().stableId, second.rows.single().stableId)
    }

    @Test
    fun suishouCsvAcceptsBomCurrencyAndThousandsSeparator() {
        val csv = "\uFEFF时间,收支类型,交易金额,项目,资金账户\n" +
            "2026/8/31 9:08,收入,\"￥1,234.50\",工资,银行卡"
        val result = SuishouCsvParser.parse(csv)
        assertEquals(0, result.rejectedRows)
        assertEquals("1234.50", result.rows.single().amountText)
        assertEquals(ManualTransactionType.INCOME, result.rows.single().type)
        assertEquals("工资", result.rows.single().category)
    }

    @Test
    fun suishouCsvSupportsSplitAmountsSubcategoryTransferAccountAndMultilineNotes() {
        val csv = "日期,交易类型,支出金额,收入金额,一级分类,二级分类,账户1,账户2,备注\n" +
            "2026-08-30,支出,25.60,,食品酒水,早餐,微信,,\"第一行\n第二行\"\n" +
            "2026-08-31,转账,100.00,,资金往来,账户互转,银行卡,支付宝,调余额"
        val result = SuishouCsvParser.parse(csv)
        assertEquals(0, result.rejectedRows)
        assertEquals(2, result.rows.size)
        assertEquals("食品酒水 › 早餐", result.rows[0].category)
        assertEquals("第一行\n第二行", result.rows[0].note)
        assertEquals(ManualTransactionType.TRANSFER, result.rows[1].type)
        assertEquals("支付宝", result.rows[1].targetAccount)
    }

    @Test
    fun suishouCsvFallsBackToSplitAmountWhenGenericAmountIsBlank() {
        val csv = "日期,类型,金额,支出金额,收入金额,分类,账户\n" +
            "2026-08-30,支出,,25.60,,餐饮,微信\n" +
            "2026-08-31,收入,,,88.00,红包,QQ"
        val result = SuishouCsvParser.parse(csv)
        assertEquals(0, result.rejectedRows)
        assertEquals(listOf("25.60", "88.00"), result.rows.map { it.amountText })
        assertEquals(ManualTransactionType.INCOME, result.rows[1].type)
    }

    @Test
    fun suishouCsvRejectsUnknownHistoricalDateInsteadOfUsingToday() {
        val result = SuishouCsvParser.parse("日期,类型,金额,分类,账户\n不是日期,支出,10.00,餐饮,现金")
        assertTrue(result.rows.isEmpty())
        assertEquals(1, result.rejectedRows)
        assertTrue(result.rejectedReasons.single().contains("日期无法识别"))
    }

    @Test
    fun suishouCsvDetectsGb18030() {
        val csv = "日期,类型,金额,分类,账户\n2026-08-31,支出,16.00,餐饮,现金"
        val result = SuishouCsvParser.parse(csv.toByteArray(java.nio.charset.Charset.forName("GB18030")))
        assertEquals("GB18030", result.sourceEncoding)
        assertEquals("餐饮", result.rows.single().category)
    }

    @Test
    fun suishouXlsxReadsSharedStringsAndExcelDates() {
        val shared = listOf("日期", "类型", "金额", "分类", "账户", "支出", "16.00", "红包", "QQ", "收入", "88.00", "工资", "银行卡")
        val sharedXml = "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>"
        val styles = """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
            <cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c><c r="E1" t="s"><v>4</v></c></row>
            <row r="2"><c r="A2" s="1"><v>46265</v></c><c r="B2" t="s"><v>5</v></c><c r="C2" t="s"><v>6</v></c><c r="D2" t="s"><v>7</v></c><c r="E2" t="s"><v>8</v></c></row>
            </sheetData></worksheet>"""
        val incomeSheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c><c r="E1" t="s"><v>4</v></c></row>
            <row r="2"><c r="A2" s="1"><v>46266</v></c><c r="B2" t="s"><v>9</v></c><c r="C2" t="s"><v>10</v></c><c r="D2" t="s"><v>11</v></c><c r="E2" t="s"><v>12</v></c></row>
            </sheetData></worksheet>"""
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            mapOf(
                "[Content_Types].xml" to "<Types/>",
                "xl/sharedStrings.xml" to sharedXml,
                "xl/styles.xml" to styles,
                "xl/worksheets/sheet1.xml" to sheet,
                "xl/worksheets/sheet2.xml" to incomeSheet,
            ).forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        val result = SuishouImportParser.parse(output.toByteArray())
        assertEquals("XLSX", result.sourceEncoding)
        assertEquals(2, result.rows.size)
        assertEquals(listOf(ManualTransactionType.EXPENSE, ManualTransactionType.INCOME), result.rows.map { it.type })
        assertEquals(listOf("16.00", "88.00"), result.rows.map { it.amountText })
        assertEquals("红包", result.rows.first().category)
        assertEquals("QQ", result.rows.first().account)
    }

    @Test
    fun migrationEnvelopeCarriesStableSchemaAndNoNetworkFields() {
        val row = ManualTransactionEntity(
            id = "stable-id",
            type = ManualTransactionType.EXPENSE,
            amountCents = 1600,
            category = "餐饮",
            account = "现金",
            targetAccount = null,
            occurredAtMs = 1,
            note = "测试",
            createdAtMs = 1,
            updatedAtMs = 1,
        )
        val exported = ManualLedgerMigrationCodec.exportJson(listOf(row))
        assertTrue(exported.contains("\"schema\":\"manual-ledger-v1\""))
        assertTrue(exported.contains("\"id\":\"stable-id\""))
        assertFalse(exported.contains("endpoint"))
        assertFalse(exported.contains("token"))
    }

    @Test
    fun insightsProjectMonthAndCompareWithoutFloatingPointDrift() {
        assertEquals(31000L, LedgerInsights.projectedExpense(10000, 10, 31))
        assertEquals(25, LedgerInsights.monthChangePercent(12500, 10000))
        assertEquals(-25, LedgerInsights.monthChangePercent(7500, 10000))
        assertEquals(null, LedgerInsights.monthChangePercent(7500, 0))
    }

    @Test
    fun categoryCatalogGuidesAliasesButKeepsGenuineCustomCategories() {
        assertEquals("宠物", CategoryCatalog.normalize(ManualTransactionType.EXPENSE, "小猫"))
        assertEquals("饮食", CategoryCatalog.normalize(ManualTransactionType.EXPENSE, "外卖"))
        assertEquals("摄影", CategoryCatalog.normalize(ManualTransactionType.EXPENSE, "摄影"))
        assertTrue("宠物" in CategoryCatalog.defaults(ManualTransactionType.EXPENSE))
        assertEquals("饮食 › 餐饮", CategoryCatalog.defaultPath(ManualTransactionType.EXPENSE))
        assertEquals(listOf("餐饮", "早餐", "午餐", "晚餐", "外卖", "买菜", "零食", "饮料"),
            CategoryCatalog.hierarchyOptions(ManualTransactionType.EXPENSE).getValue("饮食"))
        assertEquals(
            mapOf("其他" to listOf("API", "租服务器"), "创业 001" to listOf("域名")),
            CategoryCatalog.observedHierarchy(listOf(
                "其他 › 租服务器", "其他 › API", "其他 › API", "创业 001 › 域名", "餐饮",
            )),
        )
    }

    @Test
    fun insightsFindPeakTimeAndWeekendSpendingShare() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun expense(local: LocalDateTime, cents: Long) = ManualTransactionEntity(
            id = local.toString(), type = ManualTransactionType.EXPENSE, amountCents = cents,
            category = "餐饮", account = "现金", targetAccount = null,
            occurredAtMs = local.atZone(zone).toInstant().toEpochMilli(), note = null,
            createdAtMs = 1, updatedAtMs = 1,
        )
        val rows = listOf(
            expense(LocalDate.of(2026, 8, 29).atTime(19, 0), 3000),
            expense(LocalDate.of(2026, 8, 30).atTime(20, 0), 1000),
            expense(LocalDate.of(2026, 8, 31).atTime(8, 0), 6000),
        )
        assertEquals("晚间 18–23点", LedgerInsights.peakTime(rows, zone)?.label)
        assertEquals(40, LedgerInsights.weekendSharePercent(rows, zone))
        val daily = LedgerInsights.dailySpending(rows, java.time.YearMonth.of(2026, 8), zone)
        assertEquals(31, daily.size)
        assertEquals(3000L, daily.first { it.dayOfMonth == 29 }.amountCents)
        assertEquals(6000L, daily.first { it.dayOfMonth == 31 }.amountCents)
        assertEquals(0L, daily.first { it.dayOfMonth == 1 }.amountCents)
    }

    @Test
    fun changingTransactionDatePreservesLocalTimeOfDay() {
        val zone = ZoneId.systemDefault()
        val original = LocalDateTime.of(2026, 8, 31, 7, 46, 12).atZone(zone).toInstant().toEpochMilli()
        val changed = changeLocalDate(original, 2026, 7, 15)
        val local = java.time.Instant.ofEpochMilli(changed).atZone(zone)
        assertEquals(2026, local.year)
        assertEquals(7, local.monthValue)
        assertEquals(15, local.dayOfMonth)
        assertEquals(7, local.hour)
        assertEquals(46, local.minute)
        assertEquals(12, local.second)
    }

    @Test
    fun syncButtonExplainsOfflineQueueAndOnlineWork() {
        assertEquals("当前无网络，3 条流水已排队，联网后自动同步", syncStartMessage(3, online = false))
        assertEquals("当前无网络，已排队等待联网后检查云端更新", syncStartMessage(0, online = false))
        assertEquals("正在同步，待处理 3 条…", syncStartMessage(3, online = true))
        assertEquals("正在检查云端更新…", syncStartMessage(0, online = true))
    }
}
