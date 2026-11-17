package com.studioitti.laravel.model

import com.studioitti.laravel.support.PhpArrays
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.AssignmentExpression
import com.jetbrains.php.lang.psi.elements.ClassConstantReference
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.FunctionReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpReturn
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression
import com.jetbrains.php.lang.psi.elements.Variable
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/** Eloquent models as the schema system sees them: relations, and the fields a path may name. */
object Models {
	private val RELATION_CALLS = setOf(
		"hasOne", "hasMany", "belongsTo", "belongsToMany", "morphOne", "morphMany", "morphTo",
		"morphToMany", "morphedByMany", "hasOneThrough", "hasManyThrough",
	)
	private val UNCOUNTABLE = setOf("news", "data", "media", "metadata", "equipment", "information", "feedback", "series", "species")

	/** The model a schema YAML belongs to: `HasModelSchema` reads it from the model's own directory. */
	fun forSchemaFile(file: PsiFile): PhpClass? {
		val dir = file.originalFile.containingDirectory ?: return null
		return CachedValuesManager.getCachedValue(dir) {
			CachedValueProvider.Result.create(findModel(dir), PsiModificationTracker.getInstance(dir.project))
		}
	}

	private fun findModel(dir: PsiDirectory): PhpClass? =
		dir.files.filterIsInstance<PhpFile>()
			.flatMap { PsiTreeUtil.findChildrenOfType(it, PhpClass::class.java) }
			.firstOrNull { cls ->
				!cls.isInterface && !cls.isTrait &&
					(cls.interfaceNames.any { it.endsWith("\\IHasModelSchema") } || cls.traitNames.any { it.endsWith("\\HasModelSchema") })
			}

	/** The class, its traits and its parents — app code only, the framework adds nothing a schema names. */
	fun hierarchy(cls: PhpClass): List<PhpClass> {
		val out = LinkedHashSet<PhpClass>()
		fun visit(c: PhpClass?) {
			if (c == null || c.fqn.startsWith("\\Illuminate\\") || !out.add(c)) return
			c.traits.forEach { visit(it) }
			visit(c.superClass)
		}
		visit(cls)
		return out.toList()
	}

	fun methods(cls: PhpClass): List<Method> =
		hierarchy(cls).flatMap { it.ownMethods.toList() }.distinctBy { it.name.lowercase() }

	private fun relationCall(m: Method): MethodReference? =
		PsiTreeUtil.findChildrenOfType(m, MethodReference::class.java).firstOrNull {
			it.name in RELATION_CALLS && (it.classReference as? Variable)?.name == "this"
		}

	fun relations(cls: PhpClass): List<Method> =
		methods(cls).filter { m -> !m.isStatic && m.parameters.all { it.isOptional } && relationCall(m) != null }

	fun relation(cls: PhpClass, name: String): Method? = relations(cls).firstOrNull { it.name == name }

	/** The model a relation method points at, following `X::class`, `$this->method()`, variables and `config()`. */
	fun related(relation: Method, model: PhpClass): PhpClass? {
		val arg = relationCall(relation)?.parameters?.firstOrNull() ?: return null
		val fqn = classOf(arg, model, 0) ?: return null
		return PhpIndex.getInstance(model.project).getClassesByFQN(fqn).firstOrNull()
	}

	private fun classOf(e: PsiElement?, model: PhpClass, depth: Int): String? {
		if (e == null || depth > 6) return null
		return when (e) {
			is ClassConstantReference -> if (e.name == "class") (e.classReference as? ClassReference)?.fqn else null
			is StringLiteralExpression -> e.contents.takeIf { it.isNotBlank() }?.let { if (it.startsWith("\\")) it else "\\" + it }
			is MethodReference -> {
				if ((e.classReference as? Variable)?.name != "this") return null
				val m = methods(model).firstOrNull { it.name == e.name } ?: return null
				returns(m).firstNotNullOfOrNull { classOf(it, model, depth + 1) }
			}
			is FunctionReference -> {
				if (e.name != "config") return null
				val key = (e.parameters.firstOrNull() as? StringLiteralExpression)?.contents ?: return null
				classOf(PhpArrays.configValue(e, key), model, depth + 1)
			}
			is Variable -> {
				val fn = PsiTreeUtil.getParentOfType(e, Function::class.java) ?: return null
				PsiTreeUtil.findChildrenOfType(fn, AssignmentExpression::class.java)
					.filter { (it.variable as? Variable)?.name == e.name }
					.firstNotNullOfOrNull { classOf(it.value, model, depth + 1) }
			}
			else -> null
		}
	}

