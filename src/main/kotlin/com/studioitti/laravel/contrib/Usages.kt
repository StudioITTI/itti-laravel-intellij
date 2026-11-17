package com.studioitti.laravel.contrib

import com.studioitti.laravel.lang.LangKeyReference
import com.studioitti.laravel.lang.LangKeys
import com.studioitti.laravel.model.Models
import com.studioitti.laravel.support.LaravelProject
import com.studioitti.laravel.support.PhpArrays
import com.studioitti.laravel.support.VueSlots
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.xml.XmlAttribute
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.util.Processor
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.ParameterList
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable
import org.jetbrains.yaml.psi.YAMLKeyValue

/**
 * The strings our references point at that are declarations in their own right: a lang key, a migration
 * column, a mail placeholder, a permission map entry. Each carries a reference to itself, so Find Usages and
 * Rename can start from it — PHP has no notion of a string literal being a declaration.
 */
object Declarations {
	enum class Kind(val renamable: Boolean) { LANG(true), COLUMN(true), PLACEHOLDER(true), PERMISSION(false) }

	private val LANG_FILE = Regex("^(resources/)?lang/[^/]+/[^/]+\\.php$")
	private val PERMISSION_FILE = Regex("^database/permissions(\\.php|/[^/]+\\.php)$")

	fun kind(literal: StringLiteralExpression): Kind? {
		val path = relativePath(literal) ?: return null
		val hash = hashOfKey(literal)
		return when {
			hash != null && LANG_FILE.matches(path) -> Kind.LANG
			hash != null && PERMISSION_FILE.matches(path) -> Kind.PERMISSION
			hash != null && path == "config/mail-templates.php" &&
				Targets.ownerHash(hash.parent)?.let { PhpArrays.keyOf(it) } == "placeholders" -> Kind.PLACEHOLDER
			path.startsWith("database/migrations/") && isColumnName(literal) -> Kind.COLUMN
			else -> null
		}
	}

	private fun relativePath(element: PsiElement): String? {
		val vf = element.containingFile?.originalFile?.virtualFile ?: return null
		val root = LaravelProject.root(element) ?: return null
		return VfsUtilCore.getRelativePath(vf, root)
	}

	/** The hash whose KEY this literal is. */
	private fun hashOfKey(literal: StringLiteralExpression): ArrayHashElement? {
		val hash = literal.parent as? ArrayHashElement ?: literal.parent?.parent as? ArrayHashElement ?: return null
		return hash.takeIf { PhpArrays.unwrap(it.key) == literal }
	}

	/** `$table->string('code')`: the first argument of a blueprint call. */
	private fun isColumnName(literal: StringLiteralExpression): Boolean {
		val params = literal.parent as? ParameterList ?: return false
		val call = params.parent as? MethodReference ?: return false
		return call.classReference is Variable && params.parameters.firstOrNull() == literal
	}

	/** A lang key's dotted path, from its file and the keys around it. */
	fun langKey(literal: StringLiteralExpression): List<String>? {
		val keys = ArrayList<String>()
		var e: PsiElement? = literal
		while (e != null && e !is PsiFile) {
			if (e is ArrayHashElement) keys += PhpArrays.keyOf(e) ?: return null
			e = e.parent
		}
		val group = literal.containingFile.virtualFile?.nameWithoutExtension ?: return null
		return listOf(group) + keys.reversed()
	}

	/** Everything renamed together: a lang key in every locale, otherwise the literal alone. */
	fun group(literal: StringLiteralExpression): List<StringLiteralExpression> {
		if (kind(literal) != Kind.LANG) return listOf(literal)
		val key = langKey(literal) ?: return listOf(literal)
		return LangKeys.resolve(literal, key).mapNotNull { it.target as? StringLiteralExpression }.ifEmpty { listOf(literal) }
	}
}

/** A declaration's reference to itself: Find Usages and Rename start here. Never a usage of anything. */
class DeclarationReference(literal: StringLiteralExpression, range: TextRange) : PsiReferenceBase<PsiElement>(literal, range, true) {
	override fun resolve(): PsiElement = element
	override fun isReferenceTo(element: PsiElement) = false
}

