package ru.wds.wdl.idea;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.lang.*;
import com.intellij.lexer.Lexer;
import com.intellij.lexer.LexerBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.*;
import org.jetbrains.annotations.NotNull;

/** Minimal PSI for IDE run actions. Syntax and diagnostics remain owned by the LSP server. */
public final class WdlParserDefinition implements ParserDefinition {
    private static final IFileElementType FILE = new IFileElementType(WdlLanguage.INSTANCE);
    private static final IElementType TEXT = new IElementType("WDL_TEXT", WdlLanguage.INSTANCE);

    @Override public @NotNull Lexer createLexer(Project project) {
        return new LexerBase() {
            private CharSequence buffer = "";
            private int start;
            private int end;
            @Override public void start(@NotNull CharSequence text, int startOffset, int endOffset, int state) {
                buffer = text; start = startOffset; end = endOffset;
            }
            @Override public int getState() { return 0; }
            @Override public IElementType getTokenType() { return start < end ? TEXT : null; }
            @Override public int getTokenStart() { return start; }
            @Override public int getTokenEnd() { return end; }
            @Override public void advance() { start = end; }
            @Override public @NotNull CharSequence getBufferSequence() { return buffer; }
            @Override public int getBufferEnd() { return end; }
        };
    }
    @Override public @NotNull PsiParser createParser(Project project) {
        return (root, builder) -> {
            PsiBuilder.Marker marker = builder.mark();
            while (!builder.eof()) builder.advanceLexer();
            marker.done(root);
            return builder.getTreeBuilt();
        };
    }
    @Override public @NotNull IFileElementType getFileNodeType() { return FILE; }
    @Override public @NotNull TokenSet getWhitespaceTokens() { return TokenSet.EMPTY; }
    @Override public @NotNull TokenSet getCommentTokens() { return TokenSet.EMPTY; }
    @Override public @NotNull TokenSet getStringLiteralElements() { return TokenSet.EMPTY; }
    @Override public @NotNull PsiElement createElement(ASTNode node) { return new ASTWrapperPsiElement(node); }
    @Override public @NotNull PsiFile createFile(@NotNull FileViewProvider provider) {
        return new PsiFileBase(provider, WdlLanguage.INSTANCE) {
            @Override public @NotNull FileType getFileType() { return WdlFileType.INSTANCE; }
        };
    }
}
