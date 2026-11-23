package com.studioitti.laravel.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Adds a frontend key to the JSON lang files lacking it (SPEC §10): after the object's last entry, with the comma
 * that entry now needs. The value is another locale's text, or the last segment made readable.
 */
class CreateJsonKeyFix(private val key: String, private val locales: List<String>) : IntentionAction {
	override fun getText() = "Add '$key' to ${locales.joinToString(", ")}"
	override fun getFamilyName() = "Add translation key"
	override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true
	override fun startInWriteAction() = true

	override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
		create(file ?: return, key, locales)
	}

	companion object {
		fun create(context: PsiElement, key: String, locales: List<String>) {
			val value = JsonKeys.resolve(context, key).firstNotNullOfOrNull { it.text }
				?: key.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
			val documents = PsiDocumentManager.getInstance(context.project)
			for ((_, file) in JsonKeys.files(context).filter { it.first in locales }.distinctBy { it.first }) {
				documents.commitAllDocuments()
				val obj = file.topLevelValue as? JsonObject ?: continue
				if (obj.findProperty(key) != null) continue
				val document = documents.getDocument(file) ?: continue
				insert(document, obj, "${quote(key)}: ${quote(value)}")
				documents.commitDocument(document)
			}
		}

		private fun insert(document: Document, obj: JsonObject, entry: String) {
			val last = obj.propertyList.lastOrNull()
			if (last == null) {
				document.replaceString(obj.textRange.startOffset + 1, obj.textRange.endOffset - 1, "\n    $entry\n")
				return
			}
			val start = document.getLineStartOffset(document.getLineNumber(last.textRange.startOffset))
			val indent = document.getText(TextRange(start, last.textRange.startOffset)).takeWhile { it == ' ' || it == '\t' }
			document.insertString(last.textRange.endOffset, ",\n$indent$entry")
		}

		private fun quote(s: String): String = buildString {
			append('"')
			for (c in s) when {
				c == '"' -> append("\\\"")
				c == '\\' -> append("\\\\")
				c == '\n' -> append("\\n")
				c == '\t' -> append("\\t")
				c < ' ' -> append("\\u%04x".format(c.code))
				else -> append(c)
			}
			append('"')
		}
	}
}
