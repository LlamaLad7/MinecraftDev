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

import com.demonwav.mcdev.platform.mixin.util.MixinConstants.Annotations.COERCE
import com.demonwav.mcdev.platform.mixin.util.checkCoerce
import com.demonwav.mcdev.platform.mixin.util.isMixinExtrasSugar
import com.demonwav.mcdev.util.Parameter
import com.demonwav.mcdev.util.allEqual
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeElement

data class MethodSignature(
    val requiredParams: List<Parameter>,
    val returnType: PsiType,
    val allowCoerceRequired: Boolean,
    val trailingParams: List<Parameter> = emptyList(),
    val allowCoerceTrailing: Boolean = true,
    val intLikeTypes: Set<TypePosition> = emptySet()
) {
    sealed interface TypePosition : Comparable<TypePosition> {
        fun getElement(method: PsiMethod): PsiTypeElement?

        data object Return : TypePosition {
            override fun getElement(method: PsiMethod) = method.returnTypeElement

            override fun compareTo(other: TypePosition): Int = if (other is Return) 0 else -1
        }

        data class Param(val index: Int) : TypePosition {
            override fun getElement(method: PsiMethod) = method.parameterList.parameters[index].typeElement

            override fun compareTo(other: TypePosition): Int = if (other is Param) index.compareTo(other.index) else 1
        }
    }

    fun matches(method: PsiMethod): Boolean {
        return matches(
            method.parameterList.parameters.dropLastWhile { it.isMixinExtrasSugar },
            method.returnType ?: return false,
            method.hasAnnotation(COERCE),
            { it.type },
            { it.hasAnnotation(COERCE) },
        )
    }

    fun matches(suggested: SuggestedSignature): Boolean {
        return matches(
            suggested.params,
            suggested.returnType,
            suggested.coerceReturnType,
            { it.type },
            { it.coerce },
        )
    }

    fun matchesParams(params: PsiParameterList): Boolean {
        return matchesParams(
            params.parameters.dropLastWhile { it.isMixinExtrasSugar },
            { it.type },
            { it.hasAnnotation(COERCE) },
        )
    }

    fun matchesReturnType(returnType: PsiType, hasCoerce: Boolean): Boolean =
        matchType(this.returnType, returnType, allowCoerceRequired && hasCoerce, TypePosition.Return)

    private fun <ParamT : Any> matches(
        params: List<ParamT>,
        returnType: PsiType,
        returnCoerce: Boolean,
        paramType: (ParamT) -> PsiType,
        paramCoerce: (ParamT) -> Boolean,
    ): Boolean {
        val intLikeMismatch = TypePosition.Return in intLikeTypes
            && intLikeTypes.asSequence()
            .filterIsInstance<TypePosition.Param>()
            .mapNotNull { params.getOrNull(it.index) }
            .take(1)
            .any { paramType(it) != returnType }

        return !intLikeMismatch
            && matchesReturnType(returnType, returnCoerce)
            && matchesParams(params, paramType, paramCoerce)
    }

    private fun <ParamT : Any> matchesParams(
        params: List<ParamT>,
        paramType: (ParamT) -> PsiType,
        paramCoerce: (ParamT) -> Boolean,
    ): Boolean {
        fun matchParams(expected: List<Parameter>, allowCoerce: Boolean, startIndex: Int): Boolean {
            return expected.asSequence()
                .zip(params.asSequence().withIndex().drop(startIndex))
                .all { (expected, indexAndActual) ->
                    val (index, actual) = indexAndActual
                    matchType(
                        expected.type,
                        paramType(actual),
                        allowCoerce && paramCoerce(actual),
                        TypePosition.Param(index),
                    )
                }
        }

        return params.size in requiredParams.size..requiredParams.size + trailingParams.size
            && intLikeTypes.asSequence().filterIsInstance<TypePosition.Param>().mapNotNull { (index) ->
                    params.getOrNull(index)?.let(paramType)
               }.allEqual()
            && matchParams(requiredParams, allowCoerceRequired, 0)
            && matchParams(trailingParams, allowCoerceTrailing, requiredParams.size)
    }

    private fun matchType(expected: PsiType, actual: PsiType, coerce: Boolean, typePos: TypePosition) =
        checkCoerce(expected, actual, coerce, typePos in intLikeTypes)
}
