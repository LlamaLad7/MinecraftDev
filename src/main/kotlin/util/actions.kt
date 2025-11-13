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

package com.demonwav.mcdev.util

import com.demonwav.mcdev.facet.MinecraftFacet
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.createSmartPointer
import com.intellij.ui.LightColors
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import java.util.Arrays
import javax.swing.JComponent

fun getDataFromActionEvent(e: AnActionEvent): ActionData? {
    fun findModuleForLibrary(file: PsiFile): Module? {
        val virtualFile = file.virtualFile ?: return null

        val index = ProjectFileIndex.getInstance(file.project)

        if (!index.isInLibrarySource(virtualFile) && !index.isInLibraryClasses(virtualFile)) {
            return null
        }

        val orderEntries = index.getOrderEntriesForFile(virtualFile)
        if (orderEntries.isEmpty()) {
            return null
        }

        if (orderEntries.size == 1) {
            return orderEntries[0].ownerModule
        }

        val ownerModules = orderEntries.map { it.ownerModule }.toTypedArray()
        Arrays.sort(ownerModules, ModuleManager.getInstance(file.project).moduleDependencyComparator())
        return ownerModules[0]
    }

    val project = e.project ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
    val caret = e.getData(CommonDataKeys.CARET) ?: return null
    val element = file.findElementAt(caret.offset) ?: return null
    val module = ModuleUtil.findModuleForPsiElement(element) ?: findModuleForLibrary(file) ?: return null
    val instance = MinecraftFacet.getInstance(module) ?: return null

    return ActionData(project, editor, file, element, caret, instance)
}

fun showBalloon(e: AnActionEvent, message: String) {
    val project = e.project ?: return
    val data = getDataFromActionEvent(e)
    showBalloon(project, data?.editor, data?.element, message)
}

fun showBalloon(project: Project, editor: Editor?, element: PsiElement?, message: String?) {
    if (message == null) {
        return
    }

    val balloon = JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(message, null, LightColors.YELLOW, null)
        .setHideOnAction(true)
        .setHideOnClickOutside(true)
        .setHideOnKeyOutside(true)
        .createBalloon()

    val elementPointer = element?.createSmartPointer()
    invokeLater {
        val element = elementPointer?.element
        if (element != null && editor != null) {
            val pos = editor.offsetToVisualPosition(element.textRange.endOffset - element.textLength / 2)
            val at = RelativePoint(
                editor.contentComponent,
                editor.visualPositionToXY(VisualPosition(pos.line + 1, pos.column)),
            )
            balloon.show(at, Balloon.Position.below)
            return@invokeLater
        }

        val statusBar = WindowManager.getInstance().getStatusBar(project)
        val statusBarComponent = statusBar.component
        if (statusBarComponent != null) {
            balloon.show(RelativePoint.getCenterOf(statusBarComponent), Balloon.Position.below)
            return@invokeLater
        }

        val focused = WindowManager.getInstance().getFocusedComponent(project)
        if (focused is JComponent) {
            balloon.show(RelativePoint.getCenterOf(focused), Balloon.Position.below)
            return@invokeLater
        }

        balloon.show(RelativePoint.fromScreen(Point()), Balloon.Position.below)
    }
}

fun showSuccessBalloon(editor: Editor, element: PsiElement, text: String) {
    val balloon = JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(text, null, LightColors.SLIGHTLY_GREEN, null)
        .setHideOnAction(true)
        .setHideOnClickOutside(true)
        .setHideOnKeyOutside(true)
        .createBalloon()

    val elementPointer = element.createSmartPointer()
    invokeLater {
        val element = elementPointer.element ?: return@invokeLater
        val pos = editor.offsetToVisualPosition(element.textRange.endOffset - element.textLength / 2)
        val at = RelativePoint(
            editor.contentComponent,
            editor.visualPositionToXY(VisualPosition(pos.line + 1, pos.column)),
        )

        balloon.show(at, Balloon.Position.below)
    }
}
