package com.panther.ticketing.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.panther.ticketing",
        importOptions = { ImportOption.DoNotIncludeTests.class }
)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule domain_should_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..model..")
                    .or().resideInAPackage("..ports..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..driven..", "..api..", "..config..", "..scheduler..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule usecase_should_not_depend_on_infrastructure =
            noClasses()
                    .that().resideInAPackage("..usecase..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..driven..", "..api..", "..scheduler..")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domain_model_should_not_depend_on_usecase =
            noClasses()
                    .that().resideInAPackage("..model..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..usecase..")
                    .allowEmptyShould(true);
}
