/*
 * Minecraft Development for IntelliJ
 *
 * https://mcdev.io/
 *
 * Copyright (C) 2024 minecraft-dev
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

package com.demonwav.mcdev.platform.mixin.expression;

import com.demonwav.mcdev.platform.mixin.expression.gen.psi.MEExpressionTypes;
import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.TokenType;

%%

%public
%class MEExpressionLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{ return;
%eof}

WHITE_SPACE = [\ \n\t\r]
RESERVED = assert|break|case|catch|const|continue|default|else|finally|for|goto|if|switch|synchronized|try|while|yield|_
WILDCARD = "?"
NEW = new
INSTANCEOF = instanceof
BOOL_LIT = true|false
NULL_LIT = null
DO = do
RETURN = return
THROW = throw
THIS = this
SUPER = super
CLASS = class
IDENTIFIER = [A-Za-z_][A-Za-z0-9_]*
INT_LIT = ( [0-9]+ | 0x[0-9a-fA-F]+ )
DEC_LIT = [0-9]*\.[0-9]+
PLUS = "+"
MINUS = -
MULT = "*"
DIV = "/"
MOD = %
BITWISE_NOT = "~"
DOT = "."
COMMA = ,
LEFT_PAREN = "("
RIGHT_PAREN = ")"
LEFT_BRACKET = "["
RIGHT_BRACKET = "]"
LEFT_BRACE = "{"
RIGHT_BRACE = "}"
AT = @
SHL = <<
SHR = >>
USHR = >>>
LT = <
LE = <=
GT = >
GE = >=
EQ = ==
NE = "!="
BITWISE_AND = &
BITWISE_XOR = "^"
BITWISE_OR = "|"
ASSIGN = =

STRING_TERMINATOR = '
STRING_ESCAPE = \\'|\\\\

%state STRING

%%

<YYINITIAL> {
    {WHITE_SPACE}+ { yybegin(YYINITIAL); return TokenType.WHITE_SPACE; }
    {RESERVED} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_RESERVED; }
    {WILDCARD} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_WILDCARD; }
    {NEW} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_NEW; }
    {INSTANCEOF} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_INSTANCEOF; }
    {BOOL_LIT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_BOOL_LIT; }
    {NULL_LIT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_NULL_LIT; }
    {DO} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_DO; }
    {RETURN} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_RETURN; }
    {THROW} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_THROW; }
    {THIS} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_THIS; }
    {SUPER} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_SUPER; }
    {CLASS} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_CLASS; }
    {IDENTIFIER} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_IDENTIFIER; }
    {INT_LIT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_INT_LIT; }
    {DEC_LIT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_DEC_LIT; }
    {PLUS} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_PLUS; }
    {MINUS} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_MINUS; }
    {MULT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_MULT; }
    {DIV} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_DIV; }
    {MOD} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_MOD; }
    {BITWISE_NOT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_BITWISE_NOT; }
    {DOT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_DOT; }
    {COMMA} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_COMMA; }
    {LEFT_PAREN} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_LEFT_PAREN; }
    {RIGHT_PAREN} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_RIGHT_PAREN; }
    {LEFT_BRACKET} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_LEFT_BRACKET; }
    {RIGHT_BRACKET} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_RIGHT_BRACKET; }
    {LEFT_BRACE} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_LEFT_BRACE; }
    {RIGHT_BRACE} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_RIGHT_BRACE; }
    {AT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_AT; }
    {SHL} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_SHL; }
    {SHR} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_SHR; }
    {USHR} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_USHR; }
    {LT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_LT; }
    {LE} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_LE; }
    {GT} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_GT; }
    {GE} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_GE; }
    {EQ} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_EQ; }
    {NE} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_NE; }
    {BITWISE_AND} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_BITWISE_AND; }
    {BITWISE_XOR} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_BITWISE_XOR; }
    {BITWISE_OR} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_BITWISE_OR; }
    {ASSIGN} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_ASSIGN; }
    {STRING_TERMINATOR} { yybegin(STRING); return MEExpressionTypes.TOKEN_STRING_TERMINATOR; }
}

<STRING> {
    {STRING_ESCAPE} { yybegin(STRING); return MEExpressionTypes.TOKEN_STRING_ESCAPE; }
    {STRING_TERMINATOR} { yybegin(YYINITIAL); return MEExpressionTypes.TOKEN_STRING_TERMINATOR; }
    [^'\\]+ { yybegin(STRING); return MEExpressionTypes.TOKEN_STRING; }
}

[^] { return TokenType.BAD_CHARACTER; }
