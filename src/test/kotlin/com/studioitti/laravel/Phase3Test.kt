package com.studioitti.laravel

import com.studioitti.laravel.contrib.DeclarationRenameHandler
import com.studioitti.laravel.contrib.References
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

class Phase3Test : BasePlatformTestCase() {
	private val d = "$"

	override fun setUp() {
		super.setUp()
		fun add(path: String, text: String) = myFixture.addFileToProject(path, text.trimIndent())
		add("artisan", "")
		add("resources/lang/en/admin.php", """
			<?php return [
				'common' => [
					'created_by' => 'Created by',
				],
			];
		""")
		add("resources/lang/bg/admin.php", """
			<?php return [
				'common' => [
					'created_by' => 'Създаден от',
				],
			];
		""")
		add("app/Http/Controllers/BaseInertiaCRUDController.php", "<?php namespace App\\Http\\Controllers; abstract class BaseInertiaCRUDController {}")
		add("app/Domains/Users/User.php", """
			<?php
			namespace App\Domains\Users;
			class User implements \App\Contracts\ModelSchema\IHasModelSchema {
				public function creator() { return ${d}this->belongsTo(User::class); }
				public function getFullNameAttribute() { return ''; }
			}
		""")
		add("app/Domains/Users/UsersCtrl.php", """
			<?php
			namespace App\Domains\Users;
			class UsersCtrl extends \App\Http\Controllers\BaseInertiaCRUDController {
				public function __construct(User ${d}model) {}
				public function edit() { return 'Admin/Users/Edit'; }
			}
		""")
		add("database/migrations/0001_create_users_table.php", """
			<?php
			return new class {
				public function up(): void {
					Schema::create('users', function (Blueprint ${d}table) {
						${d}table->string('name');
						${d}table->text('notes');
					});
				}
			};
		""")
		add("app/Domains/Users/ModelSchema.yaml", """
			fields:
			  lines:
			    array-item:
			      fields:
			        qty:
			          label: Qty
			  notes:
			    input: textarea
		""")
		add("app/Domains/Users/ListSchema.yaml", """
			list:
			  titleKey: admin.common.created_by
			  columns:
			    - rel:creator.name
			    - full_name
			    - notes
		""")
		add("app/Domains/Users/EditSchema.yaml", """
			edit:
			  formLayout:
			    - repeater: lines
			    - repeater: notes
			    - slot: two_factor
			    - list: users
		""")
		add("routes/admin.php", """
			<?php
			use App\Domains\Users\UsersCtrl;
			itti_resource_route('users', UsersCtrl::class);
		""")
		add("lang/en.json", """
			{
			    "admin.chrome.cancel": "Cancel",
			    "admin.chrome.only_en": "Only here"
			}
		""")
		add("lang/bg.json", """
			{
			    "admin.chrome.cancel": "Отказ"
			}
		""")
		add("resources/js/Pages/Admin/Users/Edit.vue", """
			<script setup>
			const title = t('admin.chrome.cancel')
			</script>
			<template>
			  <super-edit-form>
			    <template #two_factor>x</template>
			  </super-edit-form>
			</template>
		""")
	}

	private fun file(path: String): PsiFile = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!

	private fun method(name: String): Method =
		PsiTreeUtil.findChildrenOfType(file("app/Domains/Users/User.php"), Method::class.java).single { it.name == name }

	private fun literal(path: String, contents: String): StringLiteralExpression =
		PsiTreeUtil.findChildrenOfType(file(path), StringLiteralExpression::class.java).first { it.contents == contents }

	private fun usagesIn(target: PsiElement): List<String> =
		ReferencesSearch.search(target).findAll().map { it.element.containingFile.name }.sorted()

	private fun text(path: String) = file(path).text

	fun testRelationMethodUsagesAndRename() {
		assertTrue(usagesIn(method("creator")).contains("ListSchema.yaml"))
		myFixture.renameElement(method("creator"), "author")
		assertTrue(text("app/Domains/Users/ListSchema.yaml"), text("app/Domains/Users/ListSchema.yaml").contains("- rel:author.name"))
	}

