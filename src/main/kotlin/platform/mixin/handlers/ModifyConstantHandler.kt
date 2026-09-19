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

import com.demonwav.mcdev.platform.mixin.handlers.injectionPoint.ConstantInjectionPoint
import com.demonwav.mcdev.platform.mixin.handlers.injectionPoint.InjectionPoint
import com.demonwav.mcdev.platform.mixin.handlers.mixinextras.TargetInsn
import com.demonwav.mcdev.platform.mixin.inspection.injector.MethodSignature
import com.demonwav.mcdev.platform.mixin.inspection.injector.SuggestedSignature
import com.demonwav.mcdev.platform.mixin.util.ClassAndMethodNode
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.llamalad7.mixinextras.expression.impl.point.ExpressionContext
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

class ModifyConstantHandler : InsnInjectorAnnotationHandler() {
    private val constantInjectionPoint by lazy { InjectionPoint.byAtCode("CONSTANT") as ConstantInjectionPoint }

    private val allowedOpcodes = setOf(
        Opcodes.ACONST_NULL,
        Opcodes.ICONST_M1,
        Opcodes.ICONST_0,
        Opcodes.ICONST_1,
        Opcodes.ICONST_2,
        Opcodes.ICONST_3,
        Opcodes.ICONST_4,
        Opcodes.ICONST_5,
        Opcodes.LCONST_0,
        Opcodes.LCONST_1,
        Opcodes.FCONST_0,
        Opcodes.FCONST_1,
        Opcodes.FCONST_2,
        Opcodes.DCONST_0,
        Opcodes.DCONST_1,
        Opcodes.BIPUSH,
        Opcodes.SIPUSH,
        Opcodes.LDC,
        Opcodes.IFLT,
        Opcodes.IFGE,
        Opcodes.IFGT,
        Opcodes.IFLE,
        Opcodes.INSTANCEOF,
    )

    override fun getAtKey(annotation: PsiAnnotation) = "constant"

    override fun expectedMethodSignature(
        annotation: PsiAnnotation,
        targetClass: ClassNode,
        targetMethod: MethodNode,
        targetInsn: TargetInsn,
    ): List<MethodSignature>? {
        val targetParams = collectTargetMethodParameters(annotation.project, targetClass, targetMethod)
        val cst = constantInjectionPoint.getTargetedConstant(targetInsn.insn) ?: return emptyList()
        return basicMethodSignatures(annotation, cst)?.map { it.copy(trailingParams = targetParams) }
    }

    override fun suggestedMethodSignature(
        annotation: PsiAnnotation,
        targets: List<ClassAndMethodNode>
    ): SuggestedSignature? {
        val isTypeCheck =
            resolveInstructions(annotation, targets).asSequence()
                .map { it.result.insn.opcode == Opcodes.INSTANCEOF }
                .distinct()
                .singleOrNull() ?: return null

        return if (isTypeCheck) {
            SuggestedSignature.exact(
                makeTypeCheckMethodSignature(
                    PsiManager.getInstance(annotation.project),
                    annotation,
                    PsiTypes.booleanType(),
                )
            )
        } else {
            SuggestedSignature.modifierNoCoerce(annotation, targets, this)
        }
    }

    private fun basicMethodSignatures(annotation: PsiAnnotation, cst: Any): List<MethodSignature>? {
        val psiManager = PsiManager.getInstance(annotation.project)

        return if (cst is Type) {
            listOf(
                makeTypeCheckMethodSignature(
                    psiManager,
                    annotation,
                    PsiTypes.booleanType(),
                ),
                makeTypeCheckMethodSignature(
                    psiManager,
                    annotation,
                    getClassType(psiManager, annotation),
                ),
            )
        } else {
            listOf(
                makeMethodSignature(
                    getConstantType(annotation, cst)
                        ?: throw IllegalStateException("Unknown constant type: ${cst.javaClass.name}")
                ),
            )
        }
    }

    private fun getConstantType(context: PsiAnnotation, cst: Any) = when (cst) {
        is Int -> PsiTypes.intType()
        is Long -> PsiTypes.longType()
        is Float -> PsiTypes.floatType()
        is Double -> PsiTypes.doubleType()
        is String -> PsiType.getJavaLangString(
            PsiManager.getInstance(context.project),
            context.resolveScope
        )

        else -> null
    }

    private fun makeMethodSignature(type: PsiType): MethodSignature {
        return MethodSignature(
            listOf(sanitizedParameter(type, "constant")),
            type,
            allowCoerceRequired = true,
        )
    }

    private fun makeTypeCheckMethodSignature(
        psiManager: PsiManager,
        context: PsiElement,
        returnType: PsiType,
    ): MethodSignature {
        return MethodSignature(
            listOf(
                sanitizedParameter(PsiType.getJavaLangObject(psiManager, context.resolveScope), "instance"),
                sanitizedParameter(getClassType(psiManager, context), "type"),
            ),
            returnType,
            allowCoerceRequired = false,
        )
    }

    private fun getClassType(psiManager: PsiManager, context: PsiElement): PsiType {
        return JavaPsiFacade.getElementFactory(psiManager.project).createTypeFromText("java.lang.Class<?>", context)
    }

    override fun isInsnAllowed(insn: AbstractInsnNode, decorations: Map<String, Any?>): Boolean {
        return insn.opcode in allowedOpcodes
    }

    override val allowedInsnDescription = "constants"

    override val mixinExtrasExpressionContextType = ExpressionContext.Type.MODIFY_CONSTANT
}
