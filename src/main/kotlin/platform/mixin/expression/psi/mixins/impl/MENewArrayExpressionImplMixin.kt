/*
 * Minecraft Development for IntelliJ
 *
 * https://mcdev.io/
 *
 * Copyright (C) 2024 minecraft-dev
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

package com.demonwav.mcdev.platform.mixin.expression.psi.mixins.impl

import com.demonwav.mcdev.platform.mixin.expression.MESourceMatchContext
import com.demonwav.mcdev.platform.mixin.expression.gen.psi.MEArguments
import com.demonwav.mcdev.platform.mixin.expression.gen.psi.MEExpression
import com.demonwav.mcdev.platform.mixin.expression.gen.psi.MEExpressionTypes
import com.demonwav.mcdev.platform.mixin.expression.gen.psi.MEName
import com.demonwav.mcdev.platform.mixin.expression.gen.psi.impl.MEExpressionImpl
import com.demonwav.mcdev.platform.mixin.expression.meExpressionElementFactory
import com.demonwav.mcdev.platform.mixin.expression.psi.mixins.MENewArrayExpressionMixin
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.siblings

abstract class MENewArrayExpressionImplMixin(node: ASTNode) : MEExpressionImpl(node), MENewArrayExpressionMixin {
    override val dimensions get() = findChildrenByType<PsiElement>(MEExpressionTypes.TOKEN_LEFT_BRACKET).size

    override val dimExprTokens: List<MENewArrayExpressionMixin.DimExprTokens> get() {
        val result = mutableListOf<MENewArrayExpressionMixin.DimExprTokens>()

        var leftBracket: PsiElement? = findNotNullChildByType(MEExpressionTypes.TOKEN_LEFT_BRACKET)
        while (leftBracket != null) {
            var expr: MEExpression? = null
            var rightBracket: PsiElement? = null
            var nextLeftBracket: PsiElement? = null
            for (child in leftBracket.siblings(withSelf = false)) {
                if (child is MEExpression) {
                    expr = child
                } else {
                    when (child.node.elementType) {
                        MEExpressionTypes.TOKEN_RIGHT_BRACKET -> rightBracket = child
                        MEExpressionTypes.TOKEN_LEFT_BRACKET -> {
                            nextLeftBracket = child
                            break
                        }
                    }
                }
            }
            result += MENewArrayExpressionMixin.DimExprTokens(leftBracket, expr, rightBracket)
            leftBracket = nextLeftBracket
        }

        return result
    }

    override fun matchesJava(java: PsiElement, context: MESourceMatchContext): Boolean {
        if (java !is PsiNewExpression) {
            return false
        }

        if (!java.isArrayCreation) {
            return false
        }

        val javaArrayType = java.type as? PsiArrayType ?: return false
        if (javaArrayType.arrayDimensions != dimensions) {
            return false
        }

        val matchesType = context.project.meExpressionElementFactory.createType(elementType)
            .matchesJava(javaArrayType.deepComponentType, context)
        if (!matchesType) {
            return false
        }

        val javaArrayDims = java.arrayDimensions
        val arrayDims = dimExprs
        if (javaArrayDims.size != arrayDims.size) {
            return false
        }
        if (!javaArrayDims.asSequence().zip(arrayDims.asSequence()).all { (javaArrayDim, arrayDim) ->
            val actualJavaDim = PsiUtil.skipParenthesizedExprDown(javaArrayDim) ?: return@all false
            arrayDim.matchesJava(actualJavaDim, context)
        }
        ) {
            return false
        }

        val javaArrayInitializer = java.arrayInitializer
        val arrayInitializer = this.arrayInitializer
        return if (javaArrayInitializer == null) {
            arrayInitializer == null
        } else {
            arrayInitializer?.matchesJava(javaArrayInitializer.initializers, context) == true
        }
    }

    protected abstract val elementType: MEName
    protected abstract val dimExprs: List<MEExpression>
    protected abstract val arrayInitializer: MEArguments?
}