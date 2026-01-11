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

package com.demonwav.mcdev.creator.custom.types

import com.demonwav.mcdev.creator.custom.CreatorContext
import com.demonwav.mcdev.creator.custom.TemplatePropertyDescriptor
import com.demonwav.mcdev.util.MinecraftVersions
import com.demonwav.mcdev.util.SemanticVersion
import com.intellij.ui.dsl.builder.Panel
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

open class PaperVersionCreatorProperty(
    descriptor: TemplatePropertyDescriptor,
    context: CreatorContext
) : SemanticVersionCreatorProperty(descriptor, context) {

    @OptIn(DelicateCoroutinesApi::class)
    companion object {
        val paperVersions: Map<SemanticVersion, String>? = loadPaperVersions()?.associate { it to it.toString() }

        fun loadPaperVersions(): List<SemanticVersion>? {
            val client = HttpClient()
            return runBlocking {
                val response = client.get("https://fill.papermc.io/v3/projects/paper")
                if (response.status.isSuccess()) {
                    val element = Json.parseToJsonElement(response.bodyAsText())
                    return@runBlocking element.jsonObject["versions"]?.jsonObject?.values
                        ?.asSequence()
                        ?.flatMap { it.jsonArray }
                        ?.map { it.jsonPrimitive.content }
                        ?.mapNotNull { SemanticVersion.tryParse(it) }
                        // only release versions
                        ?.filter { ver -> ver.parts.all { it is SemanticVersion.Companion.VersionPart.ReleasePart } }
                        // nothing lower than 1.18.2 should be selectable
                        ?.filter { it >= MinecraftVersions.MC1_18_2 }
                        ?.toList()
                        ?.sortedDescending()
                } else {
                    return@runBlocking null
                }
            }
        }
    }

    override fun buildUi(panel: Panel) {
        super.buildDropdownMenu(panel, paperVersions)
    }

    class Factory : CreatorPropertyFactory {
        override fun create(
            descriptor: TemplatePropertyDescriptor,
            context: CreatorContext
        ): CreatorProperty<*> = PaperVersionCreatorProperty(descriptor, context)
    }
}
