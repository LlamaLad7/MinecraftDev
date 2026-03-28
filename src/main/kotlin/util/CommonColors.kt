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

import com.intellij.ui.JBColor
import java.awt.Color

@Suppress("MemberVisibilityCanBePrivate")
object CommonColors {

    val DARK_RED = JBColor(0xAA0000, 0xAA0000)
    val RED = JBColor(0xFF5555, 0xFF5555)
    val GOLD = JBColor(0xFFAA00, 0xFFAA00)
    val YELLOW = JBColor(0xFFFF55, 0xFFFF55)
    val DARK_GREEN = JBColor(0x00AA00, 0x00AA00)
    val GREEN = JBColor(0x55FF55, 0x55FF55)
    val AQUA = JBColor(0x55FFFF, 0x55FFFF)
    val DARK_AQUA = JBColor(0x00AAAA, 0x00AAAA)
    val DARK_BLUE = JBColor(0x0000AA, 0x0000AA)
    val BLUE = JBColor(0x5555FF, 0x5555FF)
    val LIGHT_PURPLE = JBColor(0xFF55FF, 0xFF55FF)
    val DARK_PURPLE = JBColor(0xAA00AA, 0xAA00AA)
    val WHITE = JBColor(0xFFFFFF, 0xFFFFFF)
    val GRAY = JBColor(0xAAAAAA, 0xAAAAAA)
    val DARK_GRAY = JBColor(0x555555, 0x555555)
    val BLACK = JBColor(0x000000, 0x000000)

    fun applyStandardColors(map: MutableMap<String, Color>, prefix: String) {
        map.apply {
            put("$prefix.DARK_RED", DARK_RED)
            put("$prefix.RED", RED)
            put("$prefix.GOLD", GOLD)
            put("$prefix.YELLOW", YELLOW)
            put("$prefix.DARK_GREEN", DARK_GREEN)
            put("$prefix.GREEN", GREEN)
            put("$prefix.AQUA", AQUA)
            put("$prefix.DARK_AQUA", DARK_AQUA)
            put("$prefix.DARK_BLUE", DARK_BLUE)
            put("$prefix.BLUE", BLUE)
            put("$prefix.LIGHT_PURPLE", LIGHT_PURPLE)
            put("$prefix.DARK_PURPLE", DARK_PURPLE)
            put("$prefix.WHITE", WHITE)
            put("$prefix.GRAY", GRAY)
            put("$prefix.DARK_GRAY", DARK_GRAY)
            put("$prefix.BLACK", BLACK)
        }
    }
}