	fun testAccessorRenameKeepsSnakeCase() {
		assertTrue(usagesIn(method("getFullNameAttribute")).contains("ListSchema.yaml"))
		myFixture.renameElement(method("getFullNameAttribute"), "getDisplayNameAttribute")
		assertTrue(text("app/Domains/Users/ListSchema.yaml"), text("app/Domains/Users/ListSchema.yaml").contains("- display_name"))
	}

	fun testLangKeyUsagesFromItsDeclaration() {
		val key = literal("resources/lang/en/admin.php", "created_by")
		// a Vue t() call is a frontend key, in lang/*.json, not a use of the PHP file
		assertEquals(listOf("ListSchema.yaml"), usagesIn(key))
		assertTrue(com.studioitti.laravel.contrib.UsagesHandlerFactory().canFindUsages(key))
	}

	fun testLangKeyRenameInEveryLocaleAndUsage() {
		val en = file("resources/lang/en/admin.php")
		val declarations = DeclarationRenameHandler.declarationsAt(en, en.text.indexOf("created_by") + 2)
		assertEquals(2, declarations.size)
		DeclarationRenameHandler.rename(project, declarations, "made_by")
		assertTrue(text("resources/lang/en/admin.php").contains("'made_by' => 'Created by'"))
		assertTrue(text("resources/lang/bg/admin.php").contains("'made_by' => 'Създаден от'"))
		assertTrue(text("app/Domains/Users/ListSchema.yaml").contains("titleKey: admin.common.made_by"))
	}

	private fun jsonKey(path: String, key: String): com.intellij.json.psi.JsonStringLiteral =
		PsiTreeUtil.findChildrenOfType(file(path), com.intellij.json.psi.JsonStringLiteral::class.java).first { it.value == key }

	fun testFrontendKeyCompletesFromTheJsonKeys() {
		val vue = myFixture.addFileToProject("resources/js/X.vue", "<script setup>t('admin.chrome.x')</script>")
		myFixture.configureFromExistingVirtualFile(vue.virtualFile)
		val host = myFixture.file.findReferenceAt(myFixture.file.text.indexOf("chrome") + 1)!!.element
		val refs = References.of(host).filterIsInstance<com.studioitti.laravel.lang.JsonKeyReference>()
		val names = { i: Int -> refs[i].variants.map { (it as com.intellij.codeInsight.lookup.LookupElement).lookupString } }
		assertEquals(listOf("admin"), names(0))
		assertEquals(listOf("chrome"), names(1))
		assertEquals(listOf("cancel", "only_en"), names(2))
	}

	fun testFrontendKeyWarningsAndFix() {
		val vue = myFixture.addFileToProject("resources/js/X.vue", "<script setup>t('admin.chrome.brand_<caret>new'); t('admin.chrome.only_en')</script>")
		myFixture.configureFromExistingVirtualFile(vue.virtualFile)
		val w = myFixture.doHighlighting().mapNotNull(HighlightInfo::getDescription)
		assertTrue(w.toString(), "Unknown translation key 'admin.chrome.brand_new'" in w && "Translation 'admin.chrome.only_en' missing in: bg" in w)
		myFixture.launchAction(myFixture.findSingleIntention("Add 'admin.chrome.brand_new' to en, bg"))
		assertEquals("{\n    \"admin.chrome.cancel\": \"Отказ\",\n    \"admin.chrome.brand_new\": \"Brand new\"\n}", text("lang/bg.json"))
		assertTrue(text("lang/en.json").endsWith("\"admin.chrome.only_en\": \"Only here\",\n    \"admin.chrome.brand_new\": \"Brand new\"\n}"))
	}

