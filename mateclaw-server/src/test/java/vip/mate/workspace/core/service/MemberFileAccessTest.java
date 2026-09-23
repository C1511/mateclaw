package vip.mate.workspace.core.service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class MemberFileAccessTest {
 @TempDir Path directory;
 @Test void readsAndWritesOwnedFiles() throws Exception {
   Path root = Files.createDirectory(directory.resolve("alice"));
   try (var stream = Files.newDirectoryStream(root)) {
     if (!(stream instanceof SecureDirectoryStream)) {
       assertThrows(java.io.IOException.class, () -> MemberFileAccess.write(root,root.resolve("report"),new byte[0],false));
       return;
     }
   }
   Path file = root.resolve("nested/report.txt");
   MemberFileAccess.write(root,file,"hello".getBytes(),false);
   MemberFileAccess.write(root,file," world".getBytes(),true);
   assertEquals("hello world",new String(MemberFileAccess.read(root,file,100)));
 }
 @Test void refusesSymlinksAtAnyLevelAndBoundsReads() throws Exception {
   Path root=Files.createDirectory(directory.resolve("alice"));
   Path other=Files.createDirectory(directory.resolve("bob"));
   Files.writeString(other.resolve("secret"),"private");
   Files.createSymbolicLink(root.resolve("peer"),other);
   Files.createSymbolicLink(root.resolve("link"),other.resolve("secret"));
   assertThrows(Exception.class,()->MemberFileAccess.read(root,root.resolve("peer/secret"),100));
   assertThrows(Exception.class,()->MemberFileAccess.write(root,root.resolve("link"),"changed".getBytes(),false));
   assertEquals("private",Files.readString(other.resolve("secret")));
   Path large=Files.writeString(root.resolve("large"),"0123456789");
   assertThrows(Exception.class,()->MemberFileAccess.read(root,large,5));
 }
}
