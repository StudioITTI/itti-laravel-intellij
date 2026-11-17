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
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression
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

	class Index(val routes: Map<String, PsiElement>, val resources: Map<String, StringLiteralExpression>)

	fun index(context: PsiElement): Index = cachedOn(context, "routes", Index(emptyMap(), emptyMap())) { dir ->
		val routes = LinkedHashMap<String, PsiElement>()
		val resources = LinkedHashMap<String, StringLiteralExpression>()
		for (file in dir.allFiles().filterIsInstance<PhpFile>()) {
			for (call in PsiTreeUtil.findChildrenOfType(file, FunctionReference::class.java)) {
				val first = call.parameters.firstOrNull() as? StringLiteralExpression ?: continue
				when {
					call is MethodReference && call.name == "name" -> routes.putIfAbsent(first.contents, first)
					call !is MethodReference && call.name == "itti_resource_route" -> {
						val name = first.contents
						resources.putIfAbsent(name, first)
						val only = (call.parameters.getOrNull(2) as? ArrayCreationExpression)
							?.let { arr -> PsiTreeUtil.findChildrenOfType(arr, StringLiteralExpression::class.java).map { it.contents } }
						(only ?: RESOURCE_ACTIONS).forEach { routes.putIfAbsent("$name.$it", first) }
						routes.putIfAbsent("$name.exportList", first)
					}
				}
			}
		}
		Index(routes, resources)
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

/** `<template #name>` / `v-slot:name` declarations in the admin's Vue pages. */
object VueSlots {
	private val SLOT = Regex("<template\\s+(?:#|v-slot:)([A-Za-z_][\\w-]*)")

	class Slot(val name: String, val file: PsiFile, val offset: Int) {
		val element: PsiElement get() = file.findElementAt(offset) ?: file
	}

	fun all(context: PsiElement): List<Slot> = cachedOn(context, "resources/js/Pages/Admin", emptyList()) { dir ->
		dir.allFiles().filter { it.name.endsWith(".vue") }.flatMap { file ->
			SLOT.findAll(file.text).map { Slot(it.groupValues[1], file, it.groups[1]!!.range.first) }.toList()
		}
	}

	fun slotAt(element: PsiElement): String? {
		val text = element.text
		return when {
			text.startsWith("#") -> text.substring(1)
			text.startsWith("v-slot:") -> text.substring(7)
			else -> null
		}?.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }?.takeIf { it.isNotEmpty() }
	}
}

fun VirtualFile.relativeTo(context: PsiElement): String =
	LaravelProject.root(context)?.let { VfsUtilCore.getRelativePath(this, it) } ?: path
