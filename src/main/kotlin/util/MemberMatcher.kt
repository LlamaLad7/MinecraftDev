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

package com.demonwav.mcdev.util

import com.demonwav.mcdev.platform.mixin.util.FieldTargetMember
import com.demonwav.mcdev.platform.mixin.util.MethodTargetMember
import com.demonwav.mcdev.platform.mixin.util.MixinTargetMember
import com.demonwav.mcdev.platform.mixin.util.bytecode
import com.demonwav.mcdev.platform.mixin.util.findField
import com.demonwav.mcdev.platform.mixin.util.findMethod
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode

/**
 * An interface which matches members, that's it really.
 */
interface MemberMatcher {
    fun matchField(owner: String, name: String, desc: String): Boolean
    fun matchMethod(owner: String, name: String, desc: String): Boolean

    fun matchField(field: PsiField, qualifier: PsiClass): Boolean {
        if (!canEverMatch(field.name)) {
            return false
        }
        val fqn = qualifier.fullQualifiedName ?: return false
        val desc = field.descriptor ?: return false
        return matchField(fqn.replace('.', '/'), field.name, desc)
    }

    fun matchField(field: FieldNode, qualifier: ClassNode): Boolean {
        return matchField(qualifier.name, field.name, field.desc)
    }

    fun matchMethod(method: PsiMethod, qualifier: PsiClass): Boolean {
        if (!canEverMatch(method.internalName)) {
            return false
        }
        val fqn = qualifier.fullQualifiedName ?: return false
        val desc = method.descriptor ?: return false
        return matchMethod(fqn.replace('.', '/'), method.internalName, desc)
    }

    fun matchMethod(method: MethodNode, qualifier: ClassNode): Boolean {
        return matchMethod(qualifier.name, method.name, method.desc)
    }

    fun getCustomOwner(owner: ClassNode): ClassNode {
        return owner
    }

    /**
     * Implement this to return false for early-out optimizations, so you don't need to resolve the member in the
     * navigation visitor
     */
    fun canEverMatch(name: String): Boolean {
        return true
    }

    val owner: String?
    val methodDescriptor: String?
    val fieldDescriptor: String?
    val qualified
        get() = owner != null

    fun resolve(
        project: Project,
        scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
    ): Pair<PsiClass, PsiMember>? {
        return resolve(project, scope, ::Pair)
    }

    fun resolveMember(project: Project, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): PsiMember? {
        return resolve(project, scope) { _, member -> member }
    }

    fun resolveAsm(
        project: Project,
        scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
    ): MixinTargetMember? {
        val owner = this.owner ?: return null

        fun doFind(owner: String): MixinTargetMember? {
            if (owner == CommonClassNames.JAVA_LANG_OBJECT) {
                return null
            }
            return RecursionManager.doPreventingRecursion(owner, false) {
                val classNode = findQualifiedClass(project, owner, scope)?.bytecode ?: return@doPreventingRecursion null

                classNode.findMethod(this)?.let {
                    return@doPreventingRecursion MethodTargetMember(classNode, it)
                }

                classNode.findField(this)?.let {
                    return@doPreventingRecursion FieldTargetMember(classNode, it)
                }

                classNode.superName?.let { doFind(it.replace('/', '.')) }?.let { return@doPreventingRecursion it }

                classNode.interfaces?.let { interfaces ->
                    for (itf in interfaces) {
                        doFind(itf.replace('/', '.'))?.let { return@doPreventingRecursion it }
                    }
                }

                null
            }
        }

        return doFind(owner)
    }

    private inline fun <R> resolve(project: Project, scope: GlobalSearchScope, ret: (PsiClass, PsiMember) -> R): R? {
        val owner = this.owner ?: return null

        val psiClass = findQualifiedClass(project, owner, scope) ?: return null

        val field = psiClass.findField(this, checkBases = true)
        return if (field != null) {
            ret(psiClass, field)
        } else {
            psiClass.findMethods(this, checkBases = true).firstOrNull()?.let { ret(psiClass, it) }
        }
    }
}
