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

package com.demonwav.mcdev.platform.bukkit.framework

import com.demonwav.mcdev.asset.PlatformAssets
import com.demonwav.mcdev.util.localFile
import com.google.gson.Gson
import com.intellij.framework.library.LibraryVersionProperties
import com.intellij.openapi.roots.libraries.LibraryPresentationProvider
import com.intellij.openapi.vfs.VirtualFile
import java.util.jar.JarFile

class ModernPaperPresentationProvider : LibraryPresentationProvider<LibraryVersionProperties>(PAPER_LIBRARY_KIND) {

    override fun getIcon(properties: LibraryVersionProperties?) = PlatformAssets.PAPER_ICON

    override fun detect(classesRoots: List<VirtualFile>): LibraryVersionProperties? {
        for (classesRoot in classesRoots) {
            if (!classesRoot.name.endsWith(".jar")) {
                continue
            }
            runCatching {
                JarFile(classesRoot.localFile).use { jar ->
                    jar.getEntry("io/papermc/paper/ServerBuildInfo.class") ?: return@runCatching
                    val versionJson = jar.getEntry("apiVersioning.json") ?: return@runCatching
                    jar.getInputStream(versionJson).use { stream ->
                        stream.reader().use { reader ->
                            val map = Gson().fromJson(reader, Map::class.java)
                            return LibraryVersionProperties(map["currentApiVersion"] as String)
                        }
                    }
                }
            }
        }
        return null
    }
}
