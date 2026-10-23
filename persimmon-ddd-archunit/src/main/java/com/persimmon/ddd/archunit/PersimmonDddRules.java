package com.persimmon.ddd.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandHandler;
import com.persimmon.ddd.application.DomainEventHandler;
import com.persimmon.ddd.application.EventType;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.Query;
import com.persimmon.ddd.application.QueryHandler;
import com.persimmon.ddd.application.ReadModel;
import com.persimmon.ddd.core.AbstractAggregateRoot;
import com.persimmon.ddd.core.DomainEvent;
import com.persimmon.ddd.core.ErrorCode;
import com.persimmon.ddd.core.IllegalStateTransitionException;
import com.persimmon.ddd.core.Invariant;
import com.persimmon.ddd.core.Transitions;
import com.persimmon.ddd.core.annotation.AggregateRoot;
import com.persimmon.ddd.core.annotation.Entity;
import com.persimmon.ddd.core.annotation.Repository;
import com.persimmon.ddd.core.annotation.Service;
import com.persimmon.ddd.core.annotation.ValueObject;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of a persimmon-ddd code base. Packages name the layers: {@code ..domain..},
 * {@code ..application..}, {@code ..infrastructure..}, {@code ..adapter..} (or {@code
 * ..interfaces..}); a context's published contract lives in {@code ..api..}.
 *
 * <pre>{@code
 * @AnalyzeClasses(packages = "com.example.ordering", importOptions = ImportOption.DoNotIncludeTests.class)
 * class ArchitectureTest {
 *   @ArchTest static final ArchRule ddd = PersimmonDddRules.all();
 * }
 * }</pre>
 */
public final class PersimmonDddRules {
  private static final String[] ADAPTER = {"..adapter..", "..interfaces.."};
  private static final String[] OUTSIDE_DOMAIN = {
    "..application..", "..infrastructure..", "..adapter..", "..interfaces.."
  };
  private static final String[] INFRASTRUCTURE_AND_ADAPTER = {
    "..infrastructure..", "..adapter..", "..interfaces.."
  };
  private static final String[] FRAMEWORKS = {
    "org.springframework..", "jakarta.persistence..", "org.hibernate..", "org.apache.ibatis..",
    "com.baomidou..", "com.fasterxml.jackson..", "io.swagger..", "org.springdoc.."
  };
  private static final String EVENT_LISTENER = "org.springframework.context.event.EventListener";
  private static final String CONTROLLER = "org.springframework.stereotype.Controller";
  private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
  private static final String TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName";
  private static final Set<String> AMBIENT_CALLS =
      Set.of(
          "java.lang.System#currentTimeMillis", "java.lang.System#nanoTime",
          "java.util.UUID#randomUUID", "java.lang.Math#random",
          "java.util.Calendar#getInstance", "java.util.concurrent.ThreadLocalRandom#current",
          "java.time.Clock#systemUTC", "java.time.Clock#systemDefaultZone", "java.time.Clock#system");
  private static final Set<String> AMBIENT_CONSTRUCTORS =
      Set.of("java.util.Date", "java.util.Random", "java.security.SecureRandom", "java.util.GregorianCalendar");

  private PersimmonDddRules() {}

