package com.studioitti.laravel.lang

import com.studioitti.laravel.support.DotContinuation
import com.studioitti.laravel.support.LaravelProject
import com.studioitti.laravel.support.PhpArrays
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/** One locale's view of a key prefix: the group file, or an entry inside it. */
class LangNode(val locale: String, val file: PhpFile, val hash: ArrayHashElement?) {
	val array: ArrayCreationExpression?
		get() = if (hash == null) PhpArrays.returnedArray(file) else PhpArrays.value(hash) as? ArrayCreationExpression

	val target: PsiElement get() = hash?.let { PhpArrays.unwrap(it.key) } ?: file

	val text: String? get() = hash?.let { (PhpArrays.value(it) as? StringLiteralExpression)?.contents }
}

/** Laravel's PHP lang files: `resources/lang/<locale>/<group>.php` (or `lang/<locale>/...`). */
object LangKeys {
	private val LANG_DIRS = listOf("resources/lang", "lang")

	fun localeDirs(context: PsiElement): List<Pair<String, PsiDirectory>> {
		val root = LaravelProject.root(context) ?: return emptyList()
		val manager = PsiManager.getInstance(context.project)
		return LANG_DIRS.mapNotNull { root.findFileByRelativePath(it) }
			.flatMap { base -> base.children.filter { it.isDirectory && it.name != "vendor" } }
			.sortedWith(compareBy({ it.name != "en" }, { it.name }))
			.mapNotNull { dir -> manager.findDirectory(dir)?.let { dir.name to it } }
	}

	fun locales(context: PsiElement): List<String> = localeDirs(context).map { it.first }.distinct()

	fun resolve(context: PsiElement, segments: List<String>): List<LangNode> {
		if (segments.isEmpty()) return emptyList()
		var nodes = localeDirs(context).mapNotNull { (locale, dir) ->
			(dir.findFile(segments[0] + ".php") as? PhpFile)?.let { LangNode(locale, it, null) }
		}
		for (segment in segments.drop(1)) {
			nodes = nodes.mapNotNull { node ->
				node.array?.let { PhpArrays.entry(it, segment) }?.let { LangNode(node.locale, node.file, it) }
			}
		}
		return nodes
	}

	fun variants(context: PsiElement, parent: List<String>): List<LookupElement> {
		if (parent.isEmpty()) {
			return localeDirs(context)
				.flatMap { (_, dir) -> dir.files.filterIsInstance<PhpFile>().map { it.virtualFile.nameWithoutExtension } }
				.distinct()
				.map {
					LookupElementBuilder.create(it).withIcon(AllIcons.FileTypes.Any_type)
						.withTypeText("lang group").withInsertHandler(DotContinuation)
				}
		}
		val seen = LinkedHashMap<String, LookupElement>()
		for (node in resolve(context, parent)) {
			val array = node.array ?: continue
			for ((key, hash) in PhpArrays.entries(array)) {
				if (key in seen) continue
				val value = PhpArrays.value(hash)
				seen[key] = if (value is ArrayCreationExpression) {
					LookupElementBuilder.create(key).withIcon(AllIcons.Nodes.Folder)
						.withTypeText("{…}").withInsertHandler(DotContinuation)
				}
				else {
					val text = (value as? StringLiteralExpression)?.contents ?: value?.text ?: ""
					LookupElementBuilder.create(key).withIcon(AllIcons.Nodes.Property)
						.withTypeText(text.take(60), true)
				}
			}
		}
		return seen.values.toList()
	}

	/** A key used whole in `<locale>.json` (no groups); only consulted before calling a key unknown. */
	fun inJson(context: PsiElement, key: String): Boolean {
		val root = LaravelProject.root(context) ?: return false
		val needle = "\"" + key.replace("\"", "\\\"") + "\""
		return LANG_DIRS.mapNotNull { root.findFileByRelativePath(it) }
			.flatMap { base -> base.children.filter { it.extension == "json" } }
			.any { VfsUtilCore.loadText(it).contains(needle) }
	}

	/** One reference per dot-separated segment of `key`, which sits at `range` inside `element`. */
	fun references(element: PsiElement, key: String, range: TextRange): Array<PsiReference> {
		val trimmed = key.trimEnd()
		if (trimmed.isEmpty() || trimmed.contains("::") || trimmed.any { it.isWhitespace() || it == '/' || it == '$' || it == '{' }) {
			return PsiReference.EMPTY_ARRAY
		}
		val segments = trimmed.split('.')
		val refs = ArrayList<PsiReference>()
		var offset = range.startOffset
		segments.forEachIndexed { i, segment ->
			if (segment.isNotEmpty()) {
				refs += LangKeyReference(element, TextRange(offset, offset + segment.length), segments, i)
			}
			offset += segment.length + 1
		}
		return refs.toTypedArray()
	}
}
