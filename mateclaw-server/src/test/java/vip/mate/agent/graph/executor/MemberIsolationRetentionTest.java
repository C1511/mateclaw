package vip.mate.agent.graph.executor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.workspace.core.service.MemberFileIsolation;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class MemberIsolationRetentionTest {
 @TempDir Path root;
 @AfterEach void reset(){ MemberFileIsolation.configure(null); }
 @Test void legacyPurgeCannotFollowMemberSymlinkToPeerFiles() throws Exception {
  Path peer=Files.createDirectories(root.resolve("peer"));
  Path secret=Files.writeString(peer.resolve("secret.txt"),"keep");
  Path storageRoot=Files.createDirectories(root.resolve("spills"));
  Files.createSymbolicLink(storageRoot.resolve("alice-conversation"),peer);
  ToolResultProperties props=new ToolResultProperties(); props.setStorageBaseDir(storageRoot.toString());
  MemberFileIsolation.configure(o->o);
  assertEquals(0,new ToolResultStorage(props).purgeConversation("alice-conversation"));
  assertEquals("keep",Files.readString(secret));
 }
}
