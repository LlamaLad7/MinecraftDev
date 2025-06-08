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

package com.demonwav.mcdev.platform.mixin.desugar

import com.demonwav.mcdev.platform.mixin.handlers.desugar.RemoveVarArgsDesugarer
import org.junit.jupiter.api.Test

class RemoveVarArgsDesugarTest : AbstractDesugarTest() {
    override val desugarer = RemoveVarArgsDesugarer

    @Test
    fun testSimple() {
        doTest(
            """
                class Test {
                    static void foo(String... args) {
                    }
                    
                    static void test() {
                        foo("a", "b", "c")
                    }
                }
            """.trimIndent(),
            """
                class Test {
                    static void foo(String[] args) {
                    }
                    
                    static void test() {
                        foo(new String[] {"a", "b", "c"})
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testNoArgs() {
        doTest(
            """
                class Test {
                    static void foo(String... args) {
                    }
                    
                    static void test() {
                        foo();
                    }
                }
            """.trimIndent(),
            """
                class Test {
                    static void foo(String[] args) {                
                    }
                    
                    static void test() {
                        foo(new String[] {});
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testGenericMethod() {
        doTest(
            """
                class Test {
                    @SafeVarargs
                    static <T> void foo(T... args) {
                    }
                    
                    static void test() {
                        foo("a", "b", "c");
                    }
                }
            """.trimIndent(),
            """
                class Test {
                    @SafeVarargs
                    static <T> void foo(T[] args) {
                    }
                    
                    static void test() {
                        foo(new String[] {"a", "b", "c"});
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testGenericClass() {
        @Suppress("unchecked")
        doTest(
            """
                class Test<T> {
                    @SafeVarargs
                    static void foo(T... args) {
                    }
                    
                    static void test() {
                        foo("a", "b", "c");
                    }
                }
            """.trimIndent(),
            """
                class Test<T> {
                    @SafeVarargs
                    static void foo(T[] args) {
                    }
                    
                    static void test() {
                        foo((T[]) new Object[] {"a", "b", "c"});
                    }
                }
            """.trimIndent()
        )
    }
}