	fun testFrontendKeyUsagesAndRename() {
		val key = jsonKey("lang/en.json", "admin.chrome.cancel")
		assertEquals(listOf("Edit.vue"), usagesIn(key))
		assertTrue(com.studioitti.laravel.contrib.UsagesHandlerFactory().canFindUsages(key))
		val vue = file("resources/js/Pages/Admin/Users/Edit.vue")
		val declarations = DeclarationRenameHandler.declarationsAt(vue, vue.text.indexOf("cancel") + 2)
		assertEquals(2, declarations.size)
		assertTrue(DeclarationRenameHandler.declarationsAt(vue, vue.text.indexOf("chrome") + 2).isEmpty())
		DeclarationRenameHandler.rename(project, declarations, "abort")
		assertTrue(text("lang/en.json").contains("\"admin.chrome.abort\": \"Cancel\""))
		assertTrue(text("lang/bg.json").contains("\"admin.chrome.abort\": \"Отказ\""))
		assertTrue(text("resources/js/Pages/Admin/Users/Edit.vue").contains("t('admin.chrome.abort')"))
	}

	fun testLangKeyRenameFromAUsage() {
		val yaml = file("app/Domains/Users/ListSchema.yaml")
		val declarations = DeclarationRenameHandler.declarationsAt(yaml, yaml.text.indexOf("created_by") + 2)
		assertEquals(2, declarations.size)
		assertTrue(DeclarationRenameHandler.declarationsAt(yaml, yaml.text.indexOf("common") + 2).isEmpty())
	}

	fun testMigrationColumnUsages() {
		assertTrue(usagesIn(literal("database/migrations/0001_create_users_table.php", "notes")).contains("ListSchema.yaml"))
	}

	fun testSlotUsagesFromTheVueTemplate() {
		val attribute = PsiTreeUtil.findChildrenOfType(file("resources/js/Pages/Admin/Users/Edit.vue"), XmlAttribute::class.java)
			.first { it.name == "#two_factor" }
		assertEquals(listOf("EditSchema.yaml"), usagesIn(attribute))
	}

	fun testCreateMissingLangKeyFix() {
		myFixture.configureByText("x.yaml", "list:\n  titleKey: admin.common.brand_<caret>new\n")
		myFixture.doHighlighting()
		myFixture.launchAction(myFixture.findSingleIntention("Add 'admin.common.brand_new' to en, bg"))
		assertTrue(text("resources/lang/en/admin.php"), text("resources/lang/en/admin.php").contains("\t\t'created_by' => 'Created by',\n\t\t'brand_new' => 'Brand new',\n\t],"))
		assertTrue(text("resources/lang/bg/admin.php").contains("'brand_new' => 'Brand new',"))
	}

	fun testCreateNestedLangKeyOnlyWhereMissing() {
		myFixture.saveText(myFixture.findFileInTempDir("resources/lang/bg/admin.php"), "<?php return [\n];")
		myFixture.configureByText("x.yaml", "list:\n  titleKey: admin.common.created_<caret>by\n")
		myFixture.doHighlighting()
		myFixture.launchAction(myFixture.findSingleIntention("Add 'admin.common.created_by' to bg"))
		assertEquals("<?php return [\n\t'common' => ['created_by' => 'Created by'],\n];", text("resources/lang/bg/admin.php"))
	}

	fun testListResolvesToRouteControllerAndSchemas() {
		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/Domains/Users/EditSchema.yaml"))
		val host = myFixture.file.findReferenceAt(myFixture.file.text.indexOf("list: users") + 8)!!.element
		val targets = References.of(host).filterIsInstance<PsiPolyVariantReference>().flatMap { it.multiResolve(false).mapNotNull { r -> r.element } }
		assertEquals(listOf("admin.php", "UsersCtrl.php", "ModelSchema.yaml", "ListSchema.yaml", "EditSchema.yaml"), targets.map { it.containingFile.name })
	}

	fun testRepeaterNeedsArrayItem() {
		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/Domains/Users/EditSchema.yaml"))
		val w = myFixture.doHighlighting().mapNotNull(HighlightInfo::getDescription)
		assertTrue(w.toString(), "'notes' has no array-item in ModelSchema.yaml, so it cannot be a repeater" in w)
		assertFalse(w.toString(), w.any { "'lines'" in it })
	}
}
