package com.hulk.manualledger

object CategoryCatalog {
    const val HIERARCHY_SEPARATOR = " › "
    private val defaults = mapOf(
        ManualTransactionType.EXPENSE to listOf(
            "餐饮", "交通", "购物", "居家", "宠物", "娱乐", "医疗", "学习", "旅行", "人情", "通讯", "其他",
        ),
        ManualTransactionType.INCOME to listOf("工资", "奖金", "兼职", "理财", "报销", "退款", "礼金", "其他"),
        ManualTransactionType.TRANSFER to listOf("账户互转", "还款", "借出", "收回", "其他"),
    )

    private val expenseAliases = mapOf(
        "食品酒水" to "餐饮", "早餐" to "餐饮", "午餐" to "餐饮", "晚餐" to "餐饮", "外卖" to "餐饮", "零食" to "餐饮", "奶茶" to "餐饮",
        "公交" to "交通", "地铁" to "交通", "打车" to "交通", "出租车" to "交通", "加油" to "交通", "停车" to "交通",
        "衣服" to "购物", "服饰" to "购物", "网购" to "购物", "日用品" to "购物",
        "房租" to "居家", "水电" to "居家", "物业" to "居家", "家居" to "居家",
        "小猫" to "宠物", "猫咪" to "宠物", "猫" to "宠物", "狗狗" to "宠物", "狗" to "宠物", "宠物支出" to "宠物",
        "药品" to "医疗", "看病" to "医疗", "医院" to "医疗",
        "书籍" to "学习", "课程" to "学习", "培训" to "学习",
        "话费" to "通讯", "宽带" to "通讯", "流量" to "通讯",
        "红包" to "人情", "礼物" to "人情", "礼金支出" to "人情",
    )

    fun defaults(type: ManualTransactionType): List<String> = defaults.getValue(type)

    fun normalize(type: ManualTransactionType, raw: String): String {
        val hierarchy = hierarchy(raw)
        val value = (hierarchy.second ?: hierarchy.first).trim().take(40)
        if (value.isBlank()) return defaults(type).first()
        val exact = defaults(type).firstOrNull { it.equals(value, ignoreCase = true) }
        if (exact != null) return exact
        if (type != ManualTransactionType.EXPENSE) return value
        return expenseAliases[value] ?: expenseAliases[hierarchy.first] ?: value
    }

    fun hierarchy(raw: String): Pair<String, String?> {
        val fields = raw.split(HIERARCHY_SEPARATOR, limit = 2).map(String::trim)
        return fields.firstOrNull().orEmpty() to fields.getOrNull(1)?.ifBlank { null }
    }

    fun sourcePath(primary: String, secondary: String): String = when {
        secondary.isBlank() || secondary == primary -> primary.ifBlank { "未分类" }
        primary.isBlank() -> secondary
        else -> "$primary$HIERARCHY_SEPARATOR$secondary"
    }.take(80)

    fun options(type: ManualTransactionType, custom: Set<String>): List<String> =
        (defaults(type) + custom.map { normalize(type, it) }).distinct()
}
