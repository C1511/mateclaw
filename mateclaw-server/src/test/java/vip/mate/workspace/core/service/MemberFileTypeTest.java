package vip.mate.workspace.core.service;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.channels.SeekableByteChannel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class MemberFileTypeTest {
 @Test void specialFilesAreRejectedBeforeOpen() throws Exception {
  SecureDirectoryStream<Path> directory=mock(SecureDirectoryStream.class);
  var view=mock(BasicFileAttributeView.class); var attributes=mock(BasicFileAttributes.class);
  when(directory.getFileAttributeView(Path.of("pipe"),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)).thenReturn(view);
  when(view.readAttributes()).thenReturn(attributes);
  assertThrows(java.io.IOException.class,()->MemberFileAccess.readFile(directory,Path.of("pipe"),100));
  verify(directory,never()).newByteChannel(any(),any());
 }
 @Test void leafReplacementWithFifoCannotBlockARead() throws Exception {
  SecureDirectoryStream<Path> directory=mock(SecureDirectoryStream.class);
  var view=mock(BasicFileAttributeView.class); var attributes=mock(BasicFileAttributes.class);
  when(directory.getFileAttributeView(Path.of("file"),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)).thenReturn(view);
  when(view.readAttributes()).thenReturn(attributes); when(attributes.isRegularFile()).thenReturn(true);
  var channel=mock(SeekableByteChannel.class);
  when(directory.newByteChannel(eq(Path.of("file")),any())).thenAnswer(invocation->{
    java.util.Set<?> options=invocation.getArgument(1);
    assertTrue(options.contains(StandardOpenOption.READ)); assertTrue(options.contains(StandardOpenOption.WRITE));
    return channel;
  });
  when(channel.size()).thenReturn(0L);
  assertArrayEquals(new byte[0],MemberFileAccess.readFile(directory,Path.of("file"),100));
  verify(channel,never()).read(any());
 }
}
