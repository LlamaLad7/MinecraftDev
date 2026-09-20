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
import com.demonwav.mcdev.platform.mixin.util.checkCoerce
import com.demonwav.mcdev.util.Parameter
import com.demonwav.mcdev.util.allEqual
import com.demonwav.mcdev.util.normalize
import com.demonwav.mcdev.util.sharedPrefixLength
import com.intellij.psi.GenericsUtil
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes

data class SuggestedSignature(
    val params: List<Param>,
    val returnType: PsiType,
    val intLikeTypes: Set<MethodSignature.TypePosition> = emptySet(),
    val coerceReturnType: Boolean = false,
) {
    data class Param(val name: String?, val type: PsiType, val coerce: Boolean = false)

    fun intersectCoerce(other: SuggestedSignature, manager: PsiManager): SuggestedSignature? {
        if (!kindsMatch(this, other)) {
            return null
        }
        val intLikeForcings = mutableSetOf<PsiType>()
        for (pos in this.intLikeTypes) {
            if (pos !in other.intLikeTypes) {
                intLikeForcings.add(other.getType(pos))
            }
        }
        for (pos in other.intLikeTypes) {
            if (pos !in this.intLikeTypes) {
                intLikeForcings.add(this.getType(pos))
            }
        }
        val intLikeAssignment = if (intLikeForcings.size > 1) PsiTypes.intType() else intLikeForcings.singleOrNull()

        val intLikeTypes = mutableSetOf<MethodSignature.TypePosition>()

        val (returnType, returnTypeIsIntLike, coerceReturnType) = getSupertype(
            manager,
            this.returnType,
            other.returnType,
            MethodSignature.TypePosition.Return in this.intLikeTypes,
            MethodSignature.TypePosition.Return in other.intLikeTypes,
            intLikeAssignment,
        )
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
                    intLikeAssignment,
                )
                if (isIntLike) {
                    intLikeTypes.add(pos)
                }
                val name = if (a.name == b.name) a.name else null
                Param(name, type, a.coerce || b.coerce || coerce)
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
                    Param(
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
            val chosenParams = optionsByType.values.firstOrNull { it.size == parameterOptions.size } ?: return null

            val name = chosenParams.asSequence().map { it.name }.distinct().singleOrNull() ?: "original"
            val type = chosenParams.asSequence().map { it.type }
                .reduce { a, b -> GenericsUtil.getLeastUpperBound(a, b, psiManager) ?: a }

            return SuggestedSignature(listOf(Param(name, type)), type)
        }

        fun operationWrapper(
            annotation: PsiAnnotation,
            signatures: List<OperationWrapperSignatures>,
        ): SuggestedSignature? {
            return intersectCoerce(annotation, signatures.asSequence().map { exact(it.signature) })
        }

        fun inject(annotation: PsiAnnotation, signatures: List<InjectSignatures>): SuggestedSignature? {
            if (!signatures.asSequence().map { it.params.kinds() }.allEqual()) {
                // Shape mismatch, use short form
                return intersectCoerce(
                    annotation,
                    signatures.asSequence().map { exact(it.shortSignature ?: it.longSignature) },
                )
            }

            val localsToUse = sharedPrefixLength(signatures.map { it.locals.kinds() })

            return intersectCoerce(
                annotation,
                signatures.asSequence()
                    .map { exact(it.longSignature, takeTrailing = localsToUse) },
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
                ) ?: return null

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
            annotation: PsiAnnotation,
            signatures: Sequence<SuggestedSignature>,
        ): SuggestedSignature? {
            val manager = PsiManager.getInstance(annotation.project)
            return signatures.reduceOrNull<SuggestedSignature?, _> { acc, it -> acc?.intersectCoerce(it, manager) }
        }
    }
}

private fun kindsMatch(a: SuggestedSignature, b: SuggestedSignature) =
    TypeKind.of(a.returnType) == TypeKind.of(b.returnType)
        && a.params.size == b.params.size
        && a.params.indices.all { TypeKind.of(a.params[it].type) == TypeKind.of(b.params[it].type) }

private fun List<Parameter>.kinds() = map { TypeKind.of(it.type) }

private fun getSupertype(
    manager: PsiManager,
    a: PsiType,
    b: PsiType,
    aIsIntLike: Boolean,
    bIsIntLike: Boolean,
    intLikeAssignment: PsiType?,
): TypeMergeResult = when (TypeKind.of(a)) {
    TypeKind.OBJECT -> {
        TypeMergeResult(
            GenericsUtil.getLeastUpperBound(a, b, manager)!!,
            isIntLike = false,
            coerce = a.normalize() != b.normalize(),
        )
    }

    TypeKind.INT_LIKE -> {
        when {
            aIsIntLike -> TypeMergeResult(
                intLikeAssignment ?: b,
                isIntLike = intLikeAssignment == null && bIsIntLike,
                coerce = intLikeAssignment != null && !bIsIntLike && intLikeAssignment != b,
            )

            bIsIntLike -> TypeMergeResult(a, isIntLike = false, coerce = false)
            a == b -> TypeMergeResult(a, isIntLike = false, coerce = false)
            else -> TypeMergeResult(PsiTypes.intType(), isIntLike = false, coerce = true)
        }
    }

    else -> {
        require(a == b)
        TypeMergeResult(a, isIntLike = false, coerce = false)
    }
}

private data class TypeMergeResult(val type: PsiType, val isIntLike: Boolean, val coerce: Boolean)
