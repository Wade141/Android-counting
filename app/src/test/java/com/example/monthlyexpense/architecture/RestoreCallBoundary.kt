package com.example.monthlyexpense.architecture

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue

/** Fail closed unless the sole restore read is directly in the lambda supplied as runRestore's
 * refreshAfterRestore argument. Uses bytecode value provenance, not generated class-name prefixes.
 * Deliberately accepts the current direct suspend-lambda shape only; helpers/references require an
 * explicit rule extension and negative fixtures. Reflection/dynamic loading is outside this check. */
internal fun restoreCallViolations(classes: Map<String, ByteArray>, host: String): List<String> {
    val root = "com/example/monthlyexpense/"
    fun node(bytes: ByteArray) = ClassNode().also { ClassReader(bytes).accept(it, 0) }
    val nodes = classes.mapValues { node(it.value) }
    val hierarchy = nodes.toMutableMap()
    fun isRestoreType(name: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
        if (name == root + "refresh/api/RestoreRefreshPort") return true
        if (!seen.add(name)) return false
        val type = hierarchy[name] ?: RestoreCallMarker::class.java.getResourceAsStream("/$name.class")?.use {
            node(it.readBytes()).also { loaded -> hierarchy[name] = loaded }
        } ?: return false
        return (type.interfaces + listOfNotNull(type.superName)).any { isRestoreType(it, seen) }
    }
    data class Call(val owner: String, val method: MethodNode, val instruction: MethodInsnNode)
    val calls = nodes.flatMap { (owner, type) -> type.methods.flatMap { method ->
        method.instructions.filterIsInstance<MethodInsnNode>().map { Call(owner, method, it) }
    } }.filterNot { call ->
        // A compiled suspend function's ContinuationImpl resumes its enclosing method. This is
        // compiler dispatch, not another business caller. Match EnclosingMethod metadata exactly.
        val type = nodes.getValue(call.owner)
        type.superName == "kotlin/coroutines/jvm/internal/ContinuationImpl" && call.method.name == "invokeSuspend" &&
            type.outerClass == call.instruction.owner && type.outerMethod == call.instruction.name &&
            type.outerMethodDesc == call.instruction.desc
    }
    val reads = calls.filter { it.instruction.name == "refreshWithinRestore" && isRestoreType(it.instruction.owner) }
    val restores = calls.filter { it.instruction.owner == root + "data/ForegroundPersistenceCoordinator" &&
        it.instruction.name.substringBefore('-') == "runRestore" }
    val errors = mutableListOf<String>()
    if (reads.size != 1) errors += "Expected one restore read, found ${reads.size}: ${reads.map { it.owner }}"
    if (restores.size != 1) return errors + "Expected one runRestore call, found ${restores.size}"
    val restore = restores.single()
    if (restore.owner.substringBefore('$') != host) errors += "runRestore outside designated host: ${restore.owner}"
    val args = Type.getArgumentTypes(restore.instruction.desc)
    if (args.size != 4 || args[2].internalName != "kotlin/jvm/functions/Function1")
        return errors + "Unsupported runRestore signature: ${restore.instruction.desc}"
    // Preserve NEW origins through locals, DUP and CHECKCAST. The analyzer handles the full operand
    // stack and control-flow merges, so intervening constructors/labels cannot fool argument matching.
    val interpreter = object : SourceInterpreter(Opcodes.ASM9) {
        override fun copyOperation(insn: AbstractInsnNode, value: SourceValue): SourceValue = value
        override fun unaryOperation(insn: AbstractInsnNode, value: SourceValue): SourceValue? =
            if (insn.opcode == Opcodes.CHECKCAST) value else super.unaryOperation(insn, value)
    }
    val frame = try {
        Analyzer(interpreter).analyze(restore.owner, restore.method)[restore.method.instructions.indexOf(restore.instruction)]
    } catch (error: Exception) { return errors + "Cannot analyze restore arguments: ${error.message}" }
    if (frame == null) return errors + "Unreachable runRestore call"
    val sources = frame.getStack(frame.stackSize - args.size + 2).insns
    val allocation = sources.singleOrNull() as? TypeInsnNode
    if (allocation?.opcode != Opcodes.NEW) return errors + "Refresh argument is not a uniquely allocated callback"
    val callback = allocation.desc
    if (nodes[callback]?.superName != "kotlin/coroutines/jvm/internal/SuspendLambda")
        return errors + "Unsupported refresh callback: $callback"
    reads.filterNot { it.owner == callback && it.method.name == "invokeSuspend" }.forEach {
        errors += "Restore read outside refreshAfterRestore callback: ${it.owner}.${it.method.name}"
    }
    return errors
}

private object RestoreCallMarker
