package com.persimmon.ddd.archunit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PersimmonDddRulesTest {
  private static final JavaClasses GOOD = new ClassFileImporter().importPackages("com.persimmon.ddd.archunit.good");
  private static final JavaClasses BAD = new ClassFileImporter().importPackages("com.persimmon.ddd.archunit.bad");

  @Test
  void aWellShapedCodeBasePassesEveryRule() {
    assertDoesNotThrow(() -> PersimmonDddRules.all().check(GOOD));
    assertDoesNotThrow(
        () -> PersimmonDddRules.boundedContextsShouldDependOnEachOtherOnlyThroughApi("com.persimmon.ddd.archunit.good").check(GOOD));
  }

  @Test
  void eachRuleCatchesItsViolation() {
    List<Supplier<ArchRule>> rules =
        List.of(
            PersimmonDddRules::domainShouldBeFrameworkFree,
            PersimmonDddRules::domainShouldNotUseAmbientTimeOrRandomness,
            PersimmonDddRules::aggregateRootsShouldExtendAbstractAggregateRoot,
            PersimmonDddRules::valueObjectsShouldBeRecordsOrEnums,
            PersimmonDddRules::aggregatesShouldReferenceOtherAggregatesByIdentity,
            PersimmonDddRules::onlyPersistenceShouldAdvanceTheVersion,
            PersimmonDddRules::integrationEventsShouldDeclareAUniqueEventType,
            PersimmonDddRules::commandHandlersShouldNotDependOnOtherCommandHandlers,
            PersimmonDddRules::readModelsAndQueryResultsShouldNotBeTheWriteModel,
            PersimmonDddRules::controllerSignaturesShouldNotExposeTheWriteModel,
            PersimmonDddRules::dependenciesShouldBeConstructorInjected);
    for (Supplier<ArchRule> rule : rules) {
      EvaluationResult result = rule.get().evaluate(BAD);
      assertTrue(result.hasViolation(), () -> rule.get().getDescription() + " should have failed");
    }
    assertTrue(PersimmonDddRules.commandsQueriesAndHandlersShouldResideInApplication().evaluate(GOOD).hasViolation() == false);
  }
}
