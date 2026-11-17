package com.studioitti.laravel.support

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.ForeachStatement
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

private val KEYS = java.util.concurrent.ConcurrentHashMap<String, Key<CachedValue<*>>>()

/** One cached value per (directory, index): the lambda class alone would be shared by every index. */
private fun <T> cachedOn(context: PsiElement, relativePath: String, empty: T, build: (PsiDirectory) -> T): T {
	val vf = LaravelProject.root(context)?.findFileByRelativePath(relativePath) ?: return empty
	val dir = PsiManager.getInstance(context.project).findDirectory(vf) ?: return empty
	@Suppress("UNCHECKED_CAST")
	val key = KEYS.computeIfAbsent(relativePath) { Key.create("itti.index.$it") } as Key<CachedValue<T>>
	return CachedValuesManager.getManager(dir.project).getCachedValue(dir, key, {
		CachedValueProvider.Result.create(build(dir), PsiModificationTracker.getInstance(dir.project))
	}, false)
}

private fun PsiDirectory.allFiles(): List<PsiFile> = files.toList() + subdirectories.flatMap { it.allFiles() }

/** Route names declared in `routes/`: `->name('…')` and the routes `itti_resource_route()` registers. */
object Routes {
	private val RESOURCE_ACTIONS = listOf("index", "create", "store", "show", "edit", "update", "destroy")

	/** `patterns`: names interpolated from something the index cannot list, `$x` matching any one segment. */
	class Index(val routes: Map<String, PsiElement>, val resources: Map<String, StringLiteralExpression>, val patterns: List<Pair<Regex, PsiElement>> = emptyList()) {
		fun route(name: String): PsiElement? = routes[name] ?: patterns.firstOrNull { it.first.matches(name) }?.second
	}

	fun index(context: PsiElement): Index = cachedOn(context, "routes", Index(emptyMap(), emptyMap())) { dir ->
		val routes = LinkedHashMap<String, PsiElement>()
		val resources = LinkedHashMap<String, StringLiteralExpression>()
		val patterns = ArrayList<Pair<Regex, PsiElement>>()
		val files = dir.allFiles().filterIsInstance<PhpFile>()
		val filePrefixes = filePrefixes(files, dir)
		for (file in files) {
			val filePrefix = filePrefixes[VfsUtilCore.getRelativePath(file.virtualFile, dir.virtualFile)].orEmpty()
			for (call in PsiTreeUtil.findChildrenOfType(file, FunctionReference::class.java)) {
				val first = call.parameters.firstOrNull() as? StringLiteralExpression ?: continue
				when {
					call is MethodReference && call.name == "name" && !isGroupPrefix(call) -> {
						val prefix = filePrefix + enclosingPrefix(call)
						val names = expand(first)
						if (names != null) names.forEach { routes.putIfAbsent(prefix + it, first) }
						else patterns += Regex(Regex.escape(prefix) + VAR.split(first.contents).joinToString("[^.]+") { Regex.escape(it) }) to first
					}
					call !is MethodReference && call.name == "itti_resource_route" -> {
						val name = filePrefix + enclosingPrefix(call) + first.contents
						resources.putIfAbsent(name, first)
						val only = (call.parameters.getOrNull(2) as? ArrayCreationExpression)
							?.let { arr -> PsiTreeUtil.findChildrenOfType(arr, StringLiteralExpression::class.java).map { it.contents } }
						(only ?: RESOURCE_ACTIONS).forEach { routes.putIfAbsent("$name.$it", first) }
						routes.putIfAbsent("$name.exportList", first)
					}
				}
			}
		}
		Index(routes, resources, patterns)
	}

	private val VAR = Regex("""\{?\$([A-Za-z_]\w*)\}?""")

	/** A name with `$var` in it, once per value of the `foreach` that sets each var; null when one cannot be listed. */
	private fun expand(literal: StringLiteralExpression): List<String>? {
		val text = literal.contents
		if (literal.isSingleQuote || !VAR.containsMatchIn(text)) return listOf(text)
		var out = listOf(text)
		for (name in VAR.findAll(text).map { it.groupValues[1] }.distinct()) {
			val values = loopValues(literal, name) ?: return null
			val use = Regex("""\{?\$$name\}?(?!\w)""")
			out = out.flatMap { t -> values.map { v -> t.replace(use, Regex.escapeReplacement(v)) } }
		}
		return out
	}

	/** `foreach (<array> as $k => $v)`: the keys or values `$name` takes, for a literal or `config()` array (also through `array_keys`). */
	private fun loopValues(element: PsiElement, name: String): List<String>? {
		var e = element.parent
		while (e != null && e !is PsiFile) {
			if (e is ForeachStatement) {
				val array = e.array
				if (e.value?.name == name) {
					val keysOf = (array as? FunctionReference)?.takeIf { it.name == "array_keys" }?.parameters?.firstOrNull()
					return if (keysOf != null) arrayOf(keysOf)?.let { a -> PhpArrays.entries(a).map { it.first } }
					else arrayOf(array)?.children?.mapNotNull { c ->
						(if (c is ArrayHashElement) PhpArrays.value(c) else PhpArrays.unwrap(c)) as? StringLiteralExpression
					}?.map { it.contents }
				}
				if (e.key?.name == name) return arrayOf(array)?.let { a -> PhpArrays.entries(a).map { it.first } }
			}
			e = e.parent
		}
		return null
	}

	private fun arrayOf(e: PsiElement?): ArrayCreationExpression? = when {
		e is ArrayCreationExpression -> e
		e is FunctionReference && e !is MethodReference && e.name == "config" ->
			(e.parameters.firstOrNull() as? StringLiteralExpression)?.let { PhpArrays.configValue(e, it.contents) } as? ArrayCreationExpression
		else -> null
	}

