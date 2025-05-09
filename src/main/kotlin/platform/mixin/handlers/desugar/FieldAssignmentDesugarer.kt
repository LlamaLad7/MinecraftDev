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

import com.demonwav.mcdev.util.constantValue
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiType
import com.intellij.util.JavaPsiConstructorUtil

object FieldAssignmentDesugarer : Desugarer {
    override fun desugar(project: Project, clazz: PsiClass) {
        val staticStatementsToInsertPre = mutableListOf<PsiStatement>()
        val staticStatementsToInsertPost = mutableListOf<PsiStatement>()
        val nonStaticStatementsToInsert = mutableListOf<PsiStatement>()
        var seenStaticInitializer = false

        for (child in clazz.children) {
            when (child) {
                is PsiField -> {
                    val initializer = child.initializer ?: continue

                    if (child.hasModifierProperty(PsiModifier.STATIC)) {
                        // check if the field is a ConstantValue with no initializer in the bytecode
                        val constantValue = initializer.constantValue
                        if (constantValue != null && constantValue !is PsiType) {
                            continue
                        }

                        val fieldInitializer = JavaPsiFacade.getElementFactory(project)
                            .createStatementFromText("${child.name} = null;", child) as PsiExpressionStatement
                        (fieldInitializer.expression as PsiAssignmentExpression).rExpression!!.replace(initializer)
                        DesugarUtil.setOriginalElement(fieldInitializer, DesugarUtil.getOriginalElement(child))

                        if (seenStaticInitializer) {
                            staticStatementsToInsertPost += fieldInitializer
                        } else {
                            staticStatementsToInsertPre += fieldInitializer
                        }
                    } else {
                        val fieldInitializer = JavaPsiFacade.getElementFactory(project)
                            .createStatementFromText("this.${child.name} = null;", child) as PsiExpressionStatement
                        (fieldInitializer.expression as PsiAssignmentExpression).rExpression!!.replace(initializer)
                        DesugarUtil.setOriginalElement(fieldInitializer, DesugarUtil.getOriginalElement(child))

                        nonStaticStatementsToInsert += fieldInitializer
                    }

                    initializer.delete()
                }
                is PsiClassInitializer -> {
                    if (child.hasModifierProperty(PsiModifier.STATIC)) {
                        seenStaticInitializer = true
                    } else {
                        nonStaticStatementsToInsert += child.body.statements
                        child.delete()
                    }
                }
            }
        }

        if (staticStatementsToInsertPre.isNotEmpty() || staticStatementsToInsertPost.isNotEmpty()) {
            val staticBlock = findStaticBlock(project, clazz)
            for (statement in staticStatementsToInsertPre) {
                staticBlock.body.addAfter(statement, staticBlock.body.lBrace)
            }
            for (statement in staticStatementsToInsertPost) {
                staticBlock.body.addBefore(statement, staticBlock.body.rBrace)
            }
        }

        if (nonStaticStatementsToInsert.isNotEmpty()) {
            for (constructor in findConstructorsCallingSuper(project, clazz)) {
                val body = constructor.body ?: continue
                val delegateCtorCall = JavaPsiConstructorUtil.findThisOrSuperCallInConstructor(constructor)
                for (statement in nonStaticStatementsToInsert.asReversed()) {
                    body.addAfter(statement, delegateCtorCall?.parent ?: body.lBrace)
                }
            }
        }
    }

    private fun findStaticBlock(project: Project, clazz: PsiClass): PsiClassInitializer {
        for (initializer in clazz.initializers) {
            if (initializer.hasModifierProperty(PsiModifier.STATIC)) {
                return initializer
            }
        }

        val initializer = JavaPsiFacade.getElementFactory(project)
            .createClass("class _Dummy_ { static {} }")
            .initializers
            .first()
        DesugarUtil.setOriginalElement(initializer, DesugarUtil.getOriginalElement(clazz))
        return clazz.add(initializer) as PsiClassInitializer
    }

    private fun findConstructorsCallingSuper(project: Project, clazz: PsiClass): List<PsiMethod> {
        val className = clazz.name ?: return emptyList()

        val constructors = clazz.constructors.filter {
            !JavaPsiConstructorUtil.isChainedConstructorCall(
                JavaPsiConstructorUtil.findThisOrSuperCallInConstructor(it)
            )
        }

        if (constructors.isNotEmpty()) {
            return constructors
        }

        val constructor = JavaPsiFacade.getElementFactory(project).createConstructor(className)
        DesugarUtil.setOriginalElement(constructor, DesugarUtil.getOriginalElement(clazz))
        return listOf(clazz.add(constructor) as PsiMethod)
    }
}
