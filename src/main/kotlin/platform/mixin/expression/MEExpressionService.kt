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

package com.demonwav.mcdev.platform.mixin.expression

import com.demonwav.mcdev.platform.mixin.util.toPsiType
import com.demonwav.mcdev.util.descriptor
import com.intellij.psi.GenericsUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.llamalad7.mixinextras.expression.impl.ExpressionService
import com.llamalad7.mixinextras.expression.impl.flow.FlowContext
import org.objectweb.asm.Type

object MEExpressionService : ExpressionService() {
    override fun getCommonSuperClass(ctx: FlowContext, type1: Type, type2: Type): Type {
        ctx as MEFlowContext
        val elementFactory = JavaPsiFacade.getElementFactory(ctx.project)
        return Type.getType(
            GenericsUtil.getLeastUpperBound(
                type1.toPsiType(elementFactory),
                type2.toPsiType(elementFactory),
                PsiManager.getInstance(ctx.project)
            )?.descriptor ?: error("Failed to merge types $type1 and $type2!")
        )
    }
}
