package com.studioitti.laravel.contrib

import com.studioitti.laravel.lang.CreateLangKeyFix
import com.studioitti.laravel.lang.LangKeyReference
import com.studioitti.laravel.lang.LangKeys
import com.studioitti.laravel.model.FieldPathReference
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import javax.swing.Icon
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import org.jetbrains.yaml.psi.YAMLScalar

private object Provider : PsiReferenceProvider() {
	override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> =
		References.of(element)
}

class YamlReferenceContributor : PsiReferenceContributor() {
	override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
		registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar::class.java), Provider)
	}
}

class JsReferenceContributor : PsiReferenceContributor() {
	override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
		registrar.registerReferenceProvider(PlatformPatterns.psiElement(JSLiteralExpression::class.java), Provider)
	}
}

class XmlReferenceContributor : PsiReferenceContributor() {
	override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
		registrar.registerReferenceProvider(PlatformPatterns.psiElement(XmlAttributeValue::class.java), Provider)
	}
}

class PhpReferenceContributor : PsiReferenceContributor() {
	override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
		registrar.registerReferenceProvider(PlatformPatterns.psiElement(StringLiteralExpression::class.java), Provider)
	}
}

/** Unknown translation keys, keys missing in some locale, unknown fields and relations. */
class ReferenceAnnotator : Annotator {
	override fun annotate(element: PsiElement, holder: AnnotationHolder) {
		if (element !is YAMLScalar && element !is JSLiteralExpression && element !is StringLiteralExpression && element !is XmlAttributeValue) return
		val refs = References.of(element)
		if (refs.isEmpty()) return
		val base = element.textRange.startOffset

		val lang = refs.filterIsInstance<LangKeyReference>()
		lang.lastOrNull()?.let { last ->
			val segments = last.segments
			if (!last.isLast || segments.size < 2 || segments.any { it.isEmpty() }) return@let
			val key = segments.joinToString(".")
			val range = TextRange(lang.first().rangeInElement.startOffset, last.rangeInElement.endOffset).shiftRight(base)
			val found = LangKeys.resolve(element, segments).map { it.locale }.toSet()
			val missing = LangKeys.locales(element).filter { it !in found }
			if (found.isEmpty()) {
				if (!LangKeys.inJson(element, key)) {
					holder.newAnnotation(HighlightSeverity.WARNING, "Unknown translation key '$key'").range(range)
						.withFix(CreateLangKeyFix(segments, missing)).create()
				}
				return@let
			}
			if (missing.isNotEmpty()) {
				holder.newAnnotation(HighlightSeverity.WEAK_WARNING, "Translation '$key' missing in: ${missing.joinToString(", ")}")
					.range(range).withFix(CreateLangKeyFix(segments, missing)).create()
			}
		}

		for (ref in refs.filterIsInstance<FieldPathReference>()) {
			val problem = ref.step?.problem ?: continue
			holder.newAnnotation(HighlightSeverity.WARNING, problem).range(ref.rangeInElement.shiftRight(base)).create()
		}

		for (ref in refs.filterIsInstance<TargetReference>()) {
			val range = ref.rangeInElement.shiftRight(base)
			if (ref.unknown != null && ref.resolved.isEmpty()) {
				holder.newAnnotation(ref.severity, ref.unknown).range(range).create()
			}
			ref.gutterIcon?.invoke()?.let { icon ->
				holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).gutterIconRenderer(IconGutter(icon, ref.value)).create()
			}
		}

		for (ref in refs.filterIsInstance<Targets.PermissionCheck>()) {
			for (problem in ref.problems()) {
				holder.newAnnotation(HighlightSeverity.WEAK_WARNING, problem).range(ref.rangeInElement.shiftRight(base)).create()
			}
		}

		for (ref in refs.filterIsInstance<Targets.ProblemCheck>()) {
			for (problem in ref.problems()) {
				holder.newAnnotation(HighlightSeverity.WARNING, problem).range(ref.rangeInElement.shiftRight(base)).create()
			}
		}
	}
}

private class IconGutter(private val icon: Icon, private val name: String) : GutterIconRenderer() {
	override fun getIcon(): Icon = icon
	override fun getTooltipText(): String = name
	override fun equals(other: Any?) = other is IconGutter && other.name == name
	override fun hashCode() = name.hashCode()
}

/** Typing `.` (or the `:` of `rel:`) inside one of our strings reopens completion. */
class DotAutoPopup : TypedHandlerDelegate() {
	override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
		if (charTyped != '.' && charTyped != ':') return Result.CONTINUE
		val at = file.findElementAt(maxOf(0, editor.caretModel.offset - 1)) ?: return Result.CONTINUE
		val host = PsiTreeUtil.getParentOfType(
			at, false, YAMLScalar::class.java, JSLiteralExpression::class.java, StringLiteralExpression::class.java,
		) ?: return Result.CONTINUE
		if (References.of(host).isNotEmpty()) {
			AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
		}
		return Result.CONTINUE
	}
}
