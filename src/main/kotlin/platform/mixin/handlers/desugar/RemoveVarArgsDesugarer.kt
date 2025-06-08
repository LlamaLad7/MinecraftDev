/*
 * Minecraft Development for IntelliJ
 *
 * https://mcdev.io/
 *
 * Copyright (C) 2025 minecraft-dev
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

package com.demonwav.mcdev.platform.mixin.handlers.desugar

import com.demonwav.mcdev.util.childrenOfType
import com.intellij.codeInsight.daemon.impl.analysis.JavaGenericsUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiCall
import com.intellij.psi.PsiCapturedWildcardType
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiTypeCastExpression
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.TypeConversionUtil
import com.siyeh.ig.psiutils.MethodCallUtils

object RemoveVarArgsDesugarer : Desugarer() {
    override fun desugar(project: Project, file: PsiJavaFile, context: DesugarContext) {
        val varArgsStarts = mutableListOf<Pair<PsiCall, Int>>()
        PsiTreeUtil.processElements(file) { element ->
            if (element is PsiCall && MethodCallUtils.isVarArgCall(element)) {
                val argumentCount = element.resolveMethod()?.parameterList?.parametersCount ?: return@processElements true
                varArgsStarts += element to argumentCount - 1
            }
            true
        }

        val elementFactory = JavaPsiFacade.getElementFactory(project)

        for ((call, varArgsStart) in varArgsStarts.asReversed()) {
            val argumentList = call.argumentList ?: continue
            val arguments = argumentList.expressions

            val result = call.resolveMethodGenerics()
            val method = result.element as? PsiMethod ?: continue
            val parameters = method.parameterList.parameters
            val componentType = PsiTypesUtil.getParameterType(
                parameters,
                parameters.lastIndex,
                true
            )

            var type = result.substitutor.substitute(componentType)
            if (type is PsiCapturedWildcardType) {
                type = type.lowerBound
            }

            val (replacementExpr, newArrayExpr) = if (JavaGenericsUtil.isReifiableType(type)) {
                val newExpr = elementFactory.createExpressionFromText("new ${type.canonicalText}[] {}", call)
                    as PsiNewExpression
                newExpr to newExpr
            } else {
                val erasure = TypeConversionUtil.erasure(type)
                val castExpr = elementFactory.createExpressionFromText(
                    "(${type.createArrayType().canonicalText}) new ${erasure.canonicalText}[] {}",
                    call
                ) as PsiTypeCastExpression
                castExpr to castExpr.operand as PsiNewExpression
            }

            if (arguments.size > varArgsStart) {
                DesugarUtil.setOriginalElement(
                    replacementExpr,
                    DesugarUtil.getOriginalElement(arguments[varArgsStart])
                )
                newArrayExpr.arrayInitializer!!.addRange(arguments[varArgsStart], arguments.last())
                for (i in varArgsStart until arguments.size) {
                    arguments[i].delete()
                }
            } else {
                DesugarUtil.setOriginalElement(
                    replacementExpr,
                    argumentList.lastChild?.let(DesugarUtil::getOriginalElement)
                )
            }

            argumentList.add(replacementExpr)
        }

        val ellipsisTypes = file.childrenOfType<PsiTypeElement>().filter { it.type is PsiEllipsisType }
        for (ellipsisType in ellipsisTypes) {
            val newType = (ellipsisType.type as PsiEllipsisType).toArrayType()
            val newTypeElement = ellipsisType.replace(elementFactory.createTypeElement(newType))
            DesugarUtil.setOriginalElement(newTypeElement, DesugarUtil.getOriginalElement(ellipsisType))
        }
    }
}
