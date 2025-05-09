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

import com.demonwav.mcdev.util.cached
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parents

object DesugarUtil {
    private val ORIGINAL_ELEMENT_KEY = Key.create<PsiElement>("mcdev.desugar.originalElement")

    private val DESUGARERS = arrayOf<Desugarer>(
        FieldAssignmentDesugarer,
    )

    fun getOriginalElement(desugared: PsiElement): PsiElement? {
        return desugared.parents(true).firstNotNullOfOrNull { it.getCopyableUserData(ORIGINAL_ELEMENT_KEY) }
    }

    fun setOriginalElement(desugared: PsiElement, original: PsiElement?) {
        desugared.putCopyableUserData(ORIGINAL_ELEMENT_KEY, original)
    }

    fun desugar(project: Project, clazz: PsiClass): PsiClass {
        return clazz.cached {
            val desugaredFile = clazz.containingFile.copy() as PsiFile
            val desugaredClass = PsiTreeUtil.findSameElementInCopy(clazz, desugaredFile)
            setOriginalRecursive(desugaredClass, clazz)
            for (desugarer in DESUGARERS) {
                desugarer.desugar(project, desugaredClass)
            }
            desugaredClass
        }
    }

    private fun setOriginalRecursive(desugared: PsiElement, original: PsiElement) {
        val desugaredElements = mutableListOf<PsiElement>()
        desugared.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                desugaredElements.add(element)
            }
        })

        val originalElements = mutableListOf<PsiElement>()
        original.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                originalElements.add(element)
            }
        })

        for ((originalElement, desugaredElement) in originalElements.zip(desugaredElements)) {
            setOriginalElement(desugaredElement, originalElement)
        }
    }

    fun getOriginalToDesugaredMap(desugared: PsiElement): Map<PsiElement, List<PsiElement>> {
        val result = mutableMapOf<PsiElement, MutableList<PsiElement>>()
        desugared.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                getOriginalElement(element)?.let { original ->
                    result.getOrPut(original) { mutableListOf() } += desugared
                }
            }
        })
        return result
    }
}
