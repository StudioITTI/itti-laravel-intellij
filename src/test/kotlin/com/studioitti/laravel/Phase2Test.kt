package com.studioitti.laravel

import com.studioitti.laravel.contrib.References
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class Phase2Test : BasePlatformTestCase() {

	private val d = "$"

	override fun setUp() {
		super.setUp()
		fun add(path: String, text: String) = myFixture.addFileToProject(path, text.trimIndent())
		add("artisan", "")
		add("resources/lang/en/admin.php", "<?php return ['permissions' => ['admin' => 'Administration']];")
		add("resources/lang/bg/admin.php", "<?php return ['permissions' => []];")
		add("database/permissions.php", "<?php return array ('admin' => 'Administration');")
		add("app/Auth/Restricted.php", "<?php namespace App\\Auth; #[\\Attribute] final class Restricted {}")
		add("app/Http/Controllers/BaseInertiaCRUDController.php", "<?php namespace App\\Http\\Controllers; abstract class BaseInertiaCRUDController {}")
		add("app/Domains/Users/User.php", """
			<?php
			namespace App\Domains\Users;
			class User implements \App\Contracts\ModelSchema\IHasModelSchema {}
		""")
		add("app/Domains/Users/ModelSchema.yaml", """
			fields:
			  lines:
			    array-item:
			      fields:
			        qty:
			          label: Qty
			  body:
			    input: html
			    html-preset: minimal
		""")
		add("app/Domains/Users/UsersCtrl.php", """
			<?php
			namespace App\Domains\Users;
			use App\Auth\Restricted;
			#[Restricted('admin', 'Administration')]
			class UsersCtrl extends \App\Http\Controllers\BaseInertiaCRUDController {
				public function __construct(User ${d}model) {}
				public function edit() { return 'Admin/Users/Edit'; }
				#[Restricted('reset')]
				public function reset() {}
			}
		""")
		add("routes/admin.php", """
			<?php
			use App\Domains\Users\UsersCtrl;
			Route::inertia('/', 'Admin/Home')->name('admin.home');
			itti_resource_route('users', UsersCtrl::class);
			itti_resource_route('users.logins', UsersLoginsCtrl::class, ['index', 'edit']);
		""")
		add("resources/js/Pages/Admin/Users/Edit.vue", """
			<script setup>
			import LocalizedForm from "../../../Components/LocalizedForm.vue";
			</script>
			<template>
			  <super-edit-form>
			    <template #two_factor><v-icon icon="mdi-home"/></template>
			  </super-edit-form>
			</template>
		""")
		add("resources/js/Components/LocalizedForm.vue", """
			<template>
			  <super-edit-form>
			    <template v-for="(_, name) in ${d}slots" #[name]="p"><slot :name="name"/></template>
			    <template #locale_switcher>
			      <menu-x><template #activator>x</template></menu-x>
			    </template>
			    <template #field.type>x</template>
			  </super-edit-form>
			</template>
		""")
		add("resources/views/public/blocks/hero.blade.php", "")
		add("resources/views/public/types/content.blade.php", "")
		add("config/rich-text.php", "<?php return ['presets' => ['minimal' => [], 'standard' => []]];")
		add("node_modules/@mdi/font/scss/_variables.scss", "${d}mdi-icons: (\n    \"home\": F02DC,\n    \"account\": F0004\n);")
		add("config/mail-templates.php", """
			<?php
			return [
				'user_password_reset' => [
					'placeholders' => ['user_name' => ['label' => 'Name', 'sample' => 'Ivan']],
					'defaults' => ['subject' => ['en' => 'Hi {{ user_name }}, {{ nope }}']],
				],
			];
		""")
		add("app/Mail/MailTemplateRenderer.php", """
			<?php
			namespace App\Mail;
			class MailTemplateRenderer {
				public function render(string ${d}code, array ${d}data) {}
				public function isEnabled(string ${d}code): bool {}
			}
		""")
	}

	private fun refs(path: String, text: String): List<List<PsiElement>> {
		val file = myFixture.addFileToProject(path, text.trimIndent())
		myFixture.configureFromExistingVirtualFile(file.virtualFile)
		val host = myFixture.file.findReferenceAt(myFixture.caretOffset)!!.element
		return References.of(host).map { ref -> (ref as PsiPolyVariantReference).multiResolve(false).mapNotNull { it.element } }
	}

	private fun warnings(path: String, text: String): List<String> {
		val file = myFixture.addFileToProject(path, text.trimIndent())
		myFixture.configureFromExistingVirtualFile(file.virtualFile)
		return myFixture.doHighlighting()
			.filter { it.severity >= HighlightSeverity.WEAK_WARNING && it.description != null }
			.map(HighlightInfo::getDescription)
	}

	private val PsiElement.fileName get() = containingFile.name

	fun testSlotResolvesToTheDomainsVueTemplate() {
		val r = refs("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - slot: two_<caret>factor\n")
		assertEquals("Edit.vue", r.single().single().fileName)
	}

	fun testSlotFilledByAnImportedComponent() {
		val r = refs("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - slot: locale_sw<caret>itcher\n")
		assertEquals("LocalizedForm.vue", r.single().single().fileName)
	}

	fun testSlotOfANonAdminPageResolvesToItsOwnPageWithALabel() {
		fun add(path: String, text: String) = myFixture.addFileToProject(path, text.trimIndent())
		add("app/Domains/Central/AdminUsers/AdminUser.php", """
			<?php
			namespace App\Domains\Central\AdminUsers;
			class AdminUser implements \App\Contracts\ModelSchema\IHasModelSchema {}
		""")
		add("app/Domains/Central/AdminUsers/AdminUsersCtrl.php", """
			<?php
			namespace App\Domains\Central\AdminUsers;
			class AdminUsersCtrl extends \App\Http\Controllers\BaseInertiaCRUDController {
				public function __construct(AdminUser ${d}model) {}
				public function edit() { return 'Central/AdminUsers/Edit'; }
			}
		""")
		add("resources/js/Pages/Central/AdminUsers/Edit.vue", "<template>\n  <super-edit-form>\n    <template #two_factor>x</template>\n  </super-edit-form>\n</template>")
		val target = refs("app/Domains/Central/AdminUsers/EditSchema.yaml", "edit:\n  formLayout:\n    - slot: two_<caret>factor\n").single().single()
		val row = com.intellij.codeInsight.navigation.targetPresentation(target)
		assertEquals("#two_factor | resources/js/Pages/Central/AdminUsers/Edit.vue", "${row.presentableText} | ${row.containerText}")
	}

	fun testDottedSlotName() {
		val r = refs("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - slot: field.t<caret>ype\n")
		assertEquals("LocalizedForm.vue", r.single().single().fileName)
	}

	fun testSlotCompletionSkipsInnerWidgetSlots() {
		val file = myFixture.addFileToProject("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - slot: x<caret>y\n")
		myFixture.configureFromExistingVirtualFile(file.virtualFile)
		val host = myFixture.file.findReferenceAt(myFixture.caretOffset)!!.element
		val names = References.of(host).single().variants.map { (it as com.intellij.codeInsight.lookup.LookupElement).lookupString }
		assertFalse(names.toString(), "activator" in names)
		assertTrue(names.toString(), "two_factor" in names && "locale_switcher" in names)
	}

	fun testVueSlotGutterOnAnImportedComponent() {
		myFixture.addFileToProject("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - slot: locale_switcher\n")
		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("resources/js/Components/LocalizedForm.vue"))
		val gutters = myFixture.findAllGutters().map { it.tooltipText }
		assertTrue(gutters.toString(), gutters.contains("Schema uses of slot 'locale_switcher'"))
	}

	fun testRouteNamesCarryGroupPrefixes() {
		myFixture.addFileToProject("routes/web.php", "<?php\nRoute::prefix('be')->name('central.')->group(base_path('routes/central.php'));")
		myFixture.addFileToProject("routes/central.php", """
			<?php
			Route::middleware(['auth'])->group(function () {
				itti_resource_route('admin_users.logins', AdminUsersLoginsCtrl::class, ['index', 'edit']);
				Route::group(['as' => 'tools.'], function () {
					Route::get('x', fn() => 1)->name('run');
				});
			});
		""".trimIndent())
		val list = refs("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - list: central.admin_users.lo<caret>gins\n")
		assertEquals("'admin_users.logins'", list.single().single().text)
		val routes = com.studioitti.laravel.support.Routes.index(myFixture.file).routes.keys
		assertTrue(routes.toString(), "central.tools.run" in routes && "central.admin_users.logins.edit" in routes)
		assertFalse(routes.toString(), "central." in routes || "run" in routes)
	}

	fun testNestedListResolvesToItsResourceRoute() {
		val r = refs("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - list: users.lo<caret>gins\n")
		assertEquals("'users.logins'", r.single().single().text)
	}

	fun testTabTitleResolvesToRepeaterItemField() {
		val r = refs("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - repeater: lines\n      presentation: tabs\n      tabTitle: q<caret>ty\n")
		assertEquals("qty", (r.single().single() as org.jetbrains.yaml.psi.YAMLKeyValue).keyText)
	}

	fun testHtmlPresetInYamlAndPhp() {
		assertEquals("'minimal'", refs("app/Domains/Users/X/ModelSchema.yaml", "fields:\n  b:\n    html-preset: mini<caret>mal\n").single().single().text)
		assertEquals("'minimal'", refs("app/Domains/Pages/Types/T.php", "<?php return ['b' => ['html-preset' => 'mini<caret>mal']];").single().single().text)
	}

	fun testIconsInYamlPhpJsAndVueAttributes() {
		assertEquals(1, refs("app/Domains/Users/ListSchema.yaml", "list:\n  icon: mdi-ho<caret>me\n").single().size)
		assertEquals(1, refs("config/x.php", "<?php return ['icon' => 'mdi-ho<caret>me'];").single().size)
		assertEquals(1, refs("resources/js/x.js", "const icon = 'mdi-ho<caret>me'").single().size)
		assertEquals(1, refs("resources/js/Pages/Admin/X.vue", "<template><v-icon icon=\"mdi-ho<caret>me\"/></template>").single().size)
	}

	fun testIconCompletionAndUnknownIcon() {
		myFixture.configureByText("ListSchema.yaml", "list:\n  icon: mdi-acc<caret>\n")
		myFixture.completeBasic()
		assertEquals("list:\n  icon: mdi-account\n", myFixture.editor.document.text)
		assertTrue(warnings("config/y.php", "<?php return ['icon' => 'mdi-nope'];").contains("Unknown icon 'mdi-nope'"))
	}

	fun testNavigationRoutesAndPatterns() {
		assertEquals("'admin.home'", refs("config/a/navigation.php", "<?php return ['left' => [['route' => 'admin.ho<caret>me']]];").single().single().text)
		assertEquals("'users'", refs("config/b/navigation.php", "<?php return ['left' => [['route' => 'users.ind<caret>ex']]];").single().single().text)
		assertEquals("'users'", refs("config/c/navigation.php", "<?php return ['left' => [['active' => ['use<caret>rs.*']]]];").single().single().text)
		assertTrue(warnings("config/d/navigation.php", "<?php return ['left' => [['route' => 'users.nope']]];").contains("Unknown route 'users.nope'"))
	}

	fun testViewsAndPartials() {
		assertEquals("content.blade.php", refs("app/Domains/Pages/Types/ContentPageType.php", "<?php class ContentPageType { public function view(): string { return 'public.types.con<caret>tent'; } }").single().single().fileName)
		assertEquals("hero.blade.php", refs("config/cms-blocks.php", "<?php return ['hero' => ['partial' => 'he<caret>ro']];").single().single().fileName)
	}

	fun testRegistryLayoutNamesResolveToTheEntrysFields() {
		val r = refs("config/cms-blocks.php", """
			<?php return ['hero' => [
				'fields' => ['heading' => [], 'button_url' => []],
				'formLayout' => ['heading', ['section' => [['cols' => 2, 'contents' => ['button_<caret>url']]], 'title' => 'CTA']],
			]];
		""")
		assertEquals("'button_url'", r.single().single().text)
		val w = warnings("config/cms-popups.php", "<?php return ['x' => ['fields' => ['a' => []], 'formLayout' => ['b', ['section' => [], 'title' => 'Not a field']]]];")
		assertTrue(w.toString(), w.contains("No field 'b' in this entry's fields"))
		assertFalse(w.toString(), w.any { it.contains("Not a field") })
	}

	fun testMailCodesAndPlaceholders() {
		val sender = """
			<?php
			class Sender {
				public function send(\App\Mail\MailTemplateRenderer ${d}renderer) {
					${d}code = 'user_password_reset';
					${d}renderer->render(${d}code, ['user_<caret>name' => 'x', 'nope' => 'y']);
				}
			}
		"""
		assertEquals("'user_name'", refs("app/Sender.php", sender).single().single().text)
		val w = warnings("app/Sender2.php", sender.replace("Sender", "Sender2").replace("<caret>", "") + "\n")
		assertTrue(w.toString(), w.contains("Mail 'user_password_reset' declares no placeholder 'nope'"))
		assertFalse(w.toString(), w.any { it.contains("Unknown mail template") })

		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("config/mail-templates.php"))
		val inTemplate = myFixture.doHighlighting().mapNotNull { it.description }
		assertTrue(inTemplate.toString(), inTemplate.contains("Mail 'user_password_reset' declares no placeholder 'nope'"))
		assertFalse(inTemplate.toString(), inTemplate.any { it.contains("'user_name'") })
	}

	fun testRestrictedPermissions() {
		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/Domains/Users/UsersCtrl.php"))
		val w = myFixture.doHighlighting().mapNotNull { it.description }
		assertTrue(w.toString(), w.contains("No admin.permissions.admin translation in: bg"))
		assertTrue(w.toString(), w.contains("'admin@reset' is not in database/permissions.php — run `php artisan permissions:build --preserve`"))
		myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("'admin'") + 2)
		val targets = References.of(myFixture.file.findReferenceAt(myFixture.caretOffset)!!.element)
			.filterIsInstance<PsiPolyVariantReference>().flatMap { it.multiResolve(false).mapNotNull { r -> r.element } }
		assertEquals(listOf("permissions.php", "admin.php"), targets.map { it.fileName })
	}

	fun testScopedPermissions() {
		fun add(path: String, text: String) = myFixture.addFileToProject(path, text.trimIndent())
		add("config/permissions.php", """
			<?php return [
				'default_scope' => 'tenant',
				'output' => ['tenant' => database_path('permissions/tenant.php'), 'central' => database_path('permissions/central.php')],
			];
		""")
		add("database/permissions/central.php", "<?php return array ('admin' => 'Administration', 'admin@reset' => 'Reset');")
		add("database/permissions/tenant.php", "<?php return array ('staff' => 'Staff');")
		myFixture.saveText(myFixture.findFileInTempDir("resources/lang/en/admin.php"), "<?php return ['permissions' => ['central' => ['admin' => 'Administration', 'admin@reset' => 'Reset', 'admin@staff' => 'Staff']]];")
		myFixture.saveText(myFixture.findFileInTempDir("resources/lang/bg/admin.php"), "<?php return ['permissions' => ['central' => ['admin@reset' => 'Нулиране']]];")
		val file = add("app/Domains/Central/X/XCtrl.php", """
			<?php
			namespace App\Domains\Central\X;
			use App\Auth\Restricted;
			#[Restricted('admin', 'Administration', scope: 'central')]
			class XCtrl {
				#[Restricted('reset')]
				public function reset() {}
				#[Restricted('staff')]
				public function staff() {}
			}
		""")
		myFixture.configureFromExistingVirtualFile(file.virtualFile)
		val w = myFixture.doHighlighting().mapNotNull { it.description }.filter { "permission" in it }
		assertEquals(listOf(
			"No admin.permissions.central.admin translation in: bg",
			"'admin@staff' is not in database/permissions/central.php — run `php artisan permissions:build --preserve`",
			"No admin.permissions.central.admin@staff translation in: bg",
		), w)
		myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("'reset'") + 2)
		val targets = References.of(myFixture.file.findReferenceAt(myFixture.caretOffset)!!.element)
			.filterIsInstance<PsiPolyVariantReference>().flatMap { it.multiResolve(false).mapNotNull { r -> r.element } }
		assertEquals(listOf("central.php", "admin.php", "admin.php"), targets.map { it.fileName })
	}

	fun testMigrationsInSubfolders() {
		val file = myFixture.addFileToProject("database/migrations/tenant/0002_create_assets_table.php", """
			<?php
			return new class {
				public function up(): void {
					Schema::create('assets', function (Blueprint ${d}table) {
						${d}table->string('code');
					});
				}
			};
		""".trimIndent())
		assertTrue(com.studioitti.laravel.model.Migrations.columns(file, "assets").containsKey("code"))
	}

	fun testDomainGutters() {
		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/Domains/Users/UsersCtrl.php"))
		val ctrl = myFixture.findAllGutters().map { it.tooltipText }
		assertTrue(ctrl.toString(), ctrl.contains("Domain of User"))

		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("app/Domains/Users/ModelSchema.yaml"))
		assertTrue(myFixture.findAllGutters().any { it.tooltipText == "Domain of User" })
	}

	fun testVueSlotGutterLinksBackToSchema() {
		myFixture.addFileToProject("app/Domains/Users/EditSchema.yaml", "edit:\n  formLayout:\n    - slot: two_factor\n")
		myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir("resources/js/Pages/Admin/Users/Edit.vue"))
		val gutters = myFixture.findAllGutters().map { it.tooltipText }
		assertTrue(gutters.toString(), gutters.contains("Schema uses of slot 'two_factor'"))
	}

	fun testAnnotatorRunsOnVueAttributes() {
		val w = warnings("resources/js/Pages/Admin/Users/Index.vue", "<template><v-icon icon=\"mdi-nope\"/></template>")
		assertTrue(w.toString(), w.contains("Unknown icon 'mdi-nope'"))
	}

	/** The glyph comes from the project's own font; this test borrows the real one when it is reachable. */
	fun testIconGlyphFromTheRealFont() {
		val ttf = java.io.File((System.getenv("ITTI_LARAVEL_ROOT") ?: "") + "/node_modules/@mdi/font/fonts/materialdesignicons-webfont.ttf")
		if (!ttf.exists()) return
		myFixture.addFileToProject("node_modules/@mdi/font/fonts/materialdesignicons-webfont.ttf", "")
		val vf = myFixture.findFileInTempDir("node_modules/@mdi/font/fonts/materialdesignicons-webfont.ttf")
		com.intellij.openapi.application.WriteAction.runAndWait<Throwable> { vf.setBinaryContent(ttf.readBytes()) }
		myFixture.configureByText("ListSchema.yaml", "list:\n  icon: mdi-home\n")
		assertTrue(myFixture.findAllGutters().any { it.tooltipText == "mdi-home" })
	}
}
