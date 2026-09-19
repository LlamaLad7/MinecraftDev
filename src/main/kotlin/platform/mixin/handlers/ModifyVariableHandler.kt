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

package com.demonwav.mcdev.platform.mixin.handlers

import com.demonwav.mcdev.platform.mixin.handlers.injectionPoint.CollectVisitor
import com.demonwav.mcdev.platform.mixin.handlers.mixinextras.TargetInsn
import com.demonwav.mcdev.platform.mixin.inspection.injector.MethodSignature
import com.demonwav.mcdev.platform.mixin.inspection.injector.SuggestedSignature
import com.demonwav.mcdev.platform.mixin.util.ClassAndMethodNode
import com.demonwav.mcdev.platform.mixin.util.LocalInfo
import com.demonwav.mcdev.platform.mixin.util.toPsiType
import com.demonwav.mcdev.util.findContainingMethod
import com.demonwav.mcdev.util.findModule
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.llamalad7.mixinextras.expression.impl.point.ExpressionContext
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

class ModifyVariableHandler : InsnInjectorAnnotationHandler() {
    override fun expectedMethodSignature(
        annotation: PsiAnnotation,
        targetClass: ClassNode,
        targetMethod: MethodNode,
        targetInsn: TargetInsn,
    ): List<MethodSignature>? {
        val module = annotation.findModule() ?: return null
        val targetParams = collectTargetMethodParameters(annotation.project, targetClass, targetMethod)

        val method = annotation.findContainingMethod() ?: return null
        val localType = method.parameterList.getParameter(0)?.type
        val info = LocalInfo.fromAnnotation(localType, annotation)

        val elementFactory = JavaPsiFacade.getElementFactory(annotation.project)
        val seenParams = mutableSetOf<String>()
        val result = mutableListOf<MethodSignature>()
        val matchedLocals = info.matchLocals(
            module, targetClass, targetMethod, targetInsn.insn,
            CollectVisitor.Mode.SUGGESTION, matchType = false
        ).orEmpty()
        for (local in matchedLocals) {
            if (seenParams.add(local.desc + local.name)) {
                val localType = Type.getType(local.desc).toPsiType(elementFactory)
                result += MethodSignature(
                    listOf(sanitizedParameter(localType, local.name, local.isNamed)),
                    localType,
                    trailingParams = targetParams,
                    allowCoerceRequired = false,
                )
            }
        }

        return result
    }

    override fun suggestedMethodSignature(
        annotation: PsiAnnotation,
        targets: List<ClassAndMethodNode>
    ): SuggestedSignature? {
        return SuggestedSignature.modifierNoCoerce(annotation, targets, this)
    }

    override val isShiftAlwaysDiscouraged = false

    override val mixinExtrasExpressionContextType = ExpressionContext.Type.MODIFY_VARIABLE
}
