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

package com.demonwav.mcdev.platform.mixin.expression.gui

import com.demonwav.mcdev.platform.mixin.reference.MethodReference
import com.demonwav.mcdev.platform.mixin.util.findClassNodeByPsiClass
import com.demonwav.mcdev.util.descriptor
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.parentOfType
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode

class MEShowFlowAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = resolve(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (clazz, method, lineNumber) = resolve(e) ?: return
        project.service<MEFlowWindowService>().showDiagram(clazz, method, lineNumber)
    }

    private fun resolve(e: AnActionEvent): Resolved? {
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        if (file.language != JavaLanguage.INSTANCE) {
            return null
        }
        val caret = e.getData(CommonDataKeys.CARET) ?: return null
        val element = file.findElementAt(caret.offset) ?: return null
        val psiClass = element.parentOfType<PsiClass>() ?: return null

        fun resolveMixinMethodString(): Resolved? {
            val string = element.parentOfType<PsiLiteralExpression>() ?: return null
            return MethodReference.resolveIfUnique(string)?.let { (clazz, method) ->
                Resolved(clazz, method)
            }
        }

        fun resolvePsiMethod(): Resolved? {
            val identifier = element as? PsiIdentifier ?: return null
            val psiMethod = identifier.parent as? PsiMethod ?: return null
            val clazz = findClassNodeByPsiClass(psiClass) ?: return null
            val desc = psiMethod.descriptor ?: return null
            val methodNode = clazz.methods.find { it.name == psiMethod.name && it.desc == desc } ?: return null
            return Resolved(clazz, methodNode)
        }

        fun resolveMethodByLine(): Resolved? {
            val clazz = findClassNodeByPsiClass(psiClass) ?: return null
            val lineNumber = caret.logicalPosition.line + 1
            val method = clazz.methods.find { method ->
                method.instructions.asSequence()
                    .filterIsInstance<LineNumberNode>()
                    .any { it.line == lineNumber }
            } ?: return null
            return Resolved(clazz, method, lineNumber)
        }

        return resolveMixinMethodString()
            ?: resolvePsiMethod()
            ?: resolveMethodByLine()
    }

    private data class Resolved(val clazz: ClassNode, val method: MethodNode, val line: Int? = null)
}
