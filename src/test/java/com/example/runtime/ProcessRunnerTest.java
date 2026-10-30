package com.example.runtime;

import com.example.runtime.sandbox.DockerProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProcessRunnerTest {
    private List<String> child(String mode) throws Exception {
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        String java=Path.of(System.getProperty("java.home"),"bin",executable).toString();
        String classes=Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java,"-cp",classes,ProcessFixture.class.getName(),mode);
    }
    @Test void sendsQuotedUnicodeScriptThroughStdinWithoutChangingIt() throws Exception {
        String input="python -c \"print('中文 spaces $HOME')\"\necho 'second line'\n";
        var result=new DockerProcessRunner().run(child("echo"),Duration.ofSeconds(5),4096,input);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo(input);
        assertThat(result.timedOut()).isFalse();
    }
    @Test void drainsBothStreamsWhileBoundingCapturedOutput() throws Exception {
        var result=new DockerProcessRunner().run(child("flood"),Duration.ofSeconds(5),1024);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).hasSize(1024);
        assertThat(result.stderr()).hasSize(1024);
        assertThat(result.truncated()).isTrue();
    }
    @Test void terminatesAnOverdueProcess() throws Exception {
        var result=new DockerProcessRunner().run(child("sleep"),Duration.ofMillis(500),1024);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(124);
    }
}
