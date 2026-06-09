package com.vortex.app.eval;

import com.vortex.common.serialization.JsonMapperFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LearningMemoryEvalCliApplicationTest {

    @Test
    void learningVerifyCommandShouldAcceptPassingReport(
            @org.junit.jupiter.api.io.TempDir Path tempDir,
            CapturedOutput output) throws Exception {
        Path reportPath = tempDir.resolve("learning-memory-eval.json");
        JsonMapperFactory.create()
                .writerWithDefaultPrettyPrinter()
                .writeValue(reportPath.toFile(), LearningMemoryEvalVerifierTest.passingReport());

        int exitCode = LlmMemoryEvalCliApplication.execute(new String[] {
                "learning",
                "verify",
                reportPath.toString()
        });

        assertThat(exitCode).isZero();
        assertThat(output.getOut()).contains("PASS: learning report");
    }
}
