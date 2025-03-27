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

package com.demonwav.mcdev.platform.mixin.insight.target

import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.hints.InlayHintsUtils
import com.intellij.codeInsight.hints.codeVision.CodeVisionProviderBase
import com.intellij.codeInsight.hints.settings.language.isInlaySettingsEditor
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SyntaxTraverser
import java.awt.event.MouseEvent

abstract class AbstractMixinTargetCodeVisionProvider : CodeVisionProviderBase() {
    override val relativeOrderings: List<CodeVisionRelativeOrdering>
        get() = listOf(
            CodeVisionRelativeOrdering.CodeVisionRelativeOrderingBefore("java.inheritors"),
            CodeVisionRelativeOrdering.CodeVisionRelativeOrderingBefore("java.references"),
            CodeVisionRelativeOrdering.CodeVisionRelativeOrderingBefore("vcs.code.vision"),
        )

    override fun computeForEditor(editor: Editor, file: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        // copied from superclass implementation, except without the check for libraries

        if (file.project.isDefault) {
            return emptyList()
        }
        if (!acceptsFile(file)) {
            return emptyList()
        }

        // we want to let this provider work only in tests dedicated for code vision, otherwise they harm performance
        if (ApplicationManager.getApplication().isUnitTestMode && !CodeVisionHost.isCodeLensTest()) {
            return emptyList()
        }

        val lenses = ArrayList<Pair<TextRange, CodeVisionEntry>>()
        val traverser = SyntaxTraverser.psiTraverser(file)
        for (element in traverser) {
            if (!acceptsElement(element)) {
                continue
            }
            if (!InlayHintsUtils.isFirstInLine(element)) {
                continue
            }
            val hint = getHint(element, file) ?: continue
            val handler = ClickHandler(element, hint)
            val range = InlayHintsUtils.getTextRangeWithoutLeadingCommentsAndWhitespaces(element)
            lenses.add(range to ClickableTextCodeVisionEntry(hint, id, handler))
        }
        return lenses
    }

    override fun acceptsFile(file: PsiFile) = file.language == JavaLanguage.INSTANCE

    private inner class ClickHandler(
        element: PsiElement,
        private val hint: String,
    ) : (MouseEvent?, Editor) -> Unit {
        private val elementPointer = SmartPointerManager.createPointer(element)

        override fun invoke(event: MouseEvent?, editor: Editor) {
            if (isInlaySettingsEditor(editor)) {
                return
            }
            val element = elementPointer.element ?: return
            logClickToFUS(element, hint)
            handleClick(editor, element, event)
        }
    }
}
