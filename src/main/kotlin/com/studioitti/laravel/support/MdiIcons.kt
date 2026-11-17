package com.studioitti.laravel.support

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/** Material Design Icons from the project's own `node_modules/@mdi/font`: names, codepoints, glyphs. */
object MdiIcons {
	private val NAME = Regex("^mdi-[a-z0-9-]*$")

	/** `mdi-…`, also mid-completion, when the value still carries the dummy identifier. */
	fun isName(text: String): Boolean =
		NAME.matches(text.replace(com.intellij.codeInsight.completion.CompletionUtil.DUMMY_IDENTIFIER_TRIMMED, "").trimEnd())
	private val ENTRY = Regex("\"([a-z0-9-]+)\": ([0-9A-Fa-f]+)")
	private const val BASE = "node_modules/@mdi/font"
	private val KEY = Key.create<com.intellij.psi.util.CachedValue<Index>>("itti.mdi")

	class Index(val variables: PsiFile, val codepoints: Map<String, Int>, val offsets: Map<String, Int>, val font: Font?)

	fun index(context: PsiElement): Index? {
		val root = LaravelProject.root(context) ?: return null
		val vf = root.findFileByRelativePath("$BASE/scss/_variables.scss") ?: return null
		val file = PsiManager.getInstance(context.project).findFile(vf) ?: return null
		return CachedValuesManager.getManager(context.project).getCachedValue(file, KEY, {
			val codepoints = LinkedHashMap<String, Int>()
			val offsets = HashMap<String, Int>()
			for (m in ENTRY.findAll(file.text)) {
				codepoints[m.groupValues[1]] = m.groupValues[2].toInt(16)
				offsets[m.groupValues[1]] = m.range.first + 1
			}
			CachedValueProvider.Result.create(Index(file, codepoints, offsets, font(root.findFileByRelativePath("$BASE/fonts/materialdesignicons-webfont.ttf"))), file)
		}, false)
	}

	private val fonts = HashMap<String, Font?>()

	private fun font(ttf: VirtualFile?): Font? {
		if (ttf == null) return null
		return synchronized(fonts) {
			fonts.getOrPut(ttf.path + "@" + ttf.timeStamp) {
				runCatching { ttf.inputStream.use { Font.createFont(Font.TRUETYPE_FONT, it) } }.getOrNull()
			}
		}
	}

	fun icon(index: Index, name: String, size: Int = 16): Icon? {
		val codepoint = index.codepoints[name.removePrefix("mdi-")] ?: return null
		val font = index.font ?: return null
		return GlyphIcon(font, codepoint, size)
	}

	private class GlyphIcon(private val font: Font, private val codepoint: Int, private val size: Int) : Icon {
		override fun getIconWidth() = JBUI.scale(size)
		override fun getIconHeight() = JBUI.scale(size)

		override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
			val g2 = g.create() as Graphics2D
			try {
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
				g2.font = font.deriveFont(JBUI.scale(size).toFloat())
				g2.color = JBColor.foreground()
				val text = String(Character.toChars(codepoint))
				val fm = g2.fontMetrics
				val dx = (iconWidth - fm.stringWidth(text)) / 2
				val dy = (iconHeight - fm.height) / 2 + fm.ascent
				g2.drawString(text, x + dx, y + dy)
			}
			finally {
				g2.dispose()
			}
		}

		override fun equals(other: Any?) = other is GlyphIcon && other.codepoint == codepoint && other.size == size
		override fun hashCode() = codepoint * 31 + size
	}
}
