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

import com.demonwav.mcdev.platform.mixin.util.IntLikeAnchorSolver
import com.demonwav.mcdev.platform.mixin.util.MixinConstants.Annotations.COERCE
import com.demonwav.mcdev.platform.mixin.util.TypeKind
import com.demonwav.mcdev.util.MutableSequencedSet
import com.demonwav.mcdev.util.SequencedSet
import com.demonwav.mcdev.util.allEqual
import com.demonwav.mcdev.util.descriptor
import com.demonwav.mcdev.util.emptySequencedSet
import com.demonwav.mcdev.util.interleaved
import com.demonwav.mcdev.util.normalize
import com.demonwav.mcdev.util.reduceFallible
import com.demonwav.mcdev.util.sequencedSetOf
import com.demonwav.mcdev.util.sequencedSetOfNotNull
import com.intellij.psi.GenericsUtil
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.parentOfType
import java.util.IdentityHashMap
import org.objectweb.asm.Type

sealed interface SignatureSuggestion {
    val params: List<Param>?
    val returnType: PsiType
    val intLikeTypes: SequencedSet<MethodSignature.TypePosition>
    val coerceReturnType: Boolean

    data class Param(val name: String?, val type: PsiType, val coerce: Boolean = false)
}

data class SuggestedReturnType(
    override val returnType: PsiType,
    val returnTypeIsIntLike: Boolean,
    override val coerceReturnType: Boolean = false,
) : SignatureSuggestion {
    override val intLikeTypes = if (returnTypeIsIntLike) sequencedSetOf(MethodSignature.TypePosition.Return) else emptySequencedSet()
    override val params: Nothing? get() = null

    private fun intersectCoerce(other: SuggestedReturnType, manager: PsiManager): SuggestedReturnType? {
        val (returnType, returnTypeIsIntLike, coerceReturnType) = getSupertype(
            manager,
            this.returnType,
            other.returnType,
            this.returnTypeIsIntLike,
            other.returnTypeIsIntLike,
        ) ?: return null

        return SuggestedReturnType(
            returnType,
            returnTypeIsIntLike,
            this.coerceReturnType || other.coerceReturnType || coerceReturnType,
        )
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

            val forcedReturnTypesBySig = validSignatures.asSequence()
                .flatten()
                .mapNotNull { sig -> sig.forcedReturnTypes(params)?.let { sig to it } }
                .toMap(IdentityHashMap())

            val constrainedSignatures = mutableMapOf<PsiType, MutableMap<Int, MethodSignature>>()
            val unconstrainedSignatures = mutableMapOf<TypeKind, MutableMap<Int, MethodSignature>>()
            val typesByKind = mutableMapOf<TypeKind, MutableSequencedSet<PsiType>>()
            val preferences = linkedSetOf<ReturnTypePreference>()

            for ((index, signature) in validSignatures.asSequence().interleaved()) {
                val forcedReturnTypes = forcedReturnTypesBySig[signature]
                if (forcedReturnTypes != null) {
                    for (forcedReturnType in forcedReturnTypes) {
                        val normalized = forcedReturnType.normalize()
                        constrainedSignatures.getOrPut(normalized, ::mutableMapOf).putIfAbsent(index, signature)
                        typesByKind.getOrPut(TypeKind.of(normalized), ::linkedSetOf).add(normalized)
                        preferences.add(ReturnTypePreference.Constrained(normalized))
                    }
                } else {
                    val kind = TypeKind.of(signature.returnType)
                    unconstrainedSignatures.getOrPut(kind, ::mutableMapOf).putIfAbsent(index, signature)
                    preferences.add(ReturnTypePreference.Unconstrained(kind))
                }
            }

            val visitedReturnTypes = mutableSetOf<PsiType>()

            fun tryReturnType(option: PsiType): SuggestedReturnType? {
                if (!visitedReturnTypes.add(option)) {
                    return null
                }
                val unconstrained = unconstrainedSignatures[TypeKind.of(option)].orEmpty()
                val constrained = constrainedSignatures.getValue(option)
                val combined = unconstrained + constrained
                if (combined.size != signatures.size) {
                    return null
                }
                val intersected = intersectCoerce(
                    params,
                    combined.values.map { sig ->
                        val forcedReturnTypes = forcedReturnTypesBySig[sig]
                        if (forcedReturnTypes == null) {
                            exact(sig)
                        } else {
                            SuggestedReturnType(
                                forcedReturnTypes.first { it.normalize() == option },
                                returnTypeIsIntLike = false,
                            )
                        }
                    },
                ) ?: return null

                return intersected.takeIf { it.returnType.normalize() == option }
            }

            for (preference in preferences) {
                when (preference) {
                    is ReturnTypePreference.Constrained -> {
                        tryReturnType(preference.type)?.let { return it }
                    }
                    is ReturnTypePreference.Unconstrained -> {
                        val unconstrained = unconstrainedSignatures.getValue(preference.kind)
                        if (unconstrained.size == signatures.size) {
                            // Will definitely work
                            return intersectCoerce(
                                params,
                                signatures.indices.map { exact(unconstrained.getValue(it)) },
                            )!!
                        }
                        val returnTypeOptions = typesByKind[preference.kind] ?: continue
                        for (option in returnTypeOptions) {
                            tryReturnType(option)?.let { return it }
                        }
                    }
                }
            }

            return null
        }

        /**
         * Returns null iff the signature can be intersected with all types of its kind. Otherwise, returns the possible
         * return types that this signature can support.
         */
        private fun MethodSignature.forcedReturnTypes(parameterList: PsiParameterList): SequencedSet<PsiType>? {
            if (MethodSignature.TypePosition.Return in intLikeTypes) {
                val params = parameterList.parameters
                val anchor = (intLikeTypes.first() as? MethodSignature.TypePosition.Param)
                    ?.let { params[it.index]?.type }
                return when (anchor) {
                    PsiTypes.intType() -> null
                    null -> {
                        val intLikeParams = intLikeTypes.asSequence()
                            .mapNotNull { it.getParam(params) }
                            .groupBy { it.type }
                        when {
                            intLikeParams.isEmpty() -> null
                            PsiTypes.intType() in intLikeParams -> sequencedSetOf(PsiTypes.intType())
                            intLikeParams.size == 1 -> sequencedSetOfNotNull(
                                intLikeParams.keys.single(),
                                PsiTypes.intType()
                                    .takeIf { intLikeParams.values.single().all { it.hasAnnotation(COERCE) } },
                            )
                            else -> {
                                for ((type, params) in intLikeParams) {
                                    check(type == PsiTypes.intType() || params.all { it.hasAnnotation(COERCE) })
                                }
                                sequencedSetOf(PsiTypes.intType())
                            }
                        }
                    }
                    else -> sequencedSetOf(anchor)
                }
            }
            if (TypeKind.of(returnType) == TypeKind.INT_LIKE && returnType != PsiTypes.intType()) {
                return sequencedSetOf(returnType)
            }

            return sequencedSetOf(returnType).takeUnless { allowCoerceRequired }
        }

        private fun intersectCoerce(
            context: PsiElement,
            types: List<SuggestedReturnType>,
        ): SuggestedReturnType? {
            if (!types.asSequence().map { TypeKind.of(it.returnType) }.allEqual()) {
                return null
            }

            val manager = PsiManager.getInstance(context.project)
            return types.asSequence().reduceFallible { acc, it -> acc.intersectCoerce(it, manager) }
        }

        private sealed interface ReturnTypePreference {
            data class Constrained(val type: PsiType) : ReturnTypePreference

            data class Unconstrained(val kind: TypeKind) : ReturnTypePreference
        }
    }
}

