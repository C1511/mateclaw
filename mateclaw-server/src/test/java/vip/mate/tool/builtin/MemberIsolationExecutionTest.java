package vip.mate.tool.builtin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.AgentWorkspaceResolver;
import vip.mate.i18n.I18nService;
import vip.mate.skill.runtime.*;
import vip.mate.skill.secret.SkillSecretService;
import vip.mate.tool.sandbox.MemberSandboxExecutor;
import vip.mate.workspace.core.service.MemberFileIsolation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemberIsolationExecutionTest {
 @TempDir Path root;
 @BeforeEach void enabled(){ MemberFileIsolation.configure(o->o.withWorkspace(7L,root.toString())); }
 @AfterEach void disabled(){ MemberFileIsolation.configure(null); }
 @Test void shellUsesContainerAndDoesNotFallBackWhenDockerFails() throws Exception {
   var runner=mock(MemberSandboxExecutor.class);
   when(runner.execute(anyList(),eq(root),anyMap(),anyInt())).thenThrow(new java.io.IOException("docker unavailable"));
   var tool=new ShellExecuteTool(mock(I18nService.class),null);
   ReflectionTestUtils.setField(tool,"memberSandboxExecutor",runner);
   String result=tool.execute_shell_command("touch host-escape",30,ChatOrigin.web("c","alice",7L,null,null,11L).toToolContext());
   assertTrue(result.contains("docker unavailable"));
   assertFalse(java.nio.file.Files.exists(root.resolve("host-escape")));
   verify(runner).execute(anyList(),eq(root),anyMap(),eq(30));
 }
 @Test void codeUsesContainerAndNeverInvokesHostSkillExecution() throws Exception {
   var runner=mock(MemberSandboxExecutor.class);
   when(runner.executeCode(anyString(),anyString(),eq(root),any(),anyMap(),anyInt()))
       .thenReturn(new MemberSandboxExecutor.Result(0,"isolated","",false));
   var host=mock(SkillScriptExecutionService.class);
   var tool=new CodeExecuteTool(mock(SkillRuntimeService.class),mock(AgentWorkspaceResolver.class),host,
       mock(SkillSecretService.class),new ObjectMapper(),null);
   ReflectionTestUtils.setField(tool,"memberSandboxExecutor",runner);
   var context=ChatOrigin.web("c","alice",7L,null,null,11L).toToolContext();
   String result=tool.execute_code("python","print('isolated')",null,null,30,context);
   assertTrue(result.contains("isolated"));
   assertTrue(tool.execute_code("python","print('x')","shared-skill",null,30,context).contains("unavailable"));
   verifyNoInteractions(host);
 }
}
