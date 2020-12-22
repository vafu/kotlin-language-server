package org.javacs.kt.symbols

import javaslang.collection.Seq
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.javacs.kt.SourcePath
import org.javacs.kt.position.range
import org.javacs.kt.util.containsCharactersInOrder
import org.javacs.kt.util.preOrderTraversal
import org.javacs.kt.util.toPath
import org.jetbrains.kotlin.com.intellij.lang.jvm.JvmModifier
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import java.util.concurrent.ConcurrentHashMap

fun documentSymbols(file: KtFile): List<Either<SymbolInformation, DocumentSymbol>> =
        doDocumentSymbols(file).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }

private fun doDocumentSymbols(element: PsiElement): List<DocumentSymbol> {
    val children = element.children.flatMap(::doDocumentSymbols)

    return pickImportantElements(element, true)?.let { currentDecl ->
        val file = element.containingFile
        val span = range(file.text, currentDecl.textRange)
        val nameSpan = currentDecl.let { range(file.text, it.textRange) } ?: span
        val symbol = DocumentSymbol(currentDecl.name ?: "<anonymous>", symbolKind(currentDecl), span, nameSpan, null, children)
        listOf(symbol)
        emptyList<DocumentSymbol>()
    } ?: children
}

fun workspaceSymbols(query: String, sp: SourcePath): List<SymbolInformation> =
        doWorkspaceSymbols(sp)
                .filter { containsCharactersInOrder(it.name.orEmpty(), query, true) }
                .mapNotNull(::symbolInformation)
                .take(10)
                .toList()

private fun doWorkspaceSymbols(sp: SourcePath): Sequence<PsiNamedElement> =
        sp.allSymbols().asSequence().flatMap(::fileSymbols)

private val psiFileElements = ConcurrentHashMap<PsiFile, Collection<PsiNamedElement>>()
private fun fileSymbols(file: PsiFile): Sequence<PsiNamedElement> =
    psiFileElements.getOrPut(file) {
        file.allChildren.toList().mapNotNull { pickImportantElements(it, false) }
    }.asSequence()

private fun pickImportantElements(node: PsiElement, includeLocals: Boolean): PsiNamedElement? =
        when (node) {
            is KtClassOrObject -> if (node.name == null) null else node
            is KtTypeAlias -> node
            is KtConstructor<*> -> node
            is KtNamedFunction -> if (!node.isLocal || includeLocals) node else null
            is KtProperty -> if (!node.isLocal || includeLocals) node else null
            is KtVariableDeclaration -> if (includeLocals) node else null
            is PsiClass -> node
            is PsiMethod -> node
            is PsiVariable -> if (node.hasModifier(JvmModifier.PUBLIC)) node else null
            else -> null
        }

private fun symbolInformation(d: PsiNamedElement): SymbolInformation? {
    val name = d.name ?: return null

    return SymbolInformation(name, symbolKind(d), symbolLocation(d), symbolContainer(d))
}

private fun symbolKind(d: PsiNamedElement): SymbolKind =
        when (d) {
            is PsiClass,
            is KtClassOrObject -> SymbolKind.Class
            is KtTypeAlias -> SymbolKind.Interface
            is KtConstructor<*> -> SymbolKind.Constructor
            is PsiMethod,
            is KtNamedFunction -> SymbolKind.Function
            is KtProperty -> SymbolKind.Property
            is PsiVariable,
            is KtVariableDeclaration -> SymbolKind.Variable
            else -> throw IllegalArgumentException("Unexpected symbol $d")
        }

private fun symbolLocation(d: PsiNamedElement): Location {
    val file = d.containingFile
    val uri = file.toPath().toUri().toString()
    val range = range(file.text, d.textRange)

    return Location(uri, range)
}

private fun symbolContainer(d: PsiNamedElement): String? =
        d.parents
                .filterIsInstance<KtNamedDeclaration>()
                .firstOrNull()
                ?.fqName.toString()

