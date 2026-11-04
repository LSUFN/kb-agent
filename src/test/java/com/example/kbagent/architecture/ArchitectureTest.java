package com.example.kbagent.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 架构约束（ARCHITECTURE.md §3.2）。
 * 包约定：com.example.kbagent.&lt;feature&gt;.{web, service, repository, domain}；
 * 工具位于 ..tool..，Agent 位于 ..agent..。
 * M0 时多数包尚不存在，因此规则允许为空（allowEmptyShould），随模块落地自动生效。
 */
class ArchitectureTest {

    private static final String ROOT = "com.example.kbagent";

    /** 能直接执行任意 SQL 的组件。 */
    private static final Set<String> RAW_SQL_TYPES = Set.of(
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "org.springframework.jdbc.core.simple.JdbcClient",
            "jakarta.persistence.EntityManager",
            "javax.sql.DataSource",
            "java.sql.Connection");

    /** 外部 AI 调用入口（按全限定名匹配，不要求 M0 的 classpath 上存在这些类）。 */
    private static final Set<String> AI_CLIENT_TYPES = Set.of(
            "org.springframework.ai.chat.client.ChatClient",
            "org.springframework.ai.chat.model.ChatModel",
            "org.springframework.ai.embedding.EmbeddingModel");

    private static final Set<String> TRANSACTIONAL_ANNOTATIONS = Set.of(
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void webLayerMustNotAccessRepositoriesDirectly() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + "..web..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + "..repository..")
                .allowEmptyShould(true)
                .because("Controller 只能依赖 Service");
        rule.check(classes);
    }

    @Test
    void toolsMustNotExecuteRawSql() {
        ArchRule rule = noClasses().that().resideInAnyPackage(ROOT + "..tool..", ROOT + "..agent..")
                .should().dependOnClassesThat(nameIn(RAW_SQL_TYPES))
                .allowEmptyShould(true)
                .because("模型可触达的代码不得具备执行任意 SQL 的能力，只能调用固定的参数化查询仓储");
        rule.check(classes);
    }

    @Test
    void agentLayerMustNotAccessRepositoriesDirectly() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + "..agent..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + "..repository..")
                .allowEmptyShould(true)
                .because("Agent 层通过 Service / Tool 访问数据");
        rule.check(classes);
    }

    @Test
    void userModuleMustNotDependOnSecurityModule() {
        ArchRule rule = noClasses().that().resideInAPackage(ROOT + ".user..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".security..")
                .because("用户模块不感知认证机制（JWT、Redis 中的令牌）；依赖方向只能是 security → user");
        rule.check(classes);
    }

    @Test
    void topLevelModulesMustBeFreeOfCycles() {
        slices().matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .because("模块之间的循环依赖会让按功能分包失去意义（ADR-01）")
                .check(classes);
    }

    @Test
    void classesCallingAiMustNotOpenTransactions() {
        ArchRule rule = classes().that(dependOnAnyOf(AI_CLIENT_TYPES))
                .should(notUseTransactional())
                .allowEmptyShould(true)
                .because("ADR-05：外部模型调用耗时不可控，不能放在数据库事务中");
        rule.check(classes);
    }

    // ---------------------------------------------------------------------

    private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> nameIn(Set<String> names) {
        return com.tngtech.archunit.base.DescribedPredicate.describe(
                "have a name in " + names, c -> names.contains(c.getName()));
    }

    private static com.tngtech.archunit.base.DescribedPredicate<JavaClass> dependOnAnyOf(Set<String> names) {
        return com.tngtech.archunit.base.DescribedPredicate.describe(
                "depend on any of " + names,
                c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> names.contains(d.getTargetClass().getName())));
    }

    private static ArchCondition<JavaClass> notUseTransactional() {
        return new ArchCondition<>("not be annotated with or declare methods annotated with @Transactional") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean classLevel = TRANSACTIONAL_ANNOTATIONS.stream().anyMatch(javaClass::isAnnotatedWith);
                if (classLevel) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getName() + " 调用了 AI 接口却在类上声明了 @Transactional"));
                }
                javaClass.getMethods().stream()
                        .filter(m -> TRANSACTIONAL_ANNOTATIONS.stream().anyMatch(m::isAnnotatedWith))
                        .forEach(m -> events.add(SimpleConditionEvent.violated(m,
                                m.getFullName() + " 所在类调用了 AI 接口，方法不得声明 @Transactional")));
            }
        };
    }
}
