package io.webagent4j.integration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
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
}
