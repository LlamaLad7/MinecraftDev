/*
 * Minecraft Development for IntelliJ
 *
 * https://mcdev.io/
 *
 * Copyright (C) 2026 minecraft-dev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, version 3.0 only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.demonwav.mcdev.platform.mixin.inspection.injector

import com.demonwav.mcdev.platform.mixin.util.TypeKind
import com.demonwav.mcdev.util.allEqual
import com.demonwav.mcdev.util.descriptor
import com.demonwav.mcdev.util.mapReduceFallible
import com.demonwav.mcdev.util.normalize
import com.demonwav.mcdev.util.reduceFallible
import com.intellij.psi.GenericsUtil
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.parentOfType
import org.objectweb.asm.Type

sealed interface SignatureSuggestion {
    val params: List<Param>?
    val returnType: PsiType
    val intLikeTypes: Set<MethodSignature.TypePosition>
    val coerceReturnType: Boolean

    data class Param(val name: String?, val type: PsiType, val coerce: Boolean = false)
}

data class SuggestedReturnType(
    override val returnType: PsiType,
    val returnTypeIsIntLike: Boolean,
    override val coerceReturnType: Boolean = false,
) : SignatureSuggestion {
    override val intLikeTypes = if (returnTypeIsIntLike) setOf(MethodSignature.TypePosition.Return) else emptySet()
    override val params: Nothing? get() = null

    private fun intersectCoerce(other: SuggestedReturnType, manager: PsiManager): SuggestedReturnType? {
        val (returnType, returnTypeIsIntLike, coerceReturnType) = getSupertype(
            manager,
            this.returnType,
            other.returnType,
            this.returnTypeIsIntLike,
            other.returnTypeIsIntLike,
        ) ?: return null

        return SuggestedReturnType(returnType, returnTypeIsIntLike, coerceReturnType)
    }

    companion object {
        fun exact(signature: MethodSignature): SuggestedReturnType {
            return SuggestedReturnType(
                signature.returnType,
                MethodSignature.TypePosition.Return in signature.intLikeTypes,
            )
        }

        fun forParams(params: PsiParameterList, signatures: List<MethodSignatures>): SuggestedReturnType? {
            val validSignatures = signatures.map { expected ->
                expected.options.filter { it.matchesParams(params) }.ifEmpty { return null }
            }

            var returnTypeOptions: MutableSet<PsiType>? = null
            outer@ for (options in validSignatures) {
                val ourReturnTypeOptions = options.mapTo(mutableSetOf()) {
                    it.forcedReturnType(params)?.normalize() ?: continue@outer
                }
                if (returnTypeOptions == null) {
                    returnTypeOptions = ourReturnTypeOptions
                } else {
                    returnTypeOptions.retainAll(ourReturnTypeOptions)
                }
            }

            if (returnTypeOptions == null) {
                // Can choose whatever we like
                val optionsByKind = validSignatures.asSequence()
                    .flatMap { options ->
                        options.asSequence()
                            .filter { it.forcedReturnType(params) == null }
                            .distinctBy { TypeKind.of(it.returnType) }
                    }
                    .groupBy { TypeKind.of(it.returnType) }
                val chosen =
                    optionsByKind.entries.firstOrNull { it.value.size == signatures.size }?.value ?: return null
                return intersectCoerce(params, chosen.map { exact(it) })
            }

            outer@ for (returnType in returnTypeOptions) {
                val choices = validSignatures.map { signatures ->
                    signatures.firstNotNullOfOrNull { signature ->
                        val forcedReturnType = signature.forcedReturnType(params)
                        if (forcedReturnType != null) {
                            if (forcedReturnType.normalize() == returnType) {
                                SuggestedReturnType(
                                    forcedReturnType,
                                    returnTypeIsIntLike = false,
                                )
                            } else {
                                null
                            }
                        } else {
                            // Here, the signature definitely allows coercion, or it would force a return type.
                            // Additionally, if the signature's return type is "int-like", we must have free choice
                            // since none of its parameters forced the return type
                            if (signature.matchesReturnType(returnType, hasCoerce = true)) {
                                SuggestedReturnType(
                                    signature.returnType,
                                    returnTypeIsIntLike = MethodSignature.TypePosition.Return in signature.intLikeTypes,
                                )
                            } else {
                                null
                            }
                        }
                    } ?: continue@outer
                }
                return intersectCoerce(params, choices)
            }

            return null
        }

        private fun MethodSignature.forcedReturnType(params: PsiParameterList): PsiType? {
            if (MethodSignature.TypePosition.Return in intLikeTypes) {
                val params = params.parameters
                return intLikeTypes.firstNotNullOfOrNull {
                    when (it) {
                        is MethodSignature.TypePosition.Param -> params.getOrNull(it.index)?.type
                        is MethodSignature.TypePosition.Return -> null
                    }
                }
            }
            if (TypeKind.of(returnType) == TypeKind.INT_LIKE && returnType != PsiTypes.intType()) {
                return returnType
            }

            return returnType.takeUnless { allowCoerceRequired }
        }

        private fun intersectCoerce(
            context: PsiElement,
            types: List<SuggestedReturnType>,
        ): SuggestedReturnType? {
            if (!types.asSequence().map { TypeKind.of(it.returnType) }.allEqual()) {
                return null
            }
            val intLikeAssignment = types
                .filter { !it.returnTypeIsIntLike && TypeKind.of(it.returnType) == TypeKind.INT_LIKE }
                .let { types ->
                    if (types.isEmpty()) {
                        null
                    } else {
                        types.asSequence()
                            .map { it.returnType }
                            .reduceFallible { acc, it -> mergeIntTypes(acc, it)?.first }
                            ?: return null
                    }
                }

            val transformedTypes = if (intLikeAssignment == null) {
                types.asSequence()
            } else {
                types.asSequence()
                    .map { type ->
                        if (!type.returnTypeIsIntLike) {
                            type
                        } else {
                            type.copy(returnType = intLikeAssignment, returnTypeIsIntLike = false)
                        }
                    }
            }

            val manager = PsiManager.getInstance(context.project)
            return transformedTypes.reduceFallible { acc, it -> acc.intersectCoerce(it, manager) }
        }
    }
}

data class SuggestedSignature(
    override val params: List<SignatureSuggestion.Param>,
    override val returnType: PsiType,
    override val intLikeTypes: Set<MethodSignature.TypePosition> = emptySet(),
    override val coerceReturnType: Boolean = false,
) : SignatureSuggestion {
    private fun intersectCoerce(other: SuggestedSignature, manager: PsiManager): SuggestedSignature? {
        val intLikeTypes = mutableSetOf<MethodSignature.TypePosition>()

        val (returnType, returnTypeIsIntLike, coerceReturnType) = getSupertype(
            manager,
            this.returnType,
            other.returnType,
            MethodSignature.TypePosition.Return in this.intLikeTypes,
            MethodSignature.TypePosition.Return in other.intLikeTypes,
        ) ?: return null

        if (returnTypeIsIntLike) {
            intLikeTypes.add(MethodSignature.TypePosition.Return)
        }

        return SuggestedSignature(
            params.withIndex().zip(other.params) { (index, a), b ->
                val pos = MethodSignature.TypePosition.Param(index)
                val (type, isIntLike, coerce) = getSupertype(
                    manager,
                    a.type,
                    b.type,
                    pos in this.intLikeTypes,
                    pos in other.intLikeTypes,
                ) ?: return null
                if (isIntLike) {
                    intLikeTypes.add(pos)
                }
                val name = if (a.name == b.name) a.name else null
                SignatureSuggestion.Param(name, type, a.coerce || b.coerce || coerce)
            },
            returnType,
            intLikeTypes,
            this.coerceReturnType || other.coerceReturnType || coerceReturnType,
        )
    }

    private fun getType(pos: MethodSignature.TypePosition) = when (pos) {
        is MethodSignature.TypePosition.Param -> params[pos.index].type
        MethodSignature.TypePosition.Return -> returnType
    }

    companion object {
        fun exact(signature: MethodSignature, takeTrailing: Int = 0): SuggestedSignature {
            return SuggestedSignature(
                (signature.requiredParams + signature.trailingParams.take(takeTrailing)).map {
                    SignatureSuggestion.Param(
                        it.name,
                        it.type,
                    )
                },
                signature.returnType,
                signature.intLikeTypes,
            )
        }

        fun modifierNoCoerce(annotation: PsiAnnotation, signatures: List<ModifierSignatures>): SuggestedSignature? {
            val parameterOptions = signatures.map { it.paramOptions }
            val psiManager = PsiManager.getInstance(annotation.project)

            val optionsByType = parameterOptions.asSequence()
                .flatMap { it.entries }
                .groupBy({ it.key }, { it.value })

            val existingReturnType = annotation.parentOfType<PsiMethod>()?.returnType
            val forExistingReturnType = existingReturnType?.let { optionsByType[Type.getType(it.descriptor)] }

            val chosenParams = forExistingReturnType?.takeIf { it.size == parameterOptions.size }
                ?: optionsByType.values.firstOrNull { it.size == parameterOptions.size } ?: return null

            val name = chosenParams.asSequence().map { it.name }.distinct().singleOrNull() ?: "original"
            val type = chosenParams.asSequence().map { it.type }
                .reduce { a, b -> GenericsUtil.getLeastUpperBound(a, b, psiManager) ?: a }

            return SuggestedSignature(listOf(SignatureSuggestion.Param(name, type)), type)
        }

        fun operationWrapper(
            annotation: PsiAnnotation,
            signatures: List<OperationWrapperSignatures>,
        ): SuggestedSignature? {
            return intersectCoerce(annotation, signatures.map { exact(it.signature) })
        }

        fun inject(annotation: PsiAnnotation, signatures: List<InjectSignatures>): SuggestedSignature? {
            val localsToUse =
                coerciblePrefixLength(signatures.asSequence().map { sig -> sig.locals.asSequence().map { it.type } })

            val longForm = intersectCoerce(
                annotation,
                signatures.map { exact(it.longSignature, takeTrailing = localsToUse) },
            )

            return longForm ?: intersectCoerce(
                annotation,
                signatures.map { exact(it.shortSignature ?: it.longSignature) },
            )
        }

        fun general(annotation: PsiAnnotation, signatures: List<GeneralSignatures>): SuggestedSignature? {
            val numParams = signatures.maxOf { it.params.size }

            val candidatesByReturnKind = signatures.asSequence()
                .flatMap { sig ->
                    sig.returnTypeOptions.keys.asSequence().map { it to sig.specificSignature(it) }
                }
                .groupBy({ it.first }, { it.second })
                .values

            for (candidates in candidatesByReturnKind) {
                if (candidates.size < signatures.size) {
                    // Not viable
                    continue
                }

                val intersected = intersectCoerce(
                    annotation,
                    candidates.map {
                        exact(it, takeTrailing = numParams - it.requiredParams.size)
                    },
                ) ?: continue

                val actuallyValid = candidates.all {
                    it.allowCoerceRequired || it.matches(intersected)
                }

                if (actuallyValid) {
                    return intersected
                }
            }

            return null
        }

        private fun intersectCoerce(
            context: PsiElement,
            signatures: List<SuggestedSignature>,
        ): SuggestedSignature? {
            if (signatures.asSequence().zipWithNext { a, b -> !kindsMatch(a, b) }.any { it }) {
                return null
            }
            val intLikeAssignments = decideIntLikeAssignments(signatures) ?: return null
            val transformedSignatures = signatures.zip(intLikeAssignments) { sig, assignment ->
                if (assignment == null) {
                    sig
                } else {
                    val params = sig.params.toMutableList()
                    var returnType = sig.returnType
                    for (pos in sig.intLikeTypes) {
                        when (pos) {
                            is MethodSignature.TypePosition.Param -> {
                                params[pos.index] = params[pos.index].copy(type = assignment)
                            }
                            MethodSignature.TypePosition.Return -> {
                                returnType = assignment
                            }
                        }
                    }
                    SuggestedSignature(params, returnType)
                }
            }
            val manager = PsiManager.getInstance(context.project)
            return transformedSignatures.asSequence()
                .reduceFallible { acc, it -> acc.intersectCoerce(it, manager) }
        }

        private fun decideIntLikeAssignments(signatures: List<SuggestedSignature>): List<PsiType?>? {
            val intLikeForcingsByPos = signatures.asSequence()
                .flatMap { sig ->
                    val positions = sig.params.indices.asSequence().map { MethodSignature.TypePosition.Param(it) } +
                        MethodSignature.TypePosition.Return
                    positions
                        .filter { TypeKind.of(sig.getType(it)) == TypeKind.INT_LIKE }
                        .filter { it !in sig.intLikeTypes }
                        .map { it to sig.getType(it) }
                }
                .groupingBy { it.first }
                .mapReduceFallible({ it.second }) { _, acc, it -> mergeIntTypes(acc, it)?.first }
                ?: return null

            val forcings = signatures
                .mapTo(mutableListOf()) { sig ->
                    sig.intLikeTypes
                        .mapNotNull { intLikeForcingsByPos[it] }
                        .ifEmpty { return@mapTo null }
                        .asSequence()
                        .reduceFallible { acc, it -> mergeIntTypes(acc, it)?.first }
                        ?: return null
                }

            val signaturesByIntLikePos = signatures.asSequence()
                .withIndex()
                .flatMap { (index, sig) ->
                    sig.intLikeTypes.asSequence().map { it to index }
                }
                .groupBy({ it.first }, { it.second })

            val visited = BooleanArray(signatures.size)

            fun linkDfs(start: Int): List<Int> {
                val result = mutableListOf<Int>()
                val stack = mutableListOf(start)

                while (stack.isNotEmpty()) {
                    val current = stack.removeLast()
                    result.add(current)
                    val linked = signatures[current].intLikeTypes.asSequence()
                        .flatMap { signaturesByIntLikePos.getValue(it) }
                    for (child in linked) {
                        if (!visited[child]) {
                            visited[child] = true
                            stack.add(child)
                        }
                    }
                }

                return result
            }

            for (i in visited.indices) {
                if (visited[i]) {
                    continue
                }
                visited[i] = true
                val group = linkDfs(i)
                val assignment = group
                    .mapNotNull { forcings[it] }
                    .ifEmpty { continue }
                    .asSequence()
                    .reduceFallible { acc, it -> mergeIntTypes(acc, it)?.first }
                    ?: return null
                for (member in group) {
                    forcings[member] = assignment
                }
            }

            return forcings
        }
    }
}

private fun kindsMatch(a: SuggestedSignature, b: SuggestedSignature) =
    TypeKind.of(a.returnType) == TypeKind.of(b.returnType)
        && a.params.size == b.params.size
        && a.params.indices.all { TypeKind.of(a.params[it].type) == TypeKind.of(b.params[it].type) }

private fun getSupertype(
    manager: PsiManager,
    a: PsiType,
    b: PsiType,
    aIsIntLike: Boolean,
    bIsIntLike: Boolean,
): TypeMergeResult? = when (TypeKind.of(a)) {
    TypeKind.OBJECT -> {
        TypeMergeResult(
            GenericsUtil.getLeastUpperBound(a, b, manager)!!,
            isIntLike = false,
            coerce = a.normalize() != b.normalize(),
        )
    }

    TypeKind.INT_LIKE -> {
        when {
            aIsIntLike && bIsIntLike -> TypeMergeResult(a, isIntLike = true, coerce = false)
            aIsIntLike || bIsIntLike -> error("Int-like type should have been forced")
            else -> mergeIntTypes(a, b)?.let { (type, coerce) ->
                TypeMergeResult(type, isIntLike = false, coerce = coerce)
            }
        }
    }

    else -> {
        require(a == b)
        TypeMergeResult(a, isIntLike = false, coerce = false)
    }
}

private data class TypeMergeResult(val type: PsiType, val isIntLike: Boolean, val coerce: Boolean)

private fun mergeIntTypes(a: PsiType, b: PsiType): Pair<PsiType, Boolean>? = when {
    a == b -> a to false
    a == PsiTypes.intType() -> b to true
    b == PsiTypes.intType() -> a to true
    else -> null
}

private fun coerciblePrefixLength(types: Sequence<Sequence<PsiType>>): Int {
    val iterators = types.map { it.iterator() }.toList()
    var i = 0

    while (true) {
        var kind: TypeKind? = null
        var forcedIntType: PsiType? = null

        for (iterator in iterators) {
            val candidate = if (iterator.hasNext()) iterator.next() else return i
            val candidateKind = TypeKind.of(candidate)

            when (kind) {
                null -> kind = candidateKind
                candidateKind -> {}
                else -> return i
            }

            if (candidateKind == TypeKind.INT_LIKE && candidate != PsiTypes.intType()) {
                when (forcedIntType) {
                    null -> forcedIntType = candidate
                    candidate -> {}
                    else -> return i
                }
            }
        }

        i++
    }
}
