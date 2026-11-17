package com.studioitti.laravel.model

import com.studioitti.laravel.support.LaravelProject
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/** How a domain's pieces find each other: model ↔ controller (constructor type) ↔ Inertia pages. */
object Domains {
	private const val BASE_CONTROLLER = "\\App\\Http\\Controllers\\BaseInertiaCRUDController"
	private val PAGE = Regex("^Admin/[A-Za-z0-9_/]+$")
	val SCHEMA_FILES = listOf("ModelSchema.yaml", "ListSchema.yaml", "EditSchema.yaml")

	fun isController(cls: PhpClass): Boolean =
		generateSequence(cls.superClass) { it.superClass }.any { it.fqn == BASE_CONTROLLER }

	/** The model a CRUD controller edits: the type of its constructor's first parameter. */
	fun modelOf(controller: PhpClass): PhpClass? {
		val param = controller.constructor?.parameters?.firstOrNull() ?: return null
		val index = PhpIndex.getInstance(controller.project)
		return param.declaredType.types.firstNotNullOfOrNull { index.getClassesByFQN(it).firstOrNull() }
	}

	fun controllers(context: PsiElement): List<PhpClass> {
		val out = ArrayList<PhpClass>()
		PhpIndex.getInstance(context.project).processAllSubclasses(BASE_CONTROLLER) { out += it; true }
		return out
	}

	fun controllersOf(model: PhpClass): List<PhpClass> =
		controllers(model).filter { modelOf(it)?.fqn == model.fqn }

	/** The Vue pages a controller renders, from the `Admin/<Domain>/<Page>` literals in it. */
	fun pagesOf(controller: PhpClass): List<PsiFile> {
		val manager = PsiManager.getInstance(controller.project)
		val root = LaravelProject.root(controller) ?: return emptyList()
		return PsiTreeUtil.findChildrenOfType(controller, StringLiteralExpression::class.java)
			.map { it.contents }.filter { PAGE.matches(it) }.distinct()
			.mapNotNull { root.findFileByRelativePath("resources/js/Pages/$it.vue")?.let(manager::findFile) }
	}

	fun schemaFiles(model: PhpClass): List<PsiFile> =
		SCHEMA_FILES.mapNotNull { model.containingFile?.containingDirectory?.findFile(it) }

	fun pagesOfModel(model: PhpClass): List<PsiFile> = controllersOf(model).flatMap { pagesOf(it) }.distinct()

	fun routeOf(controller: PhpClass, context: PsiElement): PsiElement? =
		com.studioitti.laravel.support.Routes.index(context).resources.values.firstOrNull { name ->
			val call = name.parent?.parent
			call != null && PsiTreeUtil.findChildrenOfType(call, com.jetbrains.php.lang.psi.elements.ClassReference::class.java)
				.any { it.fqn == controller.fqn }
		}
}
