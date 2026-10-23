package com.example.app;

import com.persimmon.ddd.archunit.PersimmonDddRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.example.app", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest static final ArchRule ddd = PersimmonDddRules.all();

  /** ordering may use another context only through that context's ..api.. package, and so on. */
  @ArchTest
  static final ArchRule contexts = PersimmonDddRules.boundedContextsShouldDependOnEachOtherOnlyThroughApi("com.example.app");
}