	/** The calls chained onto this one: `->name('x.')` in `->name('x.')->group(…)`. */
	private fun chainAbove(call: MethodReference): Sequence<MethodReference> =
		generateSequence(call) { m -> (m.parent as? MethodReference)?.takeIf { it.classReference == m } }.drop(1)

	private fun isGroupPrefix(call: MethodReference) = chainAbove(call).any { it.name == "group" }

	/** The route-name prefix a `group()` call adds: `->name('x.')` / `->as('x.')` before it, or `'as'` in its array. */
	private fun namePrefix(group: MethodReference): String {
		val chain = generateSequence(group.classReference as? MethodReference) { it.classReference as? MethodReference }.toList().reversed()
		val chained = chain.filter { it.name == "name" || it.name == "as" }
			.joinToString("") { (it.parameters.firstOrNull() as? StringLiteralExpression)?.contents.orEmpty() }
		val array = group.parameters.firstOrNull() as? ArrayCreationExpression
		val inArray = array?.let { PhpArrays.entry(it, "as") }?.let { PhpArrays.value(it) as? StringLiteralExpression }?.contents.orEmpty()
		return chained + inArray
	}

	/** Prefixes of the groups whose arguments hold an element, outermost first. */
	private fun enclosingPrefix(element: PsiElement): String {
		val parts = ArrayList<String>()
		var e = element.parent
		while (e != null && e !is PsiFile) {
			if (e is MethodReference && e.name == "group" && PsiTreeUtil.isAncestor(e.parameterList, element, true)) parts += namePrefix(e)
			e = e.parent
		}
		return parts.reversed().joinToString("")
	}

	/** Route files mounted inside a group (`group(base_path('routes/x.php'))`, a `require` in a closure): their name prefix, by path under `routes/`. */
	private fun filePrefixes(files: List<PhpFile>, dir: PsiDirectory): Map<String, String> {
		val out = HashMap<String, String>()
		for (file in files) {
			for (literal in PsiTreeUtil.findChildrenOfType(file, StringLiteralExpression::class.java)) {
				val path = literal.contents.takeIf { it.endsWith(".php") } ?: continue
				val target = path.substringAfterLast("routes/").removePrefix("/")
				if (dir.virtualFile.findFileByRelativePath(target) == null) continue
				out.putIfAbsent(target, enclosingPrefix(literal))
			}
		}
		return out
	}
}

/** Blade views under `resources/views`, by dotted name. */
object Views {
	fun index(context: PsiElement): Map<String, PsiFile> = cachedOn(context, "resources/views", emptyMap()) { dir ->
		val base = dir.virtualFile
		dir.allFiles().filter { it.name.endsWith(".blade.php") }.associateBy { file ->
			VfsUtilCore.getRelativePath(file.virtualFile, base)!!.removeSuffix(".blade.php").replace('/', '.')
		}
	}
}

/**
 * Slots filled by `<template #name>` in the Vue files under `resources/js`, and which `.vue` files each imports,
 * so a page's slots include those its components (LocalizedEditForm, AuditEntryForm…) fill on its behalf.
 * Only a template directly inside the file's root component counts: deeper ones fill some inner widget's slot.
 */
object VueSlots {
	private val NAME = Regex("""^(?:#|v-slot:)([A-Za-z_][\w.-]*)$""")
	private val IMPORT = Regex("""import\s+\w+\s+from\s+["']([^"']+\.vue)["']""")

	class Slot(val name: String, val file: PsiFile, val element: PsiElement)

	class Index(val slots: Map<VirtualFile, List<Slot>>, val imports: Map<VirtualFile, List<VirtualFile>>) {
		val all: List<Slot> get() = slots.values.flatten()

		/** The files themselves and every `.vue` file they import, transitively. */
		fun reachable(from: Collection<VirtualFile>): Set<VirtualFile> {
			val seen = LinkedHashSet<VirtualFile>()
			val queue = ArrayDeque(from)
			while (queue.isNotEmpty()) {
				val f = queue.removeFirst()
				if (seen.add(f)) imports[f]?.let(queue::addAll)
			}
			return seen
		}
	}

	fun index(context: PsiElement): Index = cachedOn(context, "resources/js", Index(emptyMap(), emptyMap())) { dir ->
		val slots = LinkedHashMap<VirtualFile, List<Slot>>()
		val imports = HashMap<VirtualFile, List<VirtualFile>>()
		for (file in dir.allFiles().filter { it.name.endsWith(".vue") }) {
			val vf = file.virtualFile ?: continue
			slots[vf] = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).mapNotNull { slotOf(file, it) }
			imports[vf] = IMPORT.findAll(file.text).mapNotNull { vf.parent?.findFileByRelativePath(it.groupValues[1]) }.toList()
		}
		Index(slots, imports)
	}

	/** A slot-filling `<template>` inside the root `<template>`, at any depth, but not inside another slot's content. */
	private fun slotOf(file: PsiFile, tag: XmlTag): Slot? {
		val attribute = slotAttribute(tag) ?: return null
		var up = tag.parentTag ?: return null
		while (up.parentTag != null) {
			if (slotAttribute(up) != null) return null
			up = up.parentTag!!
		}
		if (up.name != "template") return null
		return Slot(name(attribute.name)!!, file, attribute.nameElement ?: attribute)
	}

	private fun slotAttribute(tag: XmlTag) =
		if (tag.name == "template") tag.attributes.firstOrNull { name(it.name) != null } else null

	private fun name(attribute: String): String? = NAME.find(attribute)?.groupValues?.get(1)

	fun slotAt(element: PsiElement): String? = name(element.text)
}

fun VirtualFile.relativeTo(context: PsiElement): String =
	LaravelProject.root(context)?.let { VfsUtilCore.getRelativePath(this, it) } ?: path
