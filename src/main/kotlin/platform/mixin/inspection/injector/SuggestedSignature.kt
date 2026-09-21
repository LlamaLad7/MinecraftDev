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
import com.demonwav.mcdev.util.descriptor
import com.demonwav.mcdev.util.normalize
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

    fun intersectCoerce(other: SuggestedReturnType, manager: PsiManager): SuggestedReturnType? {
        if (TypeKind.of(this.returnType) != TypeKind.of(other.returnType)) {
            return null
        }

        val aIntLikeAssignment = other.returnType.takeIf { this.returnTypeIsIntLike && !other.returnTypeIsIntLike }
        val bIntLikeAssignment = this.returnType.takeIf { other.returnTypeIsIntLike && !this.returnTypeIsIntLike }

        val (returnType, returnTypeIsIntLike, coerceReturnType) = getSupertype(
            manager,
            this.returnType,
            other.returnType,
            this.returnTypeIsIntLike,
            other.returnTypeIsIntLike,
            aIntLikeAssignment,
            bIntLikeAssignment,
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
                return intersectReturnTypes(params, chosen.asSequence().map { exact(it) })
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
                return intersectReturnTypes(params, choices.asSequence())
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

        private fun intersectReturnTypes(
            context: PsiElement,
            types: Sequence<SuggestedReturnType>,
        ): SuggestedReturnType? {
            val manager = PsiManager.getInstance(context.project)
            return types.reduceOrNull<SuggestedReturnType?, _> { acc, it -> acc?.intersectCoerce(it, manager) }
        }
    }
}

data class SuggestedSignature(
    override val params: List<SignatureSuggestion.Param>,
    override val returnType: PsiType,
    override val intLikeTypes: Set<MethodSignature.TypePosition> = emptySet(),
    override val coerceReturnType: Boolean = false,
) : SignatureSuggestion {
    fun intersectCoerce(other: SuggestedSignature, manager: PsiManager): SuggestedSignature? {
        if (!kindsMatch(this, other)) {
            return null
        }
        val aIntLikeAssignment = this.intLikeAssignment(other, onConflict = { return null })
        val bIntLikeAssignment = other.intLikeAssignment(this, onConflict = { return null })

        val intLikeTypes = mutableSetOf<MethodSignature.TypePosition>()

        val (returnType, returnTypeIsIntLike, coerceReturnType) = getSupertype(
            manager,
            this.returnType,
            other.returnType,
            MethodSignature.TypePosition.Return in this.intLikeTypes,
            MethodSignature.TypePosition.Return in other.intLikeTypes,
            aIntLikeAssignment,
            bIntLikeAssignment,
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
                    aIntLikeAssignment,
                    bIntLikeAssignment,
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

    private inline fun intLikeAssignment(other: SuggestedSignature, onConflict: () -> Nothing): PsiType? {
        var result: PsiType? = null
        for (pos in this.intLikeTypes) {
            if (pos !in other.intLikeTypes) {
                result = if (result == null) {
                    other.getType(pos)
                } else {
                    mergeIntTypes(result, other.getType(pos))?.first ?: onConflict()
                }
            }
        }
        return result
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
            return intersectCoerce(annotation, signatures.asSequence().map { exact(it.signature) })
        }

        fun inject(annotation: PsiAnnotation, signatures: List<InjectSignatures>): SuggestedSignature? {
            val localsToUse =
                coerciblePrefixLength(signatures.asSequence().map { sig -> sig.locals.asSequence().map { it.type } })

            val longForm = intersectCoerce(
                annotation,
                signatures.asSequence()
                    .map { exact(it.longSignature, takeTrailing = localsToUse) },
            )

            return longForm ?: intersectCoerce(
                annotation,
                signatures.asSequence().map { exact(it.shortSignature ?: it.longSignature) },
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
                    candidates.asSequence()
                        .map {
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
            signatures: Sequence<SuggestedSignature>,
        ): SuggestedSignature? {
            val manager = PsiManager.getInstance(context.project)
            return signatures.reduceOrNull<SuggestedSignature?, _> { acc, it -> acc?.intersectCoerce(it, manager) }
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
    aIntLikeAssignment: PsiType?,
    bIntLikeAssignment: PsiType?,
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
            aIsIntLike && bIsIntLike && (aIntLikeAssignment != null || bIntLikeAssignment != null) -> {
                if (aIntLikeAssignment == bIntLikeAssignment) {
                    TypeMergeResult(aIntLikeAssignment!!, isIntLike = false, coerce = false)
                } else {
                    null
                }
            }
            aIsIntLike && bIsIntLike -> TypeMergeResult(a, isIntLike = true, coerce = false)
            aIsIntLike -> TypeMergeResult(aIntLikeAssignment!!, isIntLike = false, coerce = b != aIntLikeAssignment)
            bIsIntLike -> TypeMergeResult(bIntLikeAssignment!!, isIntLike = false, coerce = a != bIntLikeAssignment)
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
