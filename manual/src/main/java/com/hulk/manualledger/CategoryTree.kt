package com.hulk.manualledger

/** The persisted tree is the only source for selection, display and analytics. */
object CategoryTree {
    fun path(id: String?, categories: List<LedgerCategoryEntity>): String {
        val byId = categories.associateBy { it.id }
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var node = byId[id]
        while (node != null && seen.add(node.id)) {
            names += node.name
            node = byId[node.parentId]
        }
        return names.asReversed().joinToString(CategoryCatalog.HIERARCHY_SEPARATOR)
    }

    fun rootName(row: ManualTransactionEntity, categories: List<LedgerCategoryEntity>): String =
        path(row.categoryId, categories).ifBlank { row.category }
            .substringBefore(CategoryCatalog.HIERARCHY_SEPARATOR)

    fun selectable(type: ManualTransactionType, categories: List<LedgerCategoryEntity>): List<LedgerCategoryEntity> {
        val parents = categories.mapNotNull { it.parentId }.toSet()
        return categories.filter { it.type == type && it.id !in parents }.sortedBy { path(it.id, categories) }
    }
}
