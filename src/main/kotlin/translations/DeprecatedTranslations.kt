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

package com.demonwav.mcdev.translations

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.io.InputStreamReader
import java.io.IOException
import java.nio.charset.StandardCharsets

class DeprecatedTranslations private constructor(val removed: Set<String>, val renamed: Map<String, String>) {
    val inverseRenamed = renamed.entries.associateBy({ it.value }) { it.key }

    val isEmpty
        get() = removed.isEmpty() && renamed.isEmpty()

    companion object {
        private val DEFAULT = DeprecatedTranslations(emptySet(), emptyMap())
        private const val FILE_PATH = "assets/minecraft/lang/deprecated.json"

        fun getInstance(project: Project): DeprecatedTranslations {
            return CachedValuesManager.getManager(project).getCachedValue(project) {
                val file = findFile(project) ?: return@getCachedValue CachedValueProvider.Result(
                    DEFAULT,
                    ProjectRootModificationTracker.getInstance(project)
                )
                CachedValueProvider.Result(getInstanceFromFile(file), file)
            }
        }

        private fun findFile(project: Project): VirtualFile? {
            for (libraryRoot in OrderEnumerator.orderEntries(project).librariesOnly().classes().roots) {
                val file = libraryRoot.findFileByRelativePath(FILE_PATH) ?: continue
                if (!file.isDirectory) {
                    return file
                }
            }

            return null
        }

        private fun getInstanceFromFile(file: VirtualFile): DeprecatedTranslations {
            try {
                val rootElement = InputStreamReader(file.inputStream, StandardCharsets.UTF_8).use { reader ->
                     JsonParser.parseReader(reader)
                }

                if (rootElement == null || !rootElement.isJsonObject) {
                    return DEFAULT
                }

                val obj = rootElement.asJsonObject

                val removedElt = obj.get("removed")
                val removed = if (removedElt != null && removedElt.isJsonArray) {
                    val removedArr = removedElt.asJsonArray
                    removedArr.mapNotNullTo(HashSet.newHashSet(removedArr.size())) { e ->
                        if (e.isJsonPrimitive && e.asJsonPrimitive.isString) {
                            e.asString
                        } else {
                            null
                        }
                    }
                } else {
                    emptySet()
                }

                val renamedElt = obj.get("renamed")
                val renamed = if (renamedElt != null && renamedElt.isJsonObject) {
                    val renamedObj = renamedElt.asJsonObject
                    renamedObj.asMap().asSequence().mapNotNull { (k, v) ->
                        if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                            k to v.asString
                        } else {
                            null
                        }
                    }.toMap(HashMap.newHashMap(renamedObj.size()))
                } else {
                    emptyMap()
                }

                return DeprecatedTranslations(removed, renamed)
            } catch (_: IOException) {
                return DEFAULT
            }
        }
    }
}
