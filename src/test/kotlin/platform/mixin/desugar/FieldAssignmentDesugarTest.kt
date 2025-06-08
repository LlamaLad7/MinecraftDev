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

import com.demonwav.mcdev.platform.mixin.handlers.desugar.FieldAssignmentDesugarer
import org.junit.jupiter.api.Test

@Suppress("InstantiationOfUtilityClass")
class FieldAssignmentDesugarTest : AbstractDesugarTest() {
    override val desugarer = FieldAssignmentDesugarer

    @Test
    fun testStatic() {
        doTest(
            """
                class Test {
                    static final Test INSTANCE = new Test();
                }
            """.trimIndent(),
            """
                class Test {
                    static final Test INSTANCE;
                    static {
                        INSTANCE = new Test();
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testNonStatic() {
        doTest(
            """
                class Test {
                    final String field = "test";
                }
            """.trimIndent(),
            """
                class Test {
                    final String field;
                    public Test() {
                        this.field = "test";
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testStaticExistingStaticBlock() {
        doTest(
            """
                class Test {
                    static final Test INSTANCE = new Test();
                    
                    static {
                        System.out.println("Hello world!");
                    }
                }
            """.trimIndent(),
            """
                class Test {
                    static final Test INSTANCE;
                    
                    static {
                        INSTANCE = new Test();
                        System.out.println("Hello world!");
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testNonStaticExistingConstructor() {
        doTest(
            """
                class Test {
                    final String field = "test";
                    
                    Test() {
                        System.out.println("Hello world!");
                    }
                }
            """.trimIndent(),
            """
                class Test {
                    final String field;
                    
                    Test() {
                        this.field = "test";
                        System.out.println("Hello world!");
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testNonStaticExistingConstructorWithSuperCall() {
        doTest(
            """
                class Test {
                    final String field = "test";
                    
                    Test() {
                        super();
                        System.out.println("Hello world!");
                    }
                }
            """.trimIndent(),
            """
                class Test {
                    final String field;
                    
                    Test() {
                        super();
                        this.field = "test";
                        System.out.println("Hello world!");
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testNonStaticExistingConstructorWithThisCall() {
        doTest(
            """
                class Test {
                    final String field = "test";
                    
                    Test() {
                        System.out.println("Hello world!");
                    }
                    
                    Test(int i) {
                        this();
                    }
                }
            """.trimIndent(),
            """
                class Test {
                    final String field;
                    
                    Test() {
                        this.field = "test";
                        System.out.println("Hello world!");
                    }
                    
                    Test(int i) {
                        this();
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testNonStaticMultipleExistingConstructors() {
        doTest(
            """
                class Test {
                    final String field = "test";
                    
                    Test() {
                        System.out.println("Hello world!");
                    }
                    
                    Test(int i) {
                    }
                }
            """.trimIndent(),
            """
                class Test {
                    final String field;
                    
                    Test() {
                        this.field = "test";
                        System.out.println("Hello world!");
                    }
                    
                    Test(int i) {
                        this.field = "test";
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testStaticConstant() {
        doTestNoChange("""
            class Test {
                static final String FIELD = "test";
            }
        """.trimIndent())
    }

    @Test
    fun testNonFinal() {
        doTest(
            """
                class Test {
                    static String staticField = "test";
                    String nonStaticField = "test";
                }
            """.trimIndent(),
            """
                class Test {
                    static String staticField;
                    
                    static {
                        staticField = "test";
                    }
                    
                    String nonStaticField;
                    
                    public Test() {
                        this.nonStaticField = "test";
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testClassIsNotConstant() {
        doTest(
            """
                class Test {
                    static final Class<?> CONSTANT = Test.class;
                }
            """.trimIndent(),
            """
                class Test {
                    static final Class<?> CONSTANT;
                    
                    static {
                        CONSTANT = Test.class;
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testStaticFieldOrder() {
        doTest(
            """
                class Test {
                    static final Test FIELD1 = new Test();
                    
                    static {
                        System.out.println("Hello World!");
                    }
                    
                    static final Test FIELD2 = new Test();
                }
            """.trimIndent(),
            """
                class Test {
                    static final Test FIELD1;
                    
                    static {
                        FIELD1 = new Test();
                        System.out.println("Hello World!");
                        FIELD2 = new Test();
                    }
                    
                    static final Test FIELD2;
                }
            """.trimIndent()
        )
    }

    @Test
    fun testNonStaticClassInitializer() {
        doTest(
            """
                class Test {
                    final String field1 = "test1";
                    {
                        System.out.println("Hello World!");
                    }
                    final String field2 = "test2";
                }
            """.trimIndent(),
            """
                class Test {
                    final String field1;
                    
                    final String field2;
                    
                    public Test() {
                        this.field1 = "test1";
                        {
                            System.out.println("Hello World!");
                        }
                        this.field2 = "test2";
                    }
                }
            """.trimIndent()
        )
    }
}
