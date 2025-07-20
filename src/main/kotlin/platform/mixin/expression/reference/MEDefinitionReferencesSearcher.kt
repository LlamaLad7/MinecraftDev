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

package com.demonwav.mcdev.platform.mixin.expression.reference

import com.demonwav.mcdev.platform.mixin.expression.gen.psi.MEDeclaration
import com.demonwav.mcdev.platform.mixin.expression.gen.psi.MEName
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.RequestResultProcessor
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.startOffset
import com.intellij.util.Processor

/**
 * Custom searcher for ME definition references, as ReferencesSearch doesn't seem to work for injected files
 */
class MEDefinitionReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(
        params: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ) {
        val declaration = params.elementToSearch as? MEDeclaration ?: return
        val declName = declaration.name ?: return
        val injectedLanguageManager = InjectedLanguageManager.getInstance(params.project)
        val hostFile = injectedLanguageManager.getInjectionHost(declaration)?.containingFile ?: return
        params.optimizer.searchWord(
            declName,
            LocalSearchScope(hostFile),
            UsageSearchContext.IN_STRINGS,
            true,
            declaration,
            object : RequestResultProcessor() {
                override fun processTextOccurrence(
                    element: PsiElement,
                    offsetInElement: Int,
                    consumer: Processor<in PsiReference>
                ): Boolean {
                    val injectedElement = injectedLanguageManager.findInjectedElementAt(hostFile, element.startOffset + offsetInElement)
                        ?: return true
                    val meName = injectedElement.parentOfType<MEName>(true) ?: return true
                    val reference = meName.reference ?: return true
                    if (!reference.isReferenceTo(declaration)) {
                        return true
                    }
                    return consumer.process(reference)
                }
            }
        )
    }
}
