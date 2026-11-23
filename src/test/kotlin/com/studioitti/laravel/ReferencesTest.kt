package com.studioitti.laravel

import com.studioitti.laravel.contrib.References
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReferencesTest : BasePlatformTestCase() {

	override fun setUp() {
		super.setUp()
		myFixture.addFileToProject("artisan", "")
		myFixture.addFileToProject("resources/lang/en/admin.php", """<?php
			return [
				'common' => ['created_by' => 'Created by', 'only_en' => 'Only here'],
			];
		""".trimIndent())
		myFixture.addFileToProject("resources/lang/bg/admin.php", """<?php
			return [
				'common' => ['created_by' => 'Създаден от'],
			];
		""".trimIndent())
		myFixture.addFileToProject("config/userstamps.php", """<?php
			use App\Domains\Users\User;
			return ['model' => User::class];
		""".trimIndent())
		myFixture.addFileToProject("database/migrations/0001_create_users_table.php", """<?php
			use Illuminate\Database\Schema\Blueprint;
			use Illuminate\Support\Facades\Schema;
			return new class {
				public function up(): void {
					Schema::create('users', function (Blueprint ${'$'}table) {
						${'$'}table->id();
						${'$'}table->string('name');
						${'$'}table->json('attrs')->nullable();
						${'$'}table->userstamps();
						${'$'}table->timestamps();
					});
				}
			};
		""".trimIndent())
		myFixture.addFileToProject("app/Traits/HasUserstamps.php", """<?php
			namespace App\Traits;
			trait HasUserstamps {
				public function creator(): BelongsTo {
					return ${'$'}this->belongsTo(${'$'}this->userstampsModel(), 'created_by');
				}
				protected function userstampsModel(): string {
					${'$'}class = config('userstamps.model');
					return ${'$'}class;
				}
			}
		""".trimIndent())
		myFixture.addFileToProject("app/Domains/Users/User.php", """<?php
			namespace App\Domains\Users;
			use App\Contracts\ModelSchema\IHasModelSchema;
			use App\Traits\HasUserstamps;
			class User implements IHasModelSchema {
				use HasUserstamps;
				public function roles(): BelongsToMany {
					return ${'$'}this->belongsToMany(\App\Domains\Roles\Role::class);
				}
			}
		""".trimIndent())
		myFixture.addFileToProject("app/Domains/Users/ModelSchema.yaml", """
			fields:
			  attrs:
			    fields:
			      color:
			        label: Colour
		""".trimIndent())
	}

	private fun targets(text: String, fileName: String, path: String = "app/Domains/Users/$fileName"): List<List<PsiElement>> {
		val file = myFixture.addFileToProject(path, text)
		myFixture.configureFromExistingVirtualFile(file.virtualFile)
		// what Ctrl+click asks: goes through the registered contributors and every view of the file
		val host = myFixture.file.findReferenceAt(myFixture.caretOffset)!!.element
		return References.of(host).map { ref -> (ref as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element } }
	}

	fun testLangKeyInYamlResolvesEverySegmentInEveryLocale() {
		val segments = targets("list:\n  titleKey: admin.common.<caret>created_by\n", "ListSchema.yaml")
		assertEquals(3, segments.size)
		assertEquals(2, segments[0].size) // admin.php, en + bg
		assertEquals(2, segments[1].size)
		assertEquals(2, segments[2].size)
		assertEquals("'created_by'", segments[2][0].text)
	}

	fun testChooserRowsNameTheLocale() {
		val segments = targets("list:\n  titleKey: admin.common.<caret>created_by\n", "ListSchema.yaml")
		// the row Ctrl+click's chooser draws for each target (PsiElementNavigationTarget)
		val rows = (segments[0] + segments[2]).map {
			val p = com.intellij.codeInsight.navigation.targetPresentation(it)
			"${p.presentableText} | ${p.containerText}"
		}
		assertEquals(listOf(
			"en: admin.php | resources/lang/en/admin.php",
			"bg: admin.php | resources/lang/bg/admin.php",
			"en: Created by | resources/lang/en/admin.php",
			"bg: Създаден от | resources/lang/bg/admin.php",
		), rows)
		// and picking one lands on the key itself
		assertEquals("'created_by'", segments[2][1].navigationElement.text)
		assertTrue((segments[2][1].navigationElement.containingFile.virtualFile.path).endsWith("lang/bg/admin.php"))
	}


	fun testRelPathFollowsTraitRelationThroughConfigToField() {
		val segments = targets("list:\n  columns:\n    - rel:creator.<caret>name\n", "ListSchema.yaml")
		assertEquals(2, segments.size)
		assertEquals("creator", (segments[0].single() as com.jetbrains.php.lang.psi.elements.Method).name)
		assertEquals("'name'", segments[1].single().text)
	}

	fun testJsonPathIntoDeclaredNestedField() {
		val segments = targets("list:\n  columns:\n    - attrs.<caret>color\n", "ListSchema.yaml")
		assertTrue(segments[0].isNotEmpty())
		assertEquals("color", (segments[1].single() as org.jetbrains.yaml.psi.YAMLKeyValue).keyText)
	}

	/** The SPA reads lang/<locale>.json only: every segment is the whole JSON key, never the PHP group file. */
	fun testVueTranslateCallResolvesInTheJsonFiles() {
		myFixture.addFileToProject("lang/en.json", "{\n    \"admin.chrome.cancel\": \"Cancel\"\n}")
		myFixture.addFileToProject("lang/bg.json", "{\n    \"admin.chrome.cancel\": \"Отказ\"\n}")
		val segments = targets(
			"<template><div>{{ \$t('admin.chrome.<caret>cancel') }}</div></template>",
			"Edit.vue", "resources/js/Pages/Admin/Users/Edit.vue",
		)
		assertEquals(3, segments.size)
		for (segment in segments) {
			val rows = segment.map { com.intellij.codeInsight.navigation.targetPresentation(it).let { p -> "${p.presentableText} | ${p.containerText}" } }
			assertEquals(listOf("en: Cancel | lang/en.json", "bg: Отказ | lang/bg.json"), rows)
		}
	}

	fun testPhpLabelKey() {
		val segments = targets(
			"<?php return ['hero' => ['labelKey' => 'admin.common.<caret>created_by']];",
			"cms-blocks.php", "config/cms-blocks.php",
		)
		assertEquals(2, segments[2].size)
	}

	fun testCompletionOffersRelationsAndFields() {
		val file = myFixture.addFileToProject("app/Domains/Users/ListSchema.yaml", "list:\n  columns:\n    - rel:creator.<caret>\n")
		myFixture.configureFromExistingVirtualFile(file.virtualFile)
		myFixture.completeBasic()
		val names = myFixture.lookupElementStrings!!
		assertTrue(names.toString(), names.containsAll(listOf("name", "roles", "created_at", "creator")))
	}

	fun testWarnings() {
		val file = myFixture.addFileToProject("app/Domains/Users/ListSchema.yaml", """
			list:
			  titleKey: admin.common.only_en
			  columns:
			    - field: rel:creator.nope
			      labelKey: admin.common.missing
		""".trimIndent())
		myFixture.configureFromExistingVirtualFile(file.virtualFile)
		val messages = myFixture.doHighlighting()
			.filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }
			.map(HighlightInfo::getDescription)
		assertTrue(messages.toString(), messages.contains("Translation 'admin.common.only_en' missing in: bg"))
		assertTrue(messages.toString(), messages.contains("Unknown field or relation 'nope' on User"))
		assertTrue(messages.toString(), messages.contains("Unknown translation key 'admin.common.missing'"))
	}
}
