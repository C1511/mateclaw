package vip.mate.goal;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vip.mate.MateClawApplication;
import vip.mate.exception.MateClawException;
import vip.mate.goal.model.GoalStatus;
import vip.mate.goal.service.GoalJsonAcceptanceService;
import vip.mate.goal.service.GoalJsonBindingService;
import vip.mate.goal.service.GoalService;
import vip.mate.goal.service.ManagedGoalJsonService;
import vip.mate.memory.spi.MemoryManager;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** File-backed database, actual migrations and independently closed application contexts. */
class GoalJsonRestartIntegrationTest {
    @TempDir Path directory;

    @Test void legacyUpgradeAndManagedBindingsSurviveApplicationRestart() {
        String url = "jdbc:h2:file:" + directory.resolve("goals")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration/h2")
                .placeholderReplacement(false).target("193").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbc.update("""
                INSERT INTO mate_user(id,username,password,enabled,role,create_time,update_time,deleted)
                VALUES (88001,'restart-owner','unused',TRUE,'user',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                """);
        for (long id : List.of(88002L, 88003L)) {
            jdbc.update("""
                    INSERT INTO mate_conversation(id,conversation_id,username,workspace_id,agent_id,create_time,update_time,deleted)
                    VALUES (?,?,'restart-owner',1,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)
                    """, id, "restart-" + id);
            jdbc.update("""
                    INSERT INTO mate_agent_goal(id,conversation_id,agent_id,workspace_id,created_by,title,description,create_time,update_time)
                    VALUES (?,?,1,1,'restart-owner','Legacy report','Migration fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """, id, "restart-" + id);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name='mate_agent_goal' AND column_name='json_acceptance_required'", Integer.class));
        ManagedGoalJsonService.Artifact saved;
        try (var first = start(url)) {
            var goals = first.getBean(GoalService.class);
            var requirements = first.getBean(GoalJsonAcceptanceService.class);
            var artifacts = first.getBean(ManagedGoalJsonService.class);
            var bindings = first.getBean(GoalJsonBindingService.class);
            assertFalse(goals.getById(88002L).isJsonAcceptanceRequired());
            assertFalse(requirements.get(88002L, "restart-owner").required());
            assertEquals(GoalStatus.COMPLETED, goals.markCompleted(88003L, null).getStatus());
            requirements.configure(88002L, "r", new GoalJsonAcceptanceService.ConfigureRequest(0L, "report", List.of("summary")), "restart-owner");
            saved = artifacts.publish(88002L, "report", new ManagedGoalJsonService.PublishRequest(0L, "{\"summary\":false,\"unicode\":\"报告\"}"), "restart-owner");
            assertTrue(bindings.check(88002L, "r", new GoalJsonBindingService.CheckRequest(1L, saved.artifactId(), 1L), "restart-owner").acceptanceEligible());
        }
        try (var second = start(url)) {
            var goals = second.getBean(GoalService.class);
            var requirements = second.getBean(GoalJsonAcceptanceService.class);
            var artifacts = second.getBean(ManagedGoalJsonService.class);
            var bindings = second.getBean(GoalJsonBindingService.class);
            assertTrue(goals.getById(88002L).isJsonAcceptanceRequired());
            assertEquals(List.of("summary"), requirements.get(88002L, "restart-owner").requirements().getFirst().requiredFields());
            var loaded = artifacts.read(88002L, saved.artifactId(), "restart-owner");
            assertEquals(saved, loaded.artifact());
            assertEquals("{\"summary\":false,\"unicode\":\"报告\"}", loaded.jsonContent());
            assertTrue(bindings.state(88002L, "restart-owner").getFirst().acceptanceEligible());
            requirements.configure(88002L, "r", new GoalJsonAcceptanceService.ConfigureRequest(1L, "report", List.of("summary", "sources")), "restart-owner");
        }
        try (var third = start(url)) {
            var goals = third.getBean(GoalService.class);
            var artifacts = third.getBean(ManagedGoalJsonService.class);
            var bindings = third.getBean(GoalJsonBindingService.class);
            assertEquals("REQUIREMENT_CHANGED", bindings.state(88002L, "restart-owner").getFirst().status());
            assertThrows(MateClawException.class, () -> goals.markCompleted(88002L, null));
            var current = artifacts.publish(88002L, "report", new ManagedGoalJsonService.PublishRequest(1L, "{\"summary\":false,\"sources\":[]}"), "restart-owner");
            assertEquals(2, current.generation());
            assertTrue(bindings.check(88002L, "r", new GoalJsonBindingService.CheckRequest(2L, current.artifactId(), 2L), "restart-owner").acceptanceEligible());
            assertEquals(GoalStatus.COMPLETED, goals.markCompleted(88002L, null).getStatus());
        }
        try (var fourth = start(url)) {
            var goals = fourth.getBean(GoalService.class);
            assertEquals(GoalStatus.COMPLETED, goals.getById(88002L).getStatus());
            assertEquals(1, goals.listEvents(88002L, 20).stream().filter(e -> "completed".equals(e.getEventType())).count());
            assertThrows(MateClawException.class, () -> fourth.getBean(ManagedGoalJsonService.class)
                    .publish(88002L, "report", new ManagedGoalJsonService.PublishRequest(2L, "{}"), "restart-owner"));
        }
    }

    private ConfigurableApplicationContext start(String url) {
        var application = new SpringApplication(MateClawApplication.class, MemoryFixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run("--spring.datasource.url=" + url,
                "--spring.ai.dashscope.api-key=restart-fixture-no-provider",
                "--mateclaw.goal.enabled=false", "--mateclaw.plugin.enabled=false",
                "--mateclaw.skill.workspace.auto-init=false",
                "--mateclaw.skill.workspace.root=" + directory.resolve("skills"));
    }

    @TestConfiguration
    static class MemoryFixture {
        @Bean @Primary MemoryManager restartMemory() { return org.mockito.Mockito.mock(MemoryManager.class); }
    }
}
