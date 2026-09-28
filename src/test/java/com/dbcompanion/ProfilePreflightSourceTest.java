package com.dbcompanion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class ProfilePreflightSourceTest {
 @Test void preflightIsReadOnlyAndRetainsUnknownAccess() throws Exception {
  String source=Files.readString(Path.of("src/main/java/com/dbcompanion/service/ProfilePreflightService.java"));
  assertThat(source).contains("read.setReadOnly(true)","Tone.GREY","does not prove the credential is absent").doesNotContain("jdbc.update","CREATE ","DROP ","ai.generate");
 }
}
