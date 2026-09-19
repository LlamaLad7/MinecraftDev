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

import com.demonwav.mcdev.platform.mixin.handlers.InjectorAnnotationHandler
import com.demonwav.mcdev.platform.mixin.util.ClassAndMethodNode
import com.demonwav.mcdev.util.normalize
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
        if (roughShapeOf(this) != roughShapeOf(other)) {
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
        fun exact(signature: MethodSignature): SuggestedSignature {
            return SuggestedSignature(
                signature.requiredParams.map { Param(it.name, it.type) },
                signature.returnType,
                signature.intLikeTypes,
            )
        }

        fun modifierNoCoerce(
            annotation: PsiAnnotation,
            targets: List<ClassAndMethodNode>,
            handler: InjectorAnnotationHandler,
        ): SuggestedSignature? {
            val parameterOptions = handler.expectedMethodSignatures(annotation, targets).map { signatures ->
                signatures.mapNotNull {
                    require(it.intLikeTypes.isEmpty())
                    it.requiredParams.singleOrNull()
                }.ifEmpty { return null }
            }
            val psiManager = PsiManager.getInstance(annotation.project)

            val optionsByType = parameterOptions.asSequence().flatten().groupBy { it.type.normalize() }
            val chosenParams = optionsByType.values.firstOrNull { it.size == parameterOptions.size } ?: return null

            val name = chosenParams.asSequence().map { it.name }.distinct().singleOrNull() ?: "original"
            val type = chosenParams.asSequence().map { it.type }
                .reduce { a, b -> GenericsUtil.getLeastUpperBound(a, b, psiManager) ?: a }

            return SuggestedSignature(listOf(Param(name, type)), type)
        }

        fun operationWrapper(
            annotation: PsiAnnotation,
            targets: List<ClassAndMethodNode>,
            handler: InjectorAnnotationHandler,
        ): SuggestedSignature? {
            val manager = PsiManager.getInstance(annotation.project)
            return handler.expectedMethodSignatures(annotation, targets).map {
                exact(it.single())
            }.reduceOrNull<SuggestedSignature?, _> { acc, it -> acc?.intersectCoerce(it, manager) }
        }
    }
}

private fun roughShapeOf(signature: SuggestedSignature): Shape<TypeKind> {
    return Shape(signature.params.map { TypeKind.of(it.type) }, TypeKind.of(signature.returnType))
}

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
                coerce = intLikeAssignment != null && intLikeAssignment != b,
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

private data class Shape<out T>(val params: List<T>, val returnType: T)

private enum class TypeKind {
    OBJECT,
    INT_LIKE,
    FLOAT,
    DOUBLE,
    LONG,
    VOID;

    companion object {
        private val typeMap = mapOf(
            PsiTypes.byteType() to INT_LIKE,
            PsiTypes.charType() to INT_LIKE,
            PsiTypes.intType() to INT_LIKE,
            PsiTypes.shortType() to INT_LIKE,
            PsiTypes.booleanType() to INT_LIKE,
            PsiTypes.doubleType() to DOUBLE,
            PsiTypes.floatType() to FLOAT,
            PsiTypes.longType() to LONG,
            PsiTypes.voidType() to VOID,
        )

        fun of(type: PsiType) = typeMap[type] ?: OBJECT
    }
}

//private class Shape<out T>(private val init: Shape<T>?, private val last: T) {
//    private val hashCode = init.hashCode() * 31 + last.hashCode()
//
//    override fun equals(other: Any?): Boolean {
//        if (other !is Shape<*>) {
//            return false
//        }
//        @Suppress("UNCHECKED_CAST")
//        other as Shape<T>
//
//        var a: Shape<T>? = this
//        var b: Shape<T>? = other
//        while (a != null && b != null) {
//            if (a === b) {
//                return true
//            }
//            if (a.hashCode != b.hashCode || a.last != b.last) {
//                return false
//            }
//            a = a.init
//            b = b.init
//        }
//        return a == null && b == null
//    }
//
//    override fun hashCode() = hashCode
//
//    override fun toString() = buildString {
//        append('[')
//        this@Shape.append(this@buildString)
//        append(']')
//    }
//
//    private fun append(builder: StringBuilder) {
//        init?.let {
//            it.append(builder)
//            builder.append(", ")
//        }
//        builder.append(last)
//    }
//}
