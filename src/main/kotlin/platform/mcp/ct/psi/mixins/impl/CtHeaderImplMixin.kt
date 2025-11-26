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

package com.demonwav.mcdev.platform.mcp.ct.psi.mixins.impl

import com.demonwav.mcdev.platform.mcp.ct.gen.psi.CtTypes
import com.demonwav.mcdev.platform.mcp.ct.psi.mixins.CtHeaderMixin
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement

abstract class CtHeaderImplMixin(node: ASTNode) : ASTWrapperPsiElement(node), CtHeaderMixin {
    companion object {
        private val LOG = logger<CtHeaderImplMixin>()
    }

    override val nameString: String
        get() = findNotNullChildByType<PsiElement>(CtTypes.HEADER_NAME).text

    override val versionString: String?
        get() = findChildByType<PsiElement>(CtTypes.HEADER_VERSION_ELEMENT)?.text

    override val namespaceString: String?
        get() = findChildByType<PsiElement>(CtTypes.HEADER_NAMESPACE_ELEMENT)?.text

    override val effectiveVersion: Int?
        get() {
            val version = versionString?.removePrefix("v")?.toIntOrNull() ?: return null
            return when (nameString) {
                "accessWidener" -> version
                "classTweaker" -> version + 2
                else -> {
                    LOG.error("Unknown header name: $nameString")
                    null
                }
            }
        }
}
