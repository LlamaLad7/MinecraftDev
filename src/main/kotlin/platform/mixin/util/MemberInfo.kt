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

package com.demonwav.mcdev.platform.mixin.util

import com.demonwav.mcdev.platform.mixin.reference.MixinSelector
import com.intellij.openapi.util.text.StringUtil
import org.objectweb.asm.Type

/**
 * Represents a Mixin MemberInfo.
 */
data class MemberInfo(
    val name: String,
    val descriptor: String? = null,
    override val owner: String? = null,
    val matchAllNames: Boolean = false,
    val matchAllDescs: Boolean = false,
) : MixinSelector {

    init {
        assert(owner?.contains('/') != true)
    }

    val withoutDescriptor
        get() = if (this.descriptor == null) {
            this
        } else {
            copy(descriptor = null)
        }

    val withoutOwner
        get() = if (this.owner == null) {
            this
        } else {
            copy(owner = null)
        }

    override val methodDescriptor = descriptor?.takeIf { it.contains("(") }
    override val fieldDescriptor = descriptor?.takeUnless { it.contains("(") }

    val presentableText: String get() = buildString {
        if (owner != null) {
            append(owner.substringAfterLast('.'))
            append('.')
        }
        append(name)
        if (descriptor != null && descriptor.startsWith("(")) {
            append('(')
            append(Type.getArgumentTypes(descriptor).joinToString { it.className.substringAfterLast('.') })
            append(')')
        }
    }

    override fun canEverMatch(name: String): Boolean {
        return matchAllNames || this.name == name
    }

    private fun matchOwner(clazz: String): Boolean {
        assert(!clazz.contains('.'))
        return this.owner == null || this.owner == clazz.replace('/', '.')
    }

    override fun matchField(owner: String, name: String, desc: String): Boolean {
        assert(!owner.contains('.'))
        return (this.matchAllNames || this.name == name) &&
            matchOwner(owner) &&
            (this.descriptor == null || this.descriptor == desc)
    }

    override fun matchMethod(owner: String, name: String, desc: String): Boolean {
        assert(!owner.contains('.'))
        return (this.matchAllNames || this.name == name) &&
            matchOwner(owner) &&
            (this.descriptor == null || this.descriptor == desc)
    }

    fun toMixinString(): String {
        return buildString {
            if (owner != null) {
                append('L').append(owner.replace('.', '/')).append(';')
            }

            append(if (matchAllNames) "*" else name)

            descriptor?.let { descriptor ->
                if (!descriptor.startsWith('(')) {
                    // Field descriptor
                    append(':')
                }

                append(descriptor)
            }
        }
    }

    companion object {
        fun parse(value: String): MemberInfo? {
            val reference = value.replace(" ", "")
            val owner: String?

            var pos = reference.lastIndexOf('.')
            if (pos != -1) {
                // Everything before the dot is the qualifier/owner
                owner = reference.substring(0, pos).replace('/', '.')
            } else {
                pos = reference.indexOf(';')
                if (pos != -1 && reference.startsWith('L')) {
                    val internalOwner = reference.substring(1, pos)
                    if (!StringUtil.isJavaIdentifier(internalOwner.replace('/', '_'))) {
                        // Invalid: Qualifier should only contain slashes
                        return null
                    }

                    owner = internalOwner.replace('/', '.')

                    // if owner is all there is to the selector, match anything with the owner
                    if (pos == reference.length - 1) {
                        return MemberInfo("", null, owner, matchAllNames = true, matchAllDescs = true)
                    }
                } else {
                    // No owner/qualifier specified
                    pos = -1
                    owner = null
                }
            }

            val descriptor: String?
            val name: String
            val matchAllNames = reference.getOrNull(pos + 1) == '*'
            val matchAllDescs: Boolean

            // Find descriptor separator
            val methodDescPos = reference.indexOf('(', pos + 1)
            if (methodDescPos != -1) {
                // Method descriptor
                descriptor = reference.substring(methodDescPos)
                name = reference.substring(pos + 1, methodDescPos)
                matchAllDescs = false
            } else {
                val fieldDescPos = reference.indexOf(':', pos + 1)
                if (fieldDescPos != -1) {
                    descriptor = reference.substring(fieldDescPos + 1)
                    name = reference.substring(pos + 1, fieldDescPos)
                    matchAllDescs = false
                } else {
                    descriptor = null
                    matchAllDescs = reference.endsWith('*')
                    name = if (matchAllDescs) {
                        reference.substring(pos + 1, reference.lastIndex)
                    } else {
                        reference.substring(pos + 1)
                    }
                }
            }

            if (!matchAllNames && !StringUtil.isJavaIdentifier(name) && name != "<init>" && name != "<clinit>") {
                return null
            }

            return MemberInfo(if (matchAllNames) "*" else name, descriptor, owner, matchAllNames, matchAllDescs)
        }
    }
}