/** Finds our string references for the PHP, YAML and Vue elements they point at. */
class UsageSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
	override fun processQuery(params: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
		val target = params.elementToSearch
		val words = words(target)
		if (words.isEmpty()) return
		val scope = params.effectiveSearchScope
		val markup = restrict(scope, "yaml", "yml", "js", "ts", "vue")
		val php = restrict(scope, "php")
		for (word in words) {
			params.optimizer.searchWord(word, markup, UsageSearchContext.ANY, true, target)
			params.optimizer.searchWord(word, php, UsageSearchContext.IN_STRINGS, true, target)
		}
	}

	private fun restrict(scope: SearchScope, vararg extensions: String): SearchScope {
		if (scope !is GlobalSearchScope) return scope
		val types = extensions.map { FileTypeManager.getInstance().getFileTypeByExtension(it) }.distinct().toTypedArray()
		return GlobalSearchScope.getScopeRestrictedByFileTypes(scope, *types)
	}

	companion object {
		private val LEGACY_ACCESSOR = Regex("^get(\\w+)Attribute$")

		/** The words a usage of `target` contains; empty for elements that are none of ours. */
		fun words(target: PsiElement): List<String> {
			if (LaravelProject.root(target) == null) return emptyList()
			return when (target) {
				is Method -> {
					if (target.containingClass == null) return emptyList()
					val legacy = LEGACY_ACCESSOR.find(target.name)?.groupValues?.get(1)
					listOfNotNull(target.name, legacy?.let { Models.snake(it) }, Models.snake(target.name)).distinct()
				}
				is YAMLKeyValue -> if (target.containingFile.name.endsWith("Schema.yaml")) listOf(target.keyText) else emptyList()
				is StringLiteralExpression -> if (Declarations.kind(target) != null) listOf(target.contents.substringAfterLast('.').substringAfterLast('@')) else emptyList()
				is XmlAttribute -> VueSlots.slotAt(target.nameElement ?: return emptyList())?.let { listOf(it.substringAfterLast('.')) } ?: emptyList()
				else -> emptyList()
			}.filter { it.isNotEmpty() }
		}
	}
}

/** Find Usages on the elements PHP / YAML / HTML would not search themselves. */
class UsagesHandlerFactory : FindUsagesHandlerFactory() {
	override fun canFindUsages(element: PsiElement): Boolean = when (element) {
		is StringLiteralExpression -> Declarations.kind(element) != null
		is YAMLKeyValue, is XmlAttribute -> UsageSearcher.words(element).isNotEmpty()
		else -> false
	}

	override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler =
		object : FindUsagesHandler(element) {}
}

/** Rename a lang key (in every locale), a migration column or a mail placeholder, and the strings that use it. */
class DeclarationRenameHandler : RenameHandler {
	override fun isAvailableOnDataContext(dataContext: DataContext): Boolean = declarations(dataContext).isNotEmpty()

	override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext) {
		val declarations = declarations(dataContext)
		val old = declarations.firstOrNull()?.contents ?: return
		val name = Messages.showInputDialog(project, "Rename '$old' and its usages to:", "Rename", null, old, null)
		if (name.isNullOrBlank() || name == old) return
		rename(project, declarations, name)
	}

	override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext) =
		invoke(project, null, null, dataContext)

	private fun declarations(dataContext: DataContext): List<StringLiteralExpression> {
		val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return emptyList()
		val file = CommonDataKeys.PSI_FILE.getData(dataContext) ?: return emptyList()
		return declarationsAt(file, editor.caretModel.offset)
	}

	companion object {
		/** The declarations a caret stands on: one itself, or a usage of a lang key's last segment. */
		fun declarationsAt(file: PsiFile, offset: Int): List<StringLiteralExpression> {
			val leaf = file.findElementAt(offset) ?: return emptyList()
			val refs = generateSequence(leaf) { it.parent }.take(4).flatMap { e ->
				e.references.filter { it.rangeInElement.shiftRight(e.textRange.startOffset).containsOffset(offset) }
			}
			for (ref in refs) {
				when {
					ref is DeclarationReference -> {
						val literal = ref.element as StringLiteralExpression
						if (Declarations.kind(literal)?.renamable == true) return Declarations.group(literal)
					}
					ref is LangKeyReference && ref.isLast ->
						return LangKeys.resolve(ref.element, ref.segments).mapNotNull { it.target as? StringLiteralExpression }
				}
			}
			return emptyList()
		}

		fun rename(project: Project, declarations: List<StringLiteralExpression>, newName: String) {
			val scope = GlobalSearchScope.projectScope(project)
			// found before anything changes; one per string and range, last first within a file
			val usages = declarations.flatMap { ReferencesSearch.search(it, scope).findAll() }
				.distinctBy { it.element to it.rangeInElement }
				.sortedByDescending { it.element.textRange.startOffset + it.rangeInElement.startOffset }
			WriteCommandAction.runWriteCommandAction(project, "Rename", null, {
				for (usage in usages) if (usage.element.isValid) usage.handleElementRename(newName)
				for (d in declarations) if (d.isValid) ElementManipulators.handleContentChange(d, newName)
				PsiDocumentManager.getInstance(project).commitAllDocuments()
			})
		}
	}
}
