package com.studioitti.laravel.support

import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpPsiElement
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/** The Laravel app a file belongs to: the nearest ancestor directory holding `artisan`. */
object LaravelProject {
	fun root(context: PsiElement): VirtualFile? {
		var dir = context.containingFile?.originalFile?.virtualFile?.parent
		while (dir != null) {
			if (dir.findChild("artisan") != null) return dir
			dir = dir.parent
		}
		return context.project.guessProjectDir()?.takeIf { it.findChild("artisan") != null }
	}

	fun psiFile(context: PsiElement, relativePath: String): PsiFile? {
		val vf = root(context)?.findFileByRelativePath(relativePath) ?: return null
		return PsiManager.getInstance(context.project).findFile(vf)
	}
}

/** Reading the `return [...]` arrays of lang and config files. */
object PhpArrays {
	fun returnedArray(file: PsiFile): ArrayCreationExpression? =
		CachedValuesManager.getCachedValue(file) {
			val ret = PsiTreeUtil.findChildrenOfType(file, PhpReturn::class.java)
				.firstOrNull { PsiTreeUtil.getParentOfType(it, Function::class.java) == null }
			CachedValueProvider.Result.create(unwrap(ret?.argument) as? ArrayCreationExpression, file)
		}

	/** An ARRAY_KEY / ARRAY_VALUE wrapper stands in for its one expression. */
	fun unwrap(e: PsiElement?): PsiElement? {
		var cur = e
		repeat(2) {
			if (cur is ArrayCreationExpression || cur is StringLiteralExpression) return cur
			val wrapper = cur as? PhpPsiElement ?: return cur
			if (wrapper.javaClass.simpleName != "PhpPsiElementImpl") return cur
			cur = wrapper.firstPsiChild ?: return cur
		}
		return cur
	}

	fun keyOf(h: ArrayHashElement): String? = (unwrap(h.key) as? StringLiteralExpression)?.contents

	fun entries(array: ArrayCreationExpression): List<Pair<String, ArrayHashElement>> =
		array.hashElements.mapNotNull { h -> keyOf(h)?.let { it to h } }

	fun entry(array: ArrayCreationExpression, key: String): ArrayHashElement? =
		array.hashElements.firstOrNull { keyOf(it) == key }

	fun value(h: ArrayHashElement): PsiElement? = unwrap(h.value)

	/** `config('a.b.c')`: the expression stored under that key. */
	fun configValue(context: PsiElement, key: String): PsiElement? {
		val parts = key.split('.')
		val file = LaravelProject.psiFile(context, "config/${parts[0]}.php") ?: return null
		var array = returnedArray(file) ?: return null
		var value: PsiElement? = array
		for (part in parts.drop(1)) {
			val h = entry(array, part) ?: return null
			value = value(h)
			array = value as? ArrayCreationExpression ?: if (part == parts.last()) break else return null
		}
		return value
	}
}