data class SuggestedSignature(
    override val params: List<SignatureSuggestion.Param>,
    override val returnType: PsiType,
    override val intLikeTypes: SequencedSet<MethodSignature.TypePosition> = emptySequencedSet(),
    override val coerceReturnType: Boolean = false,
) : SignatureSuggestion {
    private fun intersectCoerce(other: SuggestedSignature, manager: PsiManager): SuggestedSignature? {
        val intLikeTypes = linkedSetOf<MethodSignature.TypePosition>()

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
            SequencedSet(intLikeTypes),
            this.coerceReturnType || other.coerceReturnType || coerceReturnType,
        )
    }

    companion object {
        fun exact(
            signature: MethodSignature,
            takeTrailing: Int = 0,
            intLikeAssignment: PsiType? = null,
        ): SuggestedSignature {
            var returnType = signature.returnType
            val params =
                (signature.requiredParams.asSequence() + signature.trailingParams.asSequence().take(takeTrailing)).map {
                    SignatureSuggestion.Param(
                        it.name,
                        it.type,
                    )
                }.toMutableList()
            if (intLikeAssignment != null) {
                for (pos in signature.intLikeTypes) {
                    when (pos) {
                        MethodSignature.TypePosition.Return -> returnType = intLikeAssignment
                        is MethodSignature.TypePosition.Param -> {
                            params[pos.index] = params[pos.index].copy(type = intLikeAssignment)
                        }
                    }
                }
            }
            return SuggestedSignature(
                params,
                returnType,
                if (intLikeAssignment == null) signature.intLikeTypes else emptySequencedSet(),
            )
        }

        fun modifier(annotation: PsiAnnotation, signatures: List<ModifierSignatures>): SuggestedSignature? {
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
            return intersectCoerce(
                annotation,
                signatures.map { it.signature },
                numParams = { it.requiredParams.size },
            )
        }

        fun inject(annotation: PsiAnnotation, signatures: List<InjectSignatures>): SuggestedSignature? {
            val localsToUse =
                coerciblePrefixLength(signatures.asSequence().map { sig -> sig.locals.asSequence().map { it.type } })

            val longForm = intersectCoerce(
                annotation,
                signatures.map { it.longSignature },
                numParams = { it.requiredParams.size + localsToUse },
            )

            return longForm ?: intersectCoerce(
                annotation,
                signatures.map { it.shortSignature ?: it.longSignature },
                numParams = { it.requiredParams.size },
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

                val (soft, hard) = candidates.partition { it.allowCoerceRequired }

                val intersected = intersect(
                    annotation,
                    softSignatures = soft,
                    hardSignatures = hard,
                    numParams = { numParams },
                ) ?: continue

                return intersected
            }

            return null
        }

        private fun intersectCoerce(
            context: PsiElement,
            signatures: List<MethodSignature>,
            numParams: (MethodSignature) -> Int,
        ): SuggestedSignature? {
            return intersect(
                context,
                softSignatures = signatures,
                hardSignatures = emptyList(),
                numParams = numParams,
            )
        }

        private fun intersect(
            context: PsiElement,
            softSignatures: List<MethodSignature>,
            hardSignatures: List<MethodSignature>,
            numParams: (MethodSignature) -> Int,
        ): SuggestedSignature? {
            val allSignatures = softSignatures.asSequence() + hardSignatures
            val paramsToUse = allSignatures.map { numParams(it) }.distinct().singleOrNull() ?: return null

            val intLikeAnchor = run {
                allSignatures.mapNotNull { it.intLikeTypes.firstOrNull() }.toList()
                    .ifEmpty { return@run null }
                    .asSequence()
                    .distinct()
                    .singleOrNull() ?: return null
            }
            val intLikeAssignment = intLikeAnchor?.let {
                val solver = IntLikeAnchorSolver()
                val signatures =
                    softSignatures.asSequence().map { it to false } + hardSignatures.asSequence().map { it to true }
                for ((signature, isHard) in signatures) {
                    for (pos in signature.allPositions(paramsToUse)) {
                        if (pos in signature.intLikeTypes) {
                            continue
                        }
                        val isValid = solver.constrain(
                            pos.getType(signature),
                            isAnchor = pos == intLikeAnchor,
                            isHard = isHard,
                        )
                        if (!isValid) {
                            return null
                        }
                    }
                }
                solver.solve()
            }

            val suggestedSignatures = allSignatures.map {
                exact(it, takeTrailing = paramsToUse - it.requiredParams.size, intLikeAssignment = intLikeAssignment)
            }.toList()

            if (suggestedSignatures.asSequence().zipWithNext { a, b -> !kindsMatch(a, b) }.any { it }) {
                return null
            }

            val manager = PsiManager.getInstance(context.project)
            val intersected = suggestedSignatures.asSequence()
                .reduceFallible { acc, it -> acc.intersectCoerce(it, manager) } ?: return null

            return intersected.takeIf {
                hardSignatures.all { it.matches(intersected) }
            }
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
