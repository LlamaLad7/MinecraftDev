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
import com.demonwav.mcdev.platform.mixin.util.isAssignable
import com.demonwav.mcdev.platform.mixin.util.isMixinExtrasSugar
import com.demonwav.mcdev.util.Parameter
import com.demonwav.mcdev.util.allSame
import com.demonwav.mcdev.util.countIsLessThan
import com.demonwav.mcdev.util.normalize
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.PsiTypes

data class MethodSignature(
    val requiredParams: List<Parameter>,
    val returnType: PsiType,
    val allowCoerceRequired: Boolean,
    val trailingParams: List<Parameter> = emptyList(),
    val allowCoerceTrailing: Boolean = true,
    val intLikeTypes: Set<TypePosition> = emptySet()
) {
    fun matches(method: PsiMethod): Boolean {
        val returnType = method.returnType ?: return false
        val parameters = method.parameterList.parameters.dropLastWhile { it.isMixinExtrasSugar }

        return intLikeTypes.asSequence().map { it.getElement(method)?.type }.allSame()
            && matchReturnType(returnType, method.hasAnnotation(COERCE))
            && parameters.size in requiredParams.size..requiredParams.size + trailingParams.size
            && matchParams(requiredParams, parameters, allowCoerceRequired, 0)
            && matchParams(trailingParams, parameters, allowCoerceTrailing, requiredParams.size)
    }

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

    private fun matchReturnType(returnType: PsiType, hasCoerce: Boolean): Boolean =
        matchType(returnType, this.returnType, allowCoerceRequired && hasCoerce, TypePosition.Return)

    private fun matchParams(
        expectedParams: List<Parameter>,
        actualParams: List<PsiParameter>,
        allowCoerce: Boolean,
        startIndex: Int,
    ): Boolean {
        return expectedParams.asSequence()
            .zip(actualParams.asSequence().withIndex().drop(startIndex))
            .all { (expected, indexAndActual) ->
                val (index, actual) = indexAndActual
                matchType(
                    expected.type,
                    actual.type,
                    allowCoerce && actual.hasAnnotation(COERCE),
                    TypePosition.Param(index),
                )
            }
    }

    private fun matchType(expected: PsiType, actual: PsiType, coerce: Boolean, typePos: TypePosition) =
        checkCoerce(expected, actual, coerce, typePos in intLikeTypes)
}
