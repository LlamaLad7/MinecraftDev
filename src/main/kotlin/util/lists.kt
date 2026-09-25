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

fun <T> sharedPrefixLength(lists: List<List<T>>): Int {
    if (lists.isEmpty()) {
        return 0
    }
    lists.singleOrNull()?.let { return it.size }

    val firstList = lists.first()

    for (i in firstList.indices) {
        val targetElement = firstList[i]

        if (lists.any { i >= it.size || it[i] != targetElement }) {
            return i
        }
    }

    return firstList.size
}