	private fun returns(fn: Function): List<PsiElement> =
		PsiTreeUtil.findChildrenOfType(fn, PhpReturn::class.java)
			.filter { PsiTreeUtil.getParentOfType(it, Function::class.java) == fn }
			.mapNotNull { it.argument }

	fun table(cls: PhpClass): String? {
		(cls.findFieldByName("table", false)?.defaultValue as? StringLiteralExpression)?.let { return it.contents }
		val snake = snake(cls.name)
		val plural = snake.substringBeforeLast('_', "").let { if (it.isEmpty()) "" else "${it}_" } + plural(snake.substringAfterLast('_'))
		return listOf(plural, snake).firstOrNull { Migrations.hasTable(cls, it) } ?: plural
	}

	fun schemaFile(cls: PhpClass, name: String = "ModelSchema.yaml"): YAMLFile? =
		cls.containingFile?.containingDirectory?.findFile(name) as? YAMLFile

	/** `fields:` of the model's ModelSchema.yaml. */
	fun schemaFields(cls: PhpClass): YAMLMapping? =
		(schemaFile(cls)?.documents?.firstOrNull()?.topLevelValue as? YAMLMapping)
			?.getKeyValueByKey("fields")?.value as? YAMLMapping

	/** Everything a field name may resolve to: ModelSchema fields, migration columns, accessors. */
	fun fields(cls: PhpClass): Map<String, List<FieldSource>> {
		val out = LinkedHashMap<String, MutableList<FieldSource>>()
		fun add(name: String, element: PsiElement, kind: String) {
			out.getOrPut(name) { ArrayList() } += FieldSource(element, kind)
		}
		schemaFields(cls)?.keyValues?.forEach { add(it.keyText, it, "schema") }
		table(cls)?.let { table ->
			Migrations.columns(cls, table).forEach { (name, elements) -> elements.forEach { add(name, it, "column") } }
		}
		for (m in methods(cls)) {
			val legacy = Regex("^get(\\w+)Attribute$").find(m.name)?.groupValues?.get(1)
			when {
				legacy != null && legacy.isNotEmpty() -> add(snake(legacy), m, "accessor")
				ATTRIBUTE_SIGNATURE.containsMatchIn(m.text.substringBefore('{')) -> add(snake(m.name), m, "accessor")
			}
		}
		return out
	}

	/** Nested `fields:` under a ModelSchema field (a json column), for json paths. */
	fun nestedFields(field: PsiElement): YAMLMapping? =
		((field as? YAMLKeyValue)?.value as? YAMLMapping)?.getKeyValueByKey("fields")?.value as? YAMLMapping

	private val ATTRIBUTE_SIGNATURE = Regex("\\)\\s*:\\s*\\??\\\\?(?:[\\w\\\\]+\\\\)?Attribute\\s*$")

	fun snake(name: String): String = name.replace(Regex("(?<=[a-z0-9])([A-Z])"), "_$1").lowercase()

	private fun plural(word: String): String = when {
		word in UNCOUNTABLE -> word
		word == "person" -> "people"
		word == "child" -> "children"
		word.endsWith("y") && word.length > 1 && word[word.length - 2] !in "aeiou" -> word.dropLast(1) + "ies"
		word.endsWith("s") || word.endsWith("x") || word.endsWith("z") || word.endsWith("ch") || word.endsWith("sh") -> word + "es"
		else -> word + "s"
	}
}

class FieldSource(val element: PsiElement, val kind: String)
