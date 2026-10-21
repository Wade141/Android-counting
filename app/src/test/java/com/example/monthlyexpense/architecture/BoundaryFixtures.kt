package com.example.monthlyexpense.architecture

import com.example.monthlyexpense.ExpenseUiState as AliasedRoot

internal open class AliasedRootReference(val state: AliasedRoot)
internal class InheritedRootReference : AliasedRootReference(com.example.monthlyexpense.ExpenseUiState())
internal class QualifiedMethodReference {
    fun create(): Any = com.example.monthlyexpense.ExpenseUiState()
}
internal abstract class GenericRootReference {
    abstract val values: List<AliasedRoot>
}
internal class StringOnlyReference {
    // com.example.monthlyexpense.ExpenseUiState is deliberately only text here.
    val description = "com/example/monthlyexpense/ExpenseUiState"
}
