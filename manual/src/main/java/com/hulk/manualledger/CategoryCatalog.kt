package com.hulk.manualledger

object CategoryCatalog {
    const val HIERARCHY_SEPARATOR = " › "
    private val expenseHierarchy = linkedMapOf(
        "饮食" to listOf("餐饮", "早餐", "午餐", "晚餐", "外卖", "买菜", "零食", "饮料"),
        "出行" to listOf("公交", "地铁", "打车", "加油", "停车", "火车", "飞机", "住宿"),
        "购物" to listOf("日用品", "服饰", "数码", "家电", "家居", "网购"),
        "居家" to listOf("房租", "水电煤", "物业", "维修", "生活服务"),
        "宠物" to listOf("宠物食品", "宠物用品", "宠物医疗", "猫咪", "狗狗"),
        "娱乐" to listOf("游戏", "电影", "聚会", "运动", "影音", "休闲"),
        "医疗" to listOf("药品", "门诊", "住院", "体检", "保健"),
        "学习" to listOf("书籍", "课程", "培训", "文具", "考试"),
        "旅行" to listOf("交通票务", "酒店", "景点", "旅行购物"),
        "人情" to listOf("红包", "礼物", "礼金", "请客"),
        "通讯" to listOf("话费", "宽带", "流量", "邮寄"),
        "其他" to listOf("未分类", "其他支出"),
    )
    private val defaults = mapOf(
        ManualTransactionType.EXPENSE to expenseHierarchy.keys.toList(),
        ManualTransactionType.INCOME to listOf("工资", "奖金", "兼职", "理财", "报销", "退款", "礼金", "其他"),
        ManualTransactionType.TRANSFER to listOf("账户互转", "还款", "借出", "收回", "其他"),
    )

    private val expenseAliases = mapOf(
        "食品酒水" to "饮食", "餐饮" to "饮食", "早餐" to "饮食", "午餐" to "饮食", "晚餐" to "饮食", "外卖" to "饮食", "零食" to "饮食", "奶茶" to "饮食",
        "交通" to "出行", "公交" to "出行", "地铁" to "出行", "打车" to "出行", "出租车" to "出行", "加油" to "出行", "停车" to "出行",
        "衣服" to "购物", "服饰" to "购物", "网购" to "购物", "日用品" to "购物",
        "房租" to "居家", "水电" to "居家", "物业" to "居家", "家居" to "居家",
        "小猫" to "宠物", "猫咪" to "宠物", "猫" to "宠物", "狗狗" to "宠物", "狗" to "宠物", "宠物支出" to "宠物",
        "药品" to "医疗", "看病" to "医疗", "医院" to "医疗",
        "书籍" to "学习", "课程" to "学习", "培训" to "学习",
        "话费" to "通讯", "宽带" to "通讯", "流量" to "通讯",
        "红包" to "人情", "礼物" to "人情", "礼金支出" to "人情",
    )

    fun defaults(type: ManualTransactionType): List<String> = defaults.getValue(type)

    fun hierarchyOptions(type: ManualTransactionType): Map<String, List<String>> = when (type) {
        ManualTransactionType.EXPENSE -> expenseHierarchy
        else -> defaults(type).associateWith { listOf(it) }
    }

    fun defaultPath(type: ManualTransactionType): String {
        val primary = defaults(type).first()
        return sourcePath(primary, hierarchyOptions(type).getValue(primary).first())
    }

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