  public static ArchRule all() {
    return CompositeArchRule.of(domainShouldNotDependOnOuterLayers())
        .and(applicationShouldNotDependOnInfrastructureOrAdapters())
        .and(domainShouldBeFrameworkFree())
        .and(domainShouldNotUseAmbientTimeOrRandomness())
        .and(buildingBlocksShouldResideInDomain())
        .and(aggregateRootsShouldExtendAbstractAggregateRoot())
        .and(valueObjectsShouldBeRecordsOrEnums())
        .and(aggregatesShouldReferenceOtherAggregatesByIdentity())
        .and(onlyPersistenceShouldAdvanceTheVersion())
        .and(invariantsShouldResideInDomain())
        .and(illegalStateTransitionsShouldOnlyComeFromTransitions())
        .and(errorCodesShouldBeEnums())
        .and(domainEventsShouldStayInDomain())
        .and(domainEventListenersShouldBeApplicationHandlers())
        .and(integrationEventListenersShouldResideInAdapters())
        .and(integrationEventsShouldDeclareAUniqueEventType())
        .and(commandsQueriesAndHandlersShouldResideInApplication())
        .and(commandHandlersShouldNotDependOnOtherCommandHandlers())
        .and(readModelsAndQueryResultsShouldNotBeTheWriteModel())
        .and(controllerSignaturesShouldNotExposeTheWriteModel())
        .and(repositoryPortsShouldBeDomainInterfaces())
        .and(repositoryImplementationsShouldResideInInfrastructure())
        .and(adaptersShouldNotUseRepositoryPorts())
        .and(persistenceRowsShouldResideInInfrastructure())
        .and(apiPackagesShouldHoldOnlyContracts())
        .and(dependenciesShouldBeConstructorInjected());
  }

  // ----- layering -----

  public static ArchRule domainShouldNotDependOnOuterLayers() {
    return noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(OUTSIDE_DOMAIN)
        .as("domain classes should not depend on the application, infrastructure or adapter layers")
        .allowEmptyShould(true);
  }

