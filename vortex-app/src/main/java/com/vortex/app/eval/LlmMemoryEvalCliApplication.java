package com.vortex.app.eval;

import com.vortex.app.VortexApplication;
import com.vortex.common.serialization.JsonMapperFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

@Slf4j
public final class LlmMemoryEvalCliApplication {

    private static final String VERIFY_COMMAND = "verify";

    private LlmMemoryEvalCliApplication() {
    }

    public static void main(String[] args) {
        int exitCode = execute(args);
        System.exit(exitCode);
    }

    static int execute(String[] args) {
        if (isVerifyCommand(args)) {
            return executeVerify(args);
        }
        return executeEvalRun(args);
    }

    private static int executeEvalRun(String[] args) {
        ConfigurableApplicationContext context = null;
        int exitCode = 1;
        try {
            context = new SpringApplicationBuilder(VortexApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "vortex.eval.run-on-startup=false",
                        "spring.main.banner-mode=off")
                .run(args);
            LlmMemoryEvalExecutionService executionService = context.getBean(LlmMemoryEvalExecutionService.class);
            executionService.executeConfiguredRun();
            exitCode = SpringApplication.exit(context, () -> 0);
        } catch (RuntimeException e) {
            if (context != null) {
                try {
                    SpringApplication.exit(context, () -> 1);
                } catch (RuntimeException closeError) {
                    log.warn("Failed to close CLI application context cleanly: {}", closeError.getMessage());
                }
            }
            log.error("LLM memory eval CLI run failed: {}", e.getMessage(), e);
            exitCode = 1;
        }
        return exitCode;
    }

    private static int executeVerify(String[] args) {
        try {
            VerifyRequest request = parseVerifyRequest(args);
            LlmMemoryEvalBaselineVerifier verifier = new LlmMemoryEvalBaselineVerifier(JsonMapperFactory.create());
            LlmMemoryEvalBaselineVerificationResult result = verifier.verify(request.reportPath(), request.profile());
            if (result.isPassed()) {
                System.out.println(result.renderHumanReadable());
                return 0;
            }
            System.err.println(result.renderHumanReadable());
            return 2;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            log.error("LLM memory eval baseline verification failed: {}", e.getMessage(), e);
            return 1;
        }
    }

    private static boolean isVerifyCommand(String[] args) {
        return args != null
                && args.length > 0
                && VERIFY_COMMAND.equalsIgnoreCase(args[0]);
    }

    private static VerifyRequest parseVerifyRequest(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException(verifyUsage());
        }
        String profileId = LlmMemoryEvalBaselineProfile.OFFICIAL_V2_STRICT.id();
        String reportPath = null;
        int index = 1;
        while (index < args.length) {
            String current = args[index];
            if ("--profile".equals(current)) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException(verifyUsage());
                }
                profileId = args[index + 1];
                index += 2;
                continue;
            }
            if (reportPath != null) {
                throw new IllegalArgumentException(verifyUsage());
            }
            reportPath = current;
            index++;
        }
        if (reportPath == null) {
            throw new IllegalArgumentException(verifyUsage());
        }
        LlmMemoryEvalBaselineProfile profile = LlmMemoryEvalBaselineProfile.require(profileId);
        if (!profile.strictReportProfile()) {
            throw new IllegalArgumentException(
                    "Baseline profile '" + profile.id() + "' is audit-only and cannot verify a single report.");
        }
        return new VerifyRequest(Path.of(reportPath).toAbsolutePath().normalize(), profile);
    }

    private static String verifyUsage() {
        return "Usage: java -jar vortex-app-<version>-eval-cli.jar verify "
                + "[--profile <baseline-profile-id>] <path-to-llm-memory-eval-report.json>";
    }

    private record VerifyRequest(Path reportPath, LlmMemoryEvalBaselineProfile profile) {
    }
}
