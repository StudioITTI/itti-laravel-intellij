package com.studioitti.laravel.lang

import com.studioitti.laravel.support.LaravelProject
import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/**
 * What a lang key segment resolves to: one locale's file or key, labelled `bg: Създаден от` in the
 * Ctrl+click chooser. The chooser renders a target's own presentation, and a PHP string literal's
 * is just its text — identical for every locale. Navigation goes to the real element.
 */
class LangTarget(val node: LangNode) : FakePsiElement() {
	val element: PsiElement = node.target

	override fun getParent(): PsiElement = element
	override fun getContainingFile(): PsiFile? = element.containingFile
	override fun getTextRange() = element.textRange
	override fun getTextOffset() = element.textOffset
	override fun getText(): String? = element.text
	override fun getNavigationElement(): PsiElement = element
	override fun isValid() = element.isValid
	override fun isPhysical() = false

	override fun getName(): String = presentableText
	override fun getPresentableText(): String {
		val text = if (node.hash == null) node.file.name else node.text ?: "{…}"
		return "${node.locale}: ${text.take(80)}"
	}
	override fun getLocationString(): String? {
		val root = LaravelProject.root(element) ?: return null
		return node.file.virtualFile?.let { VfsUtilCore.getRelativePath(it, root) }
	}
	override fun getIcon(open: Boolean): Icon = if (node.hash == null) AllIcons.FileTypes.Any_type else AllIcons.Nodes.Property

	override fun canNavigate() = true
	override fun navigate(requestFocus: Boolean) = (element as? Navigatable)?.navigate(requestFocus) ?: Unit
	override fun navigationRequest(): NavigationRequest? = (element as? Navigatable)?.navigationRequest()

	override fun equals(other: Any?) = other is LangTarget && other.element == element
	override fun hashCode() = element.hashCode()
}
