package com.studioitti.laravel.model

import com.studioitti.laravel.support.LaravelProject
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable

/** Table columns as `database/migrations` declares them: table => column => declaring elements. */
object Migrations {
	private val NAMED_COLUMN = setOf(
		"bigIncrements", "bigInteger", "binary", "boolean", "char", "dateTimeTz", "dateTime", "date",
		"decimal", "double", "enum", "float", "foreignId", "foreignUlid", "foreignUuid", "geography",
		"geometry", "increments", "integer", "ipAddress", "json", "jsonb", "longText", "macAddress",
		"mediumIncrements", "mediumInteger", "mediumText", "set", "smallIncrements", "smallInteger",
		"string", "text", "timeTz", "time", "timestampTz", "timestamp", "tinyIncrements", "tinyInteger",
		"tinyText", "unsignedBigInteger", "unsignedInteger", "unsignedMediumInteger",
		"unsignedSmallInteger", "unsignedTinyInteger", "ulid", "uuid", "year", "vector",
	)
	private val MORPHS = setOf("morphs", "nullableMorphs", "uuidMorphs", "nullableUuidMorphs", "ulidMorphs", "nullableUlidMorphs")

	fun columns(context: PsiElement, table: String): Map<String, List<PsiElement>> =
		index(context)[table] ?: emptyMap()

	fun hasTable(context: PsiElement, table: String): Boolean = index(context).containsKey(table)

	private fun index(context: PsiElement): Map<String, Map<String, List<PsiElement>>> {
		val vf = LaravelProject.root(context)?.findFileByRelativePath("database/migrations") ?: return emptyMap()
		val dir = PsiManager.getInstance(context.project).findDirectory(vf) ?: return emptyMap()
		return CachedValuesManager.getCachedValue(dir) {
			CachedValueProvider.Result.create(build(dir), PsiModificationTracker.getInstance(dir.project))
		}
	}

	private fun build(dir: PsiDirectory): Map<String, Map<String, List<PsiElement>>> {
		val tables = LinkedHashMap<String, LinkedHashMap<String, MutableList<PsiElement>>>()
		// subfolders too (`migrations/tenant/`); by file name, so the dated order holds across them
		fun all(d: PsiDirectory): List<PhpFile> = d.files.filterIsInstance<PhpFile>() + d.subdirectories.flatMap { all(it) }
		val files = all(dir).sortedBy { it.name }
		for (file in files) {
			for (call in PsiTreeUtil.findChildrenOfType(file, MethodReference::class.java)) {
				if (call.name != "create" && call.name != "table") continue
				if ((call.classReference as? ClassReference)?.name != "Schema") continue
				val params = call.parameters
				val table = (params.getOrNull(0) as? StringLiteralExpression)?.contents ?: continue
				val closure = params.getOrNull(1)?.let { it as? Function ?: PsiTreeUtil.findChildOfType(it, Function::class.java) } ?: continue
				val blueprint = closure.parameters.firstOrNull()?.name ?: continue
				val columns = tables.getOrPut(table) { LinkedHashMap() }
				for (col in PsiTreeUtil.findChildrenOfType(closure, MethodReference::class.java)) {
					if ((col.classReference as? Variable)?.name != blueprint) continue
					for ((name, element) in columnsOf(col)) {
						columns.getOrPut(name) { ArrayList() } += element
					}
				}
			}
		}
		return tables
	}

	private fun columnsOf(call: MethodReference): List<Pair<String, PsiElement>> {
		val name = call.name ?: return emptyList()
		val first = call.parameters.getOrNull(0) as? StringLiteralExpression
		return when {
			name in NAMED_COLUMN -> listOfNotNull(first?.let { it.contents to it })
			name == "id" -> listOf((first?.contents ?: "id") to (first ?: call))
			name == "renameColumn" -> listOfNotNull((call.parameters.getOrNull(1) as? StringLiteralExpression)?.let { it.contents to it })
			name in setOf("timestamps", "timestampsTz", "nullableTimestamps") -> listOf("created_at" to call, "updated_at" to call)
			name in setOf("softDeletes", "softDeletesTz") -> listOf((first?.contents ?: "deleted_at") to (first ?: call))
			name == "rememberToken" -> listOf("remember_token" to call)
			name in MORPHS && first != null -> listOf("${first.contents}_id" to first, "${first.contents}_type" to first)
			// this project's Blueprint macro (AppServiceProvider)
			name == "userstamps" -> listOf("created_by" to call, "updated_by" to call)
			else -> emptyList()
		}
	}
}
