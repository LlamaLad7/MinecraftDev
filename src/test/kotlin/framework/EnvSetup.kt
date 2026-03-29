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

package com.demonwav.mcdev.framework

import com.intellij.openapi.application.PathManager
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

class EnvSetup : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        // Assign each worker to a different fork directory. The worker ids are not indexed from 0, they are random
        // numbers. They are sequential, though, so we can just use modulo to turn whatever arbitrary ids we get into
        // valid indexes.
        val worker =
            (System.getProperty("org.gradle.test.worker") ?: "1").toInt() % System.getProperty("forks").toInt()
        val container = Path(System.getProperty("sandboxDir"))

        val configDir = container.resolve("config-fork-$worker")
        val systemDir = container.resolve("system-fork-$worker")
        val pluginsDir = container.resolve("plugins-fork-$worker")
        val logsDir = container.resolve("log-fork-$worker")

        System.setProperty(PathManager.PROPERTY_CONFIG_PATH, configDir.absolutePathString())
        System.setProperty(PathManager.PROPERTY_SYSTEM_PATH, systemDir.absolutePathString())
        System.setProperty(PathManager.PROPERTY_PLUGINS_PATH, pluginsDir.absolutePathString())
        System.setProperty(PathManager.PROPERTY_LOG_PATH, logsDir.absolutePathString())
    }
}
