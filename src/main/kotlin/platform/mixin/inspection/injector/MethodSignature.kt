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
import com.demonwav.mcdev.platform.mixin.util.isAssignable
import com.demonwav.mcdev.util.Parameter
import com.demonwav.mcdev.util.normalize
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.PsiTypes

data class MethodSignature(
    val requiredParams: List<Parameter>,
    val returnType: PsiType,
    val trailingParams: List<Parameter> = emptyList(),
    val trailingByDefault: Boolean = false,
    val intLikeTypes: List<TypePosition> = emptyList()
) {
    fun matchParams(parameters: List<PsiParameter>, allowCoerce: Boolean): Boolean {
        return parameters.size in requiredParams.size..requiredParams.size + trailingParams.size
            && matchParams(requiredParams, parameters, allowCoerce)
            && matchParams(trailingParams, parameters.subList(requiredParams.size, parameters.size), allowCoerce)
    }

    sealed interface TypePosition {
        fun getElement(method: PsiMethod): PsiTypeElement?

        data object Return : TypePosition {
            override fun getElement(method: PsiMethod) = method.returnTypeElement
        }

        data class Param(val index: Int) : TypePosition {
            override fun getElement(method: PsiMethod) = method.parameterList.parameters[index].typeElement
        }
    }

    companion object {
        private val INT_TYPES = setOf(
            PsiTypes.intType(),
            PsiTypes.shortType(),
            PsiTypes.charType(),
            PsiTypes.byteType(),
            PsiTypes.booleanType()
        )

        private fun matchParams(
            expectedParams: List<Parameter>,
            actualParams: List<PsiParameter>,
            allowCoerce: Boolean
        ): Boolean {
            return expectedParams.asSequence()
                .zip(actualParams.asSequence())
                .all { (expected, actual) -> matchParam(expected.type, actual, allowCoerce) }
        }

        private fun matchParam(expectedType: PsiType, parameter: PsiParameter, allowCoerce: Boolean): Boolean {
            val normalizedExpected = expectedType.normalize()
            val normalizedParameter = parameter.type.normalize()
            if (normalizedExpected == normalizedParameter) {
                return true
            }
            if (!allowCoerce || !parameter.hasAnnotation(COERCE)) {
                return false
            }

            if (normalizedExpected is PsiPrimitiveType) {
                if (normalizedParameter !is PsiPrimitiveType) {
                    return false
                }
                return normalizedExpected in INT_TYPES && normalizedParameter in INT_TYPES
            }
            return isAssignable(normalizedParameter, normalizedExpected)
        }
    }
}
