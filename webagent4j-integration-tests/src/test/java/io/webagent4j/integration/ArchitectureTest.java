package io.webagent4j.integration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private final JavaClasses projectClasses =
            new ClassFileImporter().importPackages("io.webagent4j");

    @Test
    void packageSlicesHaveNoCycles() {
        slices().matching("io.webagent4j.(*)..")
                .should()
                .beFreeOfCycles()
                .ignoreDependency(
                        JavaClass.Predicates.resideInAnyPackage("io.webagent4j.dom.."),
                        JavaClass.Predicates.resideInAnyPackage("io.webagent4j.locator.api.."))
                .ignoreDependency(
                        JavaClass.Predicates.resideInAnyPackage("io.webagent4j.dom.."),
                        JavaClass.Predicates.resideInAnyPackage("io.webagent4j.extraction.api.."))
                .ignoreDependency(
                        JavaClass.Predicates.resideInAnyPackage("io.webagent4j.browser.."),
                        JavaClass.Predicates.resideInAnyPackage("io.webagent4j.observation.."))
                .ignoreDependency(
                        JavaClass.Predicates.resideInAnyPackage("io.webagent4j.observation.."),
                        JavaClass.Predicates.resideInAnyPackage("io.webagent4j.browser.."))
                .check(projectClasses);
    }

    @Test
    void coreNeverDependsOnPlaywright() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.microsoft.playwright..")
                .check(projectClasses);
    }

    @Test
    void stableContractsNeverDependOnBackendImplementations() {
        noClasses()
                .that()
                .resideInAnyPackage("io.webagent4j.locator..", "io.webagent4j.browser..")
                .and()
                .resideOutsideOfPackage("io.webagent4j.browser.playwright..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void framePublicApiNeverLeaksNativePlaywrightTypes() {
        noClasses()
                .that()
                .haveSimpleNameStartingWith("IFrame")
                .or()
                .haveSimpleName("FrameDefinition")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void semanticObservationRemainsIndependentFromPlaywright() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.observation..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void semanticObservationRemainsIndependentFromAiLibraries() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.observation..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.openai..", "dev.langchain4j..", "org.springframework.ai..")
                .check(projectClasses);
    }

    @Test
    void waitEngineRemainsIndependentFromEveryDomainModule() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.wait..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.webagent4j.dom..",
                        "io.webagent4j.locator..",
                        "io.webagent4j.verification..",
                        "io.webagent4j.action..",
                        "io.webagent4j.browser..",
                        "io.webagent4j.observation..",
                        "com.microsoft.playwright..")
                .check(projectClasses);
    }

    @Test
    void extractionRemainsIndependentFromPlaywright() {
        noClasses()
                .that()
                .resideInAnyPackage("io.webagent4j.extraction..", "io.webagent4j.extraction.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void extractionApiRemainsIndependentFromTheLocatorEngineModule() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.extraction.api..")
                .should()
                .dependOnClassesThat(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "reside in io.webagent4j.locator.. but outside io.webagent4j.locator.api..",
                                javaClass ->
                                        javaClass
                                                        .getPackageName()
                                                        .startsWith("io.webagent4j.locator")
                                                && !javaClass
                                                        .getPackageName()
                                                        .startsWith("io.webagent4j.locator.api")))
                .check(projectClasses);
    }

    @Test
    void extractionApiRemainsIndependentFromDom() {
        // io.webagent4j.dom is allowed to depend on extraction.api (IElement#extract - see
        // packageSlicesHaveNoCycles' matching ignoreDependency), so the direction matters: only
        // dom -> extraction.api is legitimate, never the reverse.
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.extraction.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.webagent4j.dom..")
                .check(projectClasses);
    }

    @Test
    void crawlerRemainsIndependentFromPlaywright() {
        noClasses()
                .that()
                .resideInAnyPackage("io.webagent4j.crawler..", "io.webagent4j.crawler.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void crawlerRemainsIndependentFromAiLibraries() {
        noClasses()
                .that()
                .resideInAnyPackage("io.webagent4j.crawler..", "io.webagent4j.crawler.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.openai..", "dev.langchain4j..", "org.springframework.ai..")
                .check(projectClasses);
    }

    @Test
    void browserCrawlerRemainsIndependentFromPlaywright() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.browsercrawler..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void browserCrawlerRemainsIndependentFromAiLibraries() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.browsercrawler..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.openai..", "dev.langchain4j..", "org.springframework.ai..")
                .check(projectClasses);
    }

    @Test
    void crawlerApiRemainsIndependentFromTheCrawlerEngineModule() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.crawler.api..")
                .should()
                .dependOnClassesThat(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "reside in io.webagent4j.crawler.. but outside"
                                        + " io.webagent4j.crawler.api..",
                                javaClass ->
                                        javaClass
                                                        .getPackageName()
                                                        .startsWith("io.webagent4j.crawler")
                                                && !javaClass
                                                        .getPackageName()
                                                        .startsWith("io.webagent4j.crawler.api")))
                .check(projectClasses);
    }

    @Test
    void actionAndVerificationRemainIndependentFromPlaywright() {
        noClasses()
                .that()
                .resideInAnyPackage("io.webagent4j.action..", "io.webagent4j.verification..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void publicActionContractsDoNotDependOnInternalImplementations() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.action")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.webagent4j.action.internal..")
                .check(projectClasses);
    }

    @Test
    void publicObservationContractsDoNotDependOnInternalImplementations() {
        assertThat(
                        projectClasses.stream()
                                .filter(
                                        type ->
                                                type.getPackageName()
                                                        .equals("io.webagent4j.observation"))
                                .filter(type -> !type.getSimpleName().equals("ObservationEngine")))
                .allSatisfy(
                        type ->
                                assertThat(type.getDirectDependenciesFromSelf())
                                        .noneMatch(
                                                dependency ->
                                                        dependency
                                                                .getTargetClass()
                                                                .getPackageName()
                                                                .startsWith(
                                                                        "io.webagent4j.observation"
                                                                                + ".internal")));
    }

    @Test
    void coreDoesNotDependOnObservationImplementations() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.webagent4j.observation.internal..")
                .check(projectClasses);
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.core..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("io.webagent4j.observation.ObservationEngine")
                .check(projectClasses);
    }

    @Test
    void abstractClassesUseTheProjectPrefix() {
        assertThat(
                        projectClasses.stream()
                                .filter(type -> !type.isInterface())
                                .filter(
                                        type ->
                                                type.getModifiers()
                                                        .contains(JavaModifier.ABSTRACT)))
                .allMatch(type -> type.getSimpleName().startsWith("A"));
    }

    @Test
    void workflowRemainsIndependentFromPlaywright() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.workflow..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void workflowRemainsIndependentFromAiLibraries() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.workflow..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.openai..", "dev.langchain4j..", "org.springframework.ai..")
                .check(projectClasses);
    }

    @Test
    void workflowRemainsIndependentFromBrowserAndCrawlerModules() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.workflow..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.webagent4j.browser..",
                        "io.webagent4j.crawler..",
                        "io.webagent4j.crawler.api..",
                        "io.webagent4j.browsercrawler..",
                        "io.webagent4j.recording..",
                        "io.webagent4j.plugin..",
                        "io.webagent4j.cli..")
                .check(projectClasses);
    }

    @Test
    void recordingRemainsIndependentFromPlaywright() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.recording..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.microsoft.playwright..", "io.webagent4j.browser.playwright..")
                .check(projectClasses);
    }

    @Test
    void recordingRemainsIndependentFromBrowserAndCrawlerModules() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.recording..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.webagent4j.browser..",
                        "io.webagent4j.crawler..",
                        "io.webagent4j.crawler.api..",
                        "io.webagent4j.browsercrawler..")
                .check(projectClasses);
    }

    @Test
    void recordingRemainsIndependentFromPluginApi() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.recording..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.webagent4j.plugin..")
                .check(projectClasses);
    }

    @Test
    void locatorAndCoreRemainIndependentFromPluginApi() {
        noClasses()
                .that()
                .resideInAnyPackage("io.webagent4j.locator..", "io.webagent4j.core..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.webagent4j.plugin..")
                .check(projectClasses);
    }

    @Test
    void pluginApiDependsOnlyOnLocatorAndTheJdk() {
        assertThat(
                        projectClasses.stream()
                                .filter(
                                        type ->
                                                type.getPackageName()
                                                        .startsWith("io.webagent4j.plugin")))
                .allSatisfy(
                        type ->
                                assertThat(type.getDirectDependenciesFromSelf())
                                        .noneMatch(
                                                dependency -> {
                                                    String packageName =
                                                            dependency
                                                                    .getTargetClass()
                                                                    .getPackageName();
                                                    return packageName.startsWith("io.webagent4j")
                                                            && !packageName.startsWith(
                                                                    "io.webagent4j.plugin")
                                                            && !packageName.startsWith(
                                                                    "io.webagent4j.locator");
                                                }));
    }

    @Test
    void pluginApiRemainsIndependentFromBackendsAndAiLibraries() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.plugin..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "io.webagent4j.browser..",
                        "com.microsoft.playwright..",
                        "com.openai..",
                        "dev.langchain4j..",
                        "org.springframework.ai..")
                .check(projectClasses);
    }

    @Test
    void publicSignaturesPreserveBackendAndSerializationBoundaries() {
        assertPublicSignaturesExclude(
                type ->
                        (type.getPackageName().startsWith("io.webagent4j.browser")
                                        && !type.getPackageName()
                                                .startsWith("io.webagent4j.browser.playwright"))
                                || type.getPackageName().startsWith("io.webagent4j.locator.api")
                                || type.getPackageName().startsWith("io.webagent4j.observation")
                                || type.getPackageName().startsWith("io.webagent4j.extraction.api")
                                || type.getPackageName().startsWith("io.webagent4j.crawler.api"),
                Set.of("com.microsoft.playwright"));
        assertPublicSignaturesExclude(
                type -> type.getPackageName().startsWith("io.webagent4j.recording"),
                Set.of("com.fasterxml.jackson"));
        assertPublicSignaturesExclude(
                type -> type.getPackageName().startsWith("io.webagent4j.crawler.api"),
                Set.of("org.jsoup"));
        assertPublicSignaturesExclude(
                type -> type.getPackageName().startsWith("io.webagent4j.plugin"),
                Set.of(
                        "com.fasterxml.jackson",
                        "com.microsoft.playwright",
                        "info.picocli",
                        "net.bytebuddy",
                        "org.jsoup",
                        "org.slf4j"));
    }

    @Test
    void recordingRemainsIndependentFromAiLibraries() {
        noClasses()
                .that()
                .resideInAPackage("io.webagent4j.recording..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.openai..", "dev.langchain4j..", "org.springframework.ai..")
                .check(projectClasses);
    }

    @Test
    void interfacesUseTheProjectPrefix() {
        // package-info.java compiles to a synthetic interface at the bytecode level, so
        // areInterfaces() matches it too; it is excluded explicitly rather than renamed, since
        // "Ipackage-info" is not a real option.
        classes()
                .that(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "are interfaces excluding the synthetic package-info type",
                                javaClass ->
                                        javaClass.isInterface()
                                                && !javaClass
                                                        .getSimpleName()
                                                        .equals("package-info")))
                .and()
                .resideInAPackage("io.webagent4j..")
                .should()
                .haveSimpleNameStartingWith("I")
                .check(projectClasses);
    }

    private void assertPublicSignaturesExclude(
            Predicate<Class<?>> typeSelector, Set<String> forbiddenPackagePrefixes) {
        List<String> violations = new ArrayList<>();
        projectClasses.stream()
                .map(ArchitectureTest::loadWithoutInitialization)
                .filter(ArchitectureTest::isEffectivelyPublic)
                .filter(typeSelector)
                .forEach(
                        type ->
                                publicSignatureTypes(type).stream()
                                        .filter(
                                                signatureType ->
                                                        forbiddenPackagePrefixes.stream()
                                                                .anyMatch(
                                                                        prefix ->
                                                                                signatureType
                                                                                        .getTypeName()
                                                                                        .startsWith(
                                                                                                prefix)))
                                        .forEach(
                                                signatureType ->
                                                        violations.add(
                                                                type.getName()
                                                                        + " exposes "
                                                                        + signatureType
                                                                                .getTypeName())));

        assertThat(violations).isEmpty();
    }

    private static Class<?> loadWithoutInitialization(JavaClass type) {
        try {
            return Class.forName(type.getName(), false, ArchitectureTest.class.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Could not inspect " + type.getName(), failure);
        }
    }

    private static boolean isEffectivelyPublic(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            return false;
        }
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null || isEffectivelyPublic(enclosing);
    }

    private static Set<Class<?>> publicSignatureTypes(Class<?> type) {
        Set<Class<?>> result = new HashSet<>();
        Set<Type> visited = new HashSet<>();
        collect(type.getGenericSuperclass(), result, visited);
        Arrays.stream(type.getGenericInterfaces())
                .forEach(value -> collect(value, result, visited));
        Arrays.stream(type.getTypeParameters())
                .flatMap(value -> Arrays.stream(value.getBounds()))
                .forEach(value -> collect(value, result, visited));
        Arrays.stream(type.getDeclaredFields())
                .filter(ArchitectureTest::isPublicOrProtected)
                .map(Field::getGenericType)
                .forEach(value -> collect(value, result, visited));
        Arrays.stream(type.getDeclaredConstructors())
                .filter(ArchitectureTest::isPublicOrProtected)
                .forEach(constructor -> collect(constructor, result, visited));
        Arrays.stream(type.getDeclaredMethods())
                .filter(ArchitectureTest::isPublicOrProtected)
                .forEach(method -> collect(method, result, visited));
        return result;
    }

    private static boolean isPublicOrProtected(Member member) {
        int modifiers = member.getModifiers();
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void collect(
            Constructor<?> constructor, Set<Class<?>> result, Set<Type> visited) {
        Arrays.stream(constructor.getGenericParameterTypes())
                .forEach(value -> collect(value, result, visited));
        Arrays.stream(constructor.getGenericExceptionTypes())
                .forEach(value -> collect(value, result, visited));
        Arrays.stream(constructor.getTypeParameters())
                .flatMap(value -> Arrays.stream(value.getBounds()))
                .forEach(value -> collect(value, result, visited));
    }

    private static void collect(Method method, Set<Class<?>> result, Set<Type> visited) {
        collect(method.getGenericReturnType(), result, visited);
        Arrays.stream(method.getGenericParameterTypes())
                .forEach(value -> collect(value, result, visited));
        Arrays.stream(method.getGenericExceptionTypes())
                .forEach(value -> collect(value, result, visited));
        Arrays.stream(method.getTypeParameters())
                .flatMap(value -> Arrays.stream(value.getBounds()))
                .forEach(value -> collect(value, result, visited));
    }

    private static void collect(Type type, Set<Class<?>> result, Set<Type> visited) {
        if (type == null || !visited.add(type)) {
            return;
        }
        if (type instanceof Class<?> rawType) {
            result.add(rawType);
        } else if (type instanceof ParameterizedType parameterized) {
            collect(parameterized.getRawType(), result, visited);
            collect(parameterized.getOwnerType(), result, visited);
            Arrays.stream(parameterized.getActualTypeArguments())
                    .forEach(value -> collect(value, result, visited));
        } else if (type instanceof GenericArrayType array) {
            collect(array.getGenericComponentType(), result, visited);
        } else if (type instanceof WildcardType wildcard) {
            Arrays.stream(wildcard.getUpperBounds())
                    .forEach(value -> collect(value, result, visited));
            Arrays.stream(wildcard.getLowerBounds())
                    .forEach(value -> collect(value, result, visited));
        } else if (type instanceof TypeVariable<?> variable) {
            Arrays.stream(variable.getBounds()).forEach(value -> collect(value, result, visited));
        }
    }
}
