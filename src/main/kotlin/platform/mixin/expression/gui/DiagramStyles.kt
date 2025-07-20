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

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.mxgraph.util.mxConstants
import java.awt.Color

object DiagramStyles {
    val DEFAULT_NODE
        get() = mapOf(
            mxConstants.STYLE_FONTFAMILY to CURRENT_EDITOR_FONT.family,
            mxConstants.STYLE_ROUNDED to true,
            mxConstants.STYLE_FILLCOLOR to JBUI.CurrentTheme.Button.buttonColorStart().hexString,
            mxConstants.STYLE_FONTCOLOR to UIUtil.getLabelForeground().hexString,
            mxConstants.STYLE_STROKECOLOR to JBUI.CurrentTheme.Button.buttonOutlineColorStart(false).hexString,
            mxConstants.STYLE_ALIGN to mxConstants.ALIGN_CENTER,
            mxConstants.STYLE_VERTICAL_ALIGN to mxConstants.ALIGN_TOP,
            mxConstants.STYLE_SHAPE to mxConstants.SHAPE_LABEL,
            mxConstants.STYLE_SPACING to 5,
            mxConstants.STYLE_SPACING_TOP to 3,
        )
    val DEFAULT_EDGE
        get() = mapOf(
            mxConstants.STYLE_STROKECOLOR to UIUtil.getFocusedBorderColor().hexString,
        )
    val LINE_NUMBER = mapOf(
        mxConstants.STYLE_FONTSIZE to "16",
        mxConstants.STYLE_STROKECOLOR to "none",
        mxConstants.STYLE_FILLCOLOR to "none",
    )
    val SEARCH_HIGHLIGHT
        get() = mapOf(
            mxConstants.STYLE_STROKECOLOR to UIUtil.getFocusedBorderColor().hexString,
            mxConstants.STYLE_STROKEWIDTH to "2",
        )
    val IGNORED = mapOf(
        mxConstants.STYLE_OPACITY to 20,
        mxConstants.STYLE_TEXT_OPACITY to 20,
        mxConstants.STYLE_STROKE_OPACITY to 20,
        mxConstants.STYLE_FILL_OPACITY to 20,
    )
    val FAILED
        get() = mapOf(
            mxConstants.STYLE_STROKECOLOR to JBColor.red.hexString,
            mxConstants.STYLE_STROKEWIDTH to "3.5",
        )
    val PARTIAL_MATCH
        get() = mapOf(
            mxConstants.STYLE_STROKECOLOR to JBColor.orange.hexString,
            mxConstants.STYLE_STROKEWIDTH to "2.5",
        )
    val SUCCESS
        get() = mapOf(
            mxConstants.STYLE_STROKECOLOR to JBColor.green.hexString,
            mxConstants.STYLE_STROKEWIDTH to "1.5",
        )
    val CURRENT_EDITOR_FONT
        get() = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
}

private val Color.hexString get() = "#%06X".format(rgb)