  public static ArchRule applicationShouldNotDependOnInfrastructureOrAdapters() {
    return noClasses().that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE_AND_ADAPTER)
        .as("application classes should not depend on the infrastructure or adapter layers")
        .allowEmptyShould(true);
  }

  /** The domain knows persimmon-ddd-core and the JDK; no Spring, ORM, JSON or OpenAPI, no other module. */
  public static ArchRule domainShouldBeFrameworkFree() {
    DescribedPredicate<JavaClass> otherModules =
        DescribedPredicate.describe(
            "persimmon-ddd modules other than core",
            c -> c.getName().startsWith("com.persimmon.ddd.")
                && !c.getName().startsWith("com.persimmon.ddd.core.")
                && !c.getName().startsWith("com.persimmon.ddd.archunit."));
    return noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORKS)
        .orShould().dependOnClassesThat(otherModules)
        .as("domain classes should depend on nothing but the JDK and persimmon-ddd-core")
        .allowEmptyShould(true);
  }

  public static ArchRule domainShouldNotUseAmbientTimeOrRandomness() {
    ArchCondition<JavaClass> condition =
        new ArchCondition<>("not read ambient time or randomness") {
          @Override
          public void check(JavaClass origin, ConditionEvents events) {
            origin.getMethodCallsFromSelf().forEach(call -> {
              String owner = call.getTargetOwner().getName();
              String name = call.getTarget().getName();
              boolean nowWithoutClock =
                  owner.startsWith("java.time.") && name.equals("now")
                      && call.getTarget().getRawParameterTypes().stream()
                          .noneMatch(p -> p.getName().equals("java.time.Clock"));
              if (AMBIENT_CALLS.contains(owner + '#' + name) || nowWithoutClock) {
                events.add(SimpleConditionEvent.violated(call, call.getDescription()));
              }
            });
            origin.getConstructorCallsFromSelf().forEach(call -> {
              if (AMBIENT_CONSTRUCTORS.contains(call.getTargetOwner().getName())
                  && call.getTarget().getRawParameterTypes().isEmpty()) {
                events.add(SimpleConditionEvent.violated(call, call.getDescription()));
              }
            });
          }
        };
    return classes().that().resideInAPackage("..domain..").should(condition)
        .as("domain classes should take time and randomness as arguments, not read them")
        .allowEmptyShould(true);
  }

  // ----- building blocks -----

  public static ArchRule buildingBlocksShouldResideInDomain() {
    return CompositeArchRule.of(
            classes().that().areAnnotatedWith(AggregateRoot.class).or().areAnnotatedWith(Entity.class)
                .or().areAnnotatedWith(Service.class).or().implement(DomainEvent.class)
                .should().resideInAPackage("..domain..").allowEmptyShould(true))
        .and(
            classes().that().areAnnotatedWith(ValueObject.class)
                .should().resideInAnyPackage("..domain..", "..api..").allowEmptyShould(true))
        .as("@AggregateRoot, @Entity, @Service and domain events reside in the domain; @ValueObject in the domain or ..api..");
  }

  public static ArchRule aggregateRootsShouldExtendAbstractAggregateRoot() {
    return classes().that().areAnnotatedWith(AggregateRoot.class)
        .should().beAssignableTo(AbstractAggregateRoot.class)
        .as("@AggregateRoot types should extend AbstractAggregateRoot")
        .allowEmptyShould(true);
  }

  /** A record or enum is immutable and compared by value for free; a class has to prove both. */
  public static ArchRule valueObjectsShouldBeRecordsOrEnums() {
    return classes().that().areAnnotatedWith(ValueObject.class).and().areNotInterfaces()
        .should().beRecords().orShould().beEnums()
        .as("@ValueObject types should be records or enums")
        .allowEmptyShould(true);
  }

  public static ArchRule aggregatesShouldReferenceOtherAggregatesByIdentity() {
    return classes().that().areAnnotatedWith(AggregateRoot.class).or().areAnnotatedWith(Entity.class)
        .should(notHoldFieldsOf("another @AggregateRoot", c -> c.isAnnotatedWith(AggregateRoot.class)))
        .as("aggregates should reference other aggregates by identity, not hold them")
        .allowEmptyShould(true);
  }

  public static ArchRule onlyPersistenceShouldAdvanceTheVersion() {
    ArchCondition<JavaClass> condition =
        new ArchCondition<>("not call versionAdvanced()") {
          @Override
          public void check(JavaClass origin, ConditionEvents events) {
            origin.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTarget().getName().equals("versionAdvanced")
                    && call.getTarget().getOwner().isAssignableTo(AbstractAggregateRoot.class))
                .forEach(call -> events.add(SimpleConditionEvent.violated(call, call.getDescription())));
          }
        };
    return classes().that().resideInAnyPackage("..domain..", "..application..").should(condition)
        .as("only a persistence adapter advances the optimistic-lock version")
        .allowEmptyShould(true);
  }

  public static ArchRule invariantsShouldResideInDomain() {
    return classes().that().implement(Invariant.class).should().resideInAPackage("..domain..")
        .as("Invariant implementations should reside in the domain")
        .allowEmptyShould(true);
  }

  public static ArchRule illegalStateTransitionsShouldOnlyComeFromTransitions() {
    return noClasses().that().doNotHaveFullyQualifiedName(Transitions.class.getName())
        .should().callConstructor(IllegalStateTransitionException.class, ErrorCode.class, Object.class, Object.class)
        .as("IllegalStateTransitionException should be raised only by Transitions.check")
        .allowEmptyShould(true);
  }

  public static ArchRule errorCodesShouldBeEnums() {
    return classes().that().implement(ErrorCode.class).should().beEnums()
        .as("ErrorCode implementations should be enums")
        .allowEmptyShould(true);
  }

  // ----- events -----

  public static ArchRule domainEventsShouldStayInDomain() {
    return classes().that().implement(DomainEvent.class).should().resideInAPackage("..domain..")
        .as("domain events should reside in the domain")
        .allowEmptyShould(true);
  }

  public static ArchRule domainEventListenersShouldBeApplicationHandlers() {
    return methods().that(listenersOf(DomainEvent.class))
        .should().beDeclaredInClassesThat().resideInAnyPackage("..application..", "..domain..")
        .andShould().beDeclaredInClassesThat().areAnnotatedWith(DomainEventHandler.class)
        .as("domain-event @EventListener methods belong to @DomainEventHandler classes in the application or domain")
        .allowEmptyShould(true);
  }

  public static ArchRule integrationEventListenersShouldResideInAdapters() {
    return methods().that(listenersOf(IntegrationEvent.class))
        .should().beDeclaredInClassesThat().resideInAnyPackage(ADAPTER)
        .as("integration-event @EventListener methods are inbound adapters")
        .allowEmptyShould(true);
  }

  public static ArchRule integrationEventsShouldDeclareAUniqueEventType() {
    ArchCondition<JavaClass> condition =
        new ArchCondition<>("declare a unique @EventType(context, name, version >= 1)") {
          private final Map<String, List<String>> byKey = new HashMap<>();

          @Override
          public void init(Collection<JavaClass> all) {
            byKey.clear();
            for (JavaClass c : all) {
              if (c.isAnnotatedWith(EventType.class)) {
                byKey.computeIfAbsent(key(c.getAnnotationOfType(EventType.class)), k -> new java.util.ArrayList<>()).add(c.getName());
              }
            }
          }

          @Override
          public void check(JavaClass event, ConditionEvents events) {
            if (!event.isAnnotatedWith(EventType.class)) {
              events.add(SimpleConditionEvent.violated(event, event.getName() + " has no @EventType"));
              return;
            }
            EventType type = event.getAnnotationOfType(EventType.class);
            if (type.context().isBlank() || type.context().contains(".") || type.name().isBlank() || type.version() < 1) {
              events.add(SimpleConditionEvent.violated(event, event.getName() + " declares a blank context or name, a dotted context, or version < 1"));
            }
            List<String> sharing = byKey.get(key(type));
            if (sharing.size() > 1) {
              events.add(SimpleConditionEvent.violated(event, event.getName() + " shares @EventType " + key(type) + " with " + sharing));
            }
          }

          private String key(EventType type) {
            return type.context() + "." + type.name() + " v" + type.version();
          }
        };
    return classes().that().implement(IntegrationEvent.class).and().areNotInterfaces().should(condition)
        .as("integration events should declare a unique @EventType")
        .allowEmptyShould(true);
  }

  // ----- CQRS -----

  public static ArchRule commandsQueriesAndHandlersShouldResideInApplication() {
    return classes().that().implement(Command.class).or().implement(Query.class)
        .or().implement(CommandHandler.class).or().implement(QueryHandler.class)
        .should().resideInAPackage("..application..")
        .as("commands, queries and their handlers should reside in the application layer")
        .allowEmptyShould(true);
  }

  public static ArchRule commandHandlersShouldNotDependOnOtherCommandHandlers() {
    ArchCondition<JavaClass> condition =
        new ArchCondition<>("not depend on another CommandHandler") {
          @Override
          public void check(JavaClass origin, ConditionEvents events) {
            origin.getDirectDependenciesFromSelf().forEach(d -> {
              JavaClass target = d.getTargetClass();
              if (target.isAssignableTo(CommandHandler.class) && !target.isEquivalentTo(CommandHandler.class)
                  && !target.getName().equals(origin.getName())) {
                events.add(SimpleConditionEvent.violated(d, d.getDescription()));
              }
            });
          }
        };
    return classes().that().implement(CommandHandler.class).should(condition)
        .as("command handlers should not depend on other command handlers; send a command instead")
        .allowEmptyShould(true);
  }

  public static ArchRule readModelsAndQueryResultsShouldNotBeTheWriteModel() {
    ArchCondition<JavaClass> resultIsNotWriteModel =
        new ArchCondition<>("declare a result type that is not an @AggregateRoot or @Entity") {
          @Override
          public void check(JavaClass query, ConditionEvents events) {
            Set<JavaClass> results = new LinkedHashSet<>();
            collectQueryResults(query, results, new LinkedHashSet<>());
            results.stream().filter(PersimmonDddRules::isWriteModel).forEach(r ->
                events.add(SimpleConditionEvent.violated(query, query.getName() + " returns write model " + r.getName())));
          }
        };
    return CompositeArchRule.of(
            classes().that().areAnnotatedWith(ReadModel.class)
                .should().resideInAnyPackage("..application..", "..api..")
                .andShould(notHoldFieldsOf("an @AggregateRoot or @Entity", PersimmonDddRules::isWriteModel))
                .allowEmptyShould(true))
        .and(classes().that().implement(Query.class).should(resultIsNotWriteModel).allowEmptyShould(true))
        .as("@ReadModel types live in the application or ..api.. and neither they nor query results carry the write model");
  }

  public static ArchRule controllerSignaturesShouldNotExposeTheWriteModel() {
    ArchCondition<JavaClass> condition =
        new ArchCondition<>("not name an @AggregateRoot or @Entity in an endpoint signature") {
          @Override
          public void check(JavaClass controller, ConditionEvents events) {
            for (JavaMethod method : controller.getMethods()) {
              if (!method.isAnnotatedWith(REQUEST_MAPPING) && !method.isMetaAnnotatedWith(REQUEST_MAPPING)) {
                continue;
              }
              Set<JavaClass> involved = new LinkedHashSet<>(method.getReturnType().getAllInvolvedRawTypes());
              for (JavaType parameter : method.getParameterTypes()) {
                involved.addAll(parameter.getAllInvolvedRawTypes());
              }
              involved.stream().filter(PersimmonDddRules::isWriteModel).forEach(exposed ->
                  events.add(SimpleConditionEvent.violated(method, method.getFullName() + " exposes " + exposed.getName())));
            }
          }
        };
    return classes().that().areAnnotatedWith(CONTROLLER).or().areMetaAnnotatedWith(CONTROLLER).should(condition)
        .as("controller endpoint signatures should not expose aggregates or entities")
        .allowEmptyShould(true);
  }

  // ----- repositories -----

  public static ArchRule repositoryPortsShouldBeDomainInterfaces() {
    return classes().that().areAnnotatedWith(Repository.class)
        .should().beInterfaces().andShould().resideInAPackage("..domain..")
        .as("@Repository ports should be interfaces in the domain")
        .allowEmptyShould(true);
  }

  public static ArchRule repositoryImplementationsShouldResideInInfrastructure() {
    return classes().that(implementARepositoryPort()).should().resideInAPackage("..infrastructure..")
        .as("repository implementations should reside in the infrastructure layer")
        .allowEmptyShould(true);
  }

  public static ArchRule adaptersShouldNotUseRepositoryPorts() {
    return noClasses().that().resideInAnyPackage(ADAPTER)
        .should().dependOnClassesThat().areAnnotatedWith(Repository.class)
        .as("adapters should go through the application layer, not repository ports")
        .allowEmptyShould(true);
  }

  /** A table row class is the shape of one context's storage; it never crosses a layer. */
  public static ArchRule persistenceRowsShouldResideInInfrastructure() {
    return classes().that().areAnnotatedWith(TABLE_NAME).should().resideInAPackage("..infrastructure..")
        .as("@TableName row classes should reside in the infrastructure layer")
        .allowEmptyShould(true);
  }

  // ----- injection -----

  public static ArchRule dependenciesShouldBeConstructorInjected() {
    String autowired = "org.springframework.beans.factory.annotation.Autowired";
    String value = "org.springframework.beans.factory.annotation.Value";
    String inject = "jakarta.inject.Inject";
    return CompositeArchRule.of(
            noFields().should().beAnnotatedWith(autowired).orShould().beAnnotatedWith(value)
                .orShould().beAnnotatedWith(inject).allowEmptyShould(true))
        .and(noMethods().should().beAnnotatedWith(autowired).orShould().beAnnotatedWith(inject).allowEmptyShould(true))
        .as("dependencies should be constructor-injected, not set on fields or through setters");
  }

  // ----- bounded contexts -----

  /** The contract another context compiles against: events, DTOs and call interfaces, never behaviour. */
  public static ArchRule apiPackagesShouldHoldOnlyContracts() {
    return classes().that().resideInAPackage("..api..")
        .should().beInterfaces().orShould().beRecords().orShould().beEnums()
        .as("..api.. packages should hold only interfaces, records, enums and annotations")
        .allowEmptyShould(true);
  }

  /** Contexts under {@code basePackage} (one sub-package each) may only use another's {@code .api} package. */
  public static ArchRule boundedContextsShouldDependOnEachOtherOnlyThroughApi(String basePackage) {
    String prefix = basePackage.endsWith(".") ? basePackage : basePackage + ".";
    ArchCondition<JavaClass> condition =
        new ArchCondition<>("depend on other bounded contexts only through their ..api.. packages") {
          @Override
          public void check(JavaClass origin, ConditionEvents events) {
            String from = contextOf(origin.getName());
            if (from == null) {
              return;
            }
            origin.getDirectDependenciesFromSelf().forEach(d -> {
              String to = contextOf(d.getTargetClass().getName());
              String api = prefix + to + ".api";
              if (to != null && !to.equals(from)
                  && !d.getTargetClass().getPackageName().equals(api)
                  && !d.getTargetClass().getPackageName().startsWith(api + ".")) {
                events.add(SimpleConditionEvent.violated(d, d.getDescription()));
              }
            });
          }

          private String contextOf(String className) {
            if (!className.startsWith(prefix)) {
              return null;
            }
            String rest = className.substring(prefix.length());
            int dot = rest.indexOf('.');
            return dot < 0 ? null : rest.substring(0, dot);
          }
        };
    return classes().that().resideInAPackage(basePackage + "..").should(condition)
        .as("bounded contexts should depend on each other only through their ..api.. packages")
        .allowEmptyShould(true);
  }

  // ----- helpers -----

  private static boolean isWriteModel(JavaClass c) {
    return c.isAnnotatedWith(AggregateRoot.class) || c.isAnnotatedWith(Entity.class);
  }

  private static DescribedPredicate<JavaMethod> listenersOf(Class<?> marker) {
    return DescribedPredicate.describe(
        "@EventListener methods taking a " + marker.getSimpleName(),
        m -> (m.isAnnotatedWith(EVENT_LISTENER) || m.isMetaAnnotatedWith(EVENT_LISTENER))
            && m.getParameterTypes().stream()
                .flatMap(p -> p.getAllInvolvedRawTypes().stream())
                .anyMatch(p -> p.isAssignableTo(marker)));
  }

  private static DescribedPredicate<JavaClass> implementARepositoryPort() {
    return DescribedPredicate.describe(
        "implement a @Repository port",
        c -> !c.isInterface() && c.getAllRawInterfaces().stream().anyMatch(i -> i.isAnnotatedWith(Repository.class)));
  }

  private static ArchCondition<JavaClass> notHoldFieldsOf(String what, java.util.function.Predicate<JavaClass> held) {
    return new ArchCondition<>("not hold " + what) {
      @Override
      public void check(JavaClass owner, ConditionEvents events) {
        owner.getFields().stream()
            .filter(f -> !f.getModifiers().contains(JavaModifier.STATIC) && !f.getModifiers().contains(JavaModifier.SYNTHETIC))
            .forEach(f -> f.getType().getAllInvolvedRawTypes().stream().filter(held).forEach(h ->
                events.add(SimpleConditionEvent.violated(f, f.getDescription() + " holds " + h.getName()))));
      }
    };
  }

  private static void collectQueryResults(JavaClass type, Set<JavaClass> results, Set<String> visited) {
    if (!visited.add(type.getName())) {
      return;
    }
    for (JavaType implemented : type.getInterfaces()) {
      if (implemented.toErasure().isEquivalentTo(Query.class)
          && implemented instanceof JavaParameterizedType parameterized
          && parameterized.getActualTypeArguments().size() == 1) {
        results.addAll(parameterized.getActualTypeArguments().get(0).getAllInvolvedRawTypes());
      }
      collectQueryResults(implemented.toErasure(), results, visited);
    }
    type.getRawSuperclass().ifPresent(s -> collectQueryResults(s, results, visited));
  }
}
