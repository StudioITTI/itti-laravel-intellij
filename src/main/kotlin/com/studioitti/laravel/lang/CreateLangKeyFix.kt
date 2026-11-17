package com.studioitti.laravel.lang

import com.studioitti.laravel.support.PhpArrays
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression

/**
 * Adds a translation key to the locales that lack it. The value is the text another locale already has,
 * or the last segment made readable (`created_by` → `Created by`) to be translated afterwards.
 */
class CreateLangKeyFix(private val segments: List<String>, private val locales: List<String>) : IntentionAction {
	private val key get() = segments.joinToString(".")

	override fun getText() = "Add '$key' to ${locales.joinToString(", ")}"
	override fun getFamilyName() = "Add translation key"
	override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = segments.size >= 2
	override fun startInWriteAction() = true

	override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
		create(file ?: return, segments, locales)
	}

	companion object {
		fun create(context: PsiElement, segments: List<String>, locales: List<String>) {
			val value = LangKeys.resolve(context, segments).firstNotNullOfOrNull { it.text } ?: readable(segments.last())
			val documents = PsiDocumentManager.getInstance(context.project)
			for ((_, dir) in LangKeys.localeDirs(context).filter { it.first in locales }.distinctBy { it.first }) {
				val file = dir.findFile(segments[0] + ".php") as? PhpFile ?: continue
				documents.commitAllDocuments()
				var array = PhpArrays.returnedArray(file) ?: continue
				var i = 1
				while (i < segments.lastIndex) {
					array = PhpArrays.entry(array, segments[i])?.let { PhpArrays.value(it) } as? ArrayCreationExpression ?: break
					i++
				}
				if (i == segments.lastIndex && PhpArrays.entry(array, segments.last()) != null) continue
				val entry = segments.subList(i, segments.lastIndex)
					.foldRight("${quote(segments.last())} => ${quote(value)}") { seg, inner -> "${quote(seg)} => [$inner]" }
				val document = documents.getDocument(file) ?: continue
				insert(document, array, entry)
				documents.commitDocument(document)
			}
		}

		/** After the array's last entry, on a line of its own, with the trailing comma the project keeps. */
		private fun insert(document: Document, array: ArrayCreationExpression, entry: String) {
			val close = array.textRange.endOffset - 1
			val last = array.hashElements.lastOrNull()
			if (last == null) {
				val indent = indentAt(document, array.textRange.startOffset)
				val open = document.getText(array.textRange).indexOfFirst { it == '[' || it == '(' } + array.textRange.startOffset
				document.replaceString(open + 1, close, "\n$indent\t$entry,\n$indent")
				return
			}
			val end = last.textRange.endOffset
			val indent = indentAt(document, last.textRange.startOffset)
			val comma = document.getText(TextRange(end, close)).indexOf(',')
			if (comma >= 0) document.insertString(end + comma + 1, "\n$indent$entry,")
			else document.insertString(end, ",\n$indent$entry,")
		}

		private fun indentAt(document: Document, offset: Int): String {
			val start = document.getLineStartOffset(document.getLineNumber(offset))
			return document.getText(TextRange(start, offset)).takeWhile { it == ' ' || it == '\t' }
		}

		private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

		private fun readable(segment: String) = segment.replace('_', ' ').replaceFirstChar { it.uppercase() }
	}
}
