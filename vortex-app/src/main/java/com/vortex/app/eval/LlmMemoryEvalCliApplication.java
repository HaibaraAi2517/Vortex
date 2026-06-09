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
    private static final String LEARNING_COMMAND = "learning";
    private static final String PROFILE_TYPE_STRICT_REPORT = "strict-report";
    private static final String PROFILE_TYPE_AUDIT_ONLY = "audit-only";

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
        if (isLearningCommand(args)) {
            if (isLearningVerifyCommand(args)) {
                return executeLearningVerify(args);
            }
            return executeLearningRun(args);
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

    private static int executeLearningRun(String[] args) {
        ConfigurableApplicationContext context = null;
        int exitCode = 1;
        try {
            context = new SpringApplicationBuilder(VortexApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "vortex.eval.run-on-startup=false",
                            "spring.main.banner-mode=off")
                    .run(trimCommand(args));
            LearningMemoryEvalExecutionService executionService =
                    context.getBean(LearningMemoryEvalExecutionService.class);
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
            log.error("Learning memory eval CLI run failed: {}", e.getMessage(), e);
            exitCode = 1;
        }
        return exitCode;
    }

    private static int executeLearningVerify(String[] args) {
        try {
            LearningVerifyCommand request = parseLearningVerifyCommand(args);
            LearningMemoryEvalProperties properties = new LearningMemoryEvalProperties();
            properties.setProfileId(request.profileId());
            LearningMemoryEvalVerifier verifier = new LearningMemoryEvalVerifier(
                    JsonMapperFactory.create(),
                    properties);
            LearningMemoryEvalVerificationResult result = verifier.verify(request.reportPath(), request.profileId());
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
            log.error("Learning memory eval verification failed: {}", e.getMessage(), e);
            return 1;
        }
    }

    private static int executeVerify(String[] args) {
        try {
            VerifyCommand request = parseVerifyCommand(args);
            if (request.listProfiles()) {
                printProfileList();
                return 0;
            }
            if (request.describeProfile()) {
                printProfileDescription(request.profile());
                return 0;
            }
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

    private static boolean isLearningCommand(String[] args) {
        return args != null
                && args.length > 0
                && LEARNING_COMMAND.equalsIgnoreCase(args[0]);
    }

    private static boolean isLearningVerifyCommand(String[] args) {
        return args != null
                && args.length > 1
                && "verify".equalsIgnoreCase(args[1]);
    }

    private static String[] trimCommand(String[] args) {
        if (args == null || args.length <= 1) {
            return new String[0];
        }
        String[] trimmed = new String[args.length - 1];
        System.arraycopy(args, 1, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private static VerifyCommand parseVerifyCommand(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException(verifyUsage());
        }
        String profileId = LlmMemoryEvalBaselineProfile.OFFICIAL_V2_STRICT.id();
        String reportPath = null;
        boolean listProfiles = false;
        boolean describeProfile = false;
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
            if ("--list-profiles".equals(current)) {
                listProfiles = true;
                index++;
                continue;
            }
            if ("--describe".equals(current)) {
                describeProfile = true;
                index++;
                continue;
            }
            if (reportPath != null) {
                throw new IllegalArgumentException(verifyUsage());
            }
            reportPath = current;
            index++;
        }
        LlmMemoryEvalBaselineProfile profile = LlmMemoryEvalBaselineProfile.require(profileId);
        if (listProfiles) {
            if (describeProfile || reportPath != null) {
                throw new IllegalArgumentException(verifyUsage());
            }
            return new VerifyCommand(null, profile, true, false);
        }
        if (describeProfile) {
            if (reportPath != null) {
                throw new IllegalArgumentException(verifyUsage());
            }
            return new VerifyCommand(null, profile, false, true);
        }
        if (reportPath == null) {
            throw new IllegalArgumentException(verifyUsage());
        }
        if (!profile.strictReportProfile()) {
            throw new IllegalArgumentException(
                    "Baseline profile '" + profile.id() + "' is audit-only and cannot verify a single report.");
        }
        return new VerifyCommand(Path.of(reportPath).toAbsolutePath().normalize(), profile, false, false);
    }

    private static LearningVerifyCommand parseLearningVerifyCommand(String[] args) {
        if (args == null || args.length < 2 || !isLearningVerifyCommand(args)) {
            throw new IllegalArgumentException(learningVerifyUsage());
        }
        String profileId = new LearningMemoryEvalProperties().getProfileId();
        String reportPath = null;
        int index = 2;
        while (index < args.length) {
            String current = args[index];
            if ("--profile".equals(current)) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException(learningVerifyUsage());
                }
                profileId = args[index + 1];
                index += 2;
                continue;
            }
            if (reportPath != null) {
                throw new IllegalArgumentException(learningVerifyUsage());
            }
            reportPath = current;
            index++;
        }
        if (reportPath == null) {
            throw new IllegalArgumentException(learningVerifyUsage());
        }
        return new LearningVerifyCommand(Path.of(reportPath).toAbsolutePath().normalize(), profileId);
    }

    private static void printProfileList() {
        System.out.println("LLM memory eval baseline profiles:");
        for (LlmMemoryEvalBaselineProfile profile : LlmMemoryEvalBaselineProfile.allProfiles()) {
            System.out.println("- " + profile.id()
                    + " [" + renderProfileType(profile) + "]"
                    + " datasetVersion=" + profile.datasetVersion()
                    + " baselineId=" + profile.baselineId());
        }
    }

    private static void printProfileDescription(LlmMemoryEvalBaselineProfile profile) {
        System.out.println("Profile: " + profile.id());
        System.out.println("Type: " + renderProfileType(profile));
        System.out.println("Baseline ID: " + profile.baselineId());
        System.out.println("Dataset version: " + profile.datasetVersion());
        System.out.println("Dataset location: " + profile.datasetLocation());
        System.out.println("Description: " + profile.description());
        if (profile.modeExpectations().isEmpty()) {
            System.out.println("Strict verify expectations: none");
            return;
        }
        System.out.println("Strict verify expectations:");
        for (LlmMemoryEvalBaselineProfile.ModeExpectation expectation : profile.modeExpectations()) {
            System.out.println("- " + expectation.modeName()
                    + " correct=" + expectation.expectedCorrect() + "/" + expectation.expectedTotal()
                    + optionalMetric(" accuracy", expectation.expectedAccuracy())
                    + optionalMetric(" recoveredAccuracy", expectation.expectedRecoveredAccuracy())
                    + optionalMetric(" recoveredL2HitRate", expectation.expectedRecoveredL2HitRate()));
        }
    }

    private static String renderProfileType(LlmMemoryEvalBaselineProfile profile) {
        return profile.strictReportProfile() ? PROFILE_TYPE_STRICT_REPORT : PROFILE_TYPE_AUDIT_ONLY;
    }

    private static String optionalMetric(String label, Double value) {
        return value == null ? "" : label + "=" + value;
    }

    private static String verifyUsage() {
        return "Usage: java -jar vortex-app-<version>-eval-cli.jar verify "
                + "[--profile <baseline-profile-id>] <path-to-llm-memory-eval-report.json>"
                + System.lineSeparator()
                + "       java -jar vortex-app-<version>-eval-cli.jar verify --list-profiles"
                + System.lineSeparator()
                + "       java -jar vortex-app-<version>-eval-cli.jar verify "
                + "[--profile <baseline-profile-id>] --describe";
    }

    private static String learningVerifyUsage() {
        return "Usage: java -jar vortex-app-<version>-eval-cli.jar learning verify "
                + "[--profile <learning-profile-id>] <path-to-learning-memory-eval-report.json>";
    }

    private record VerifyCommand(
            Path reportPath,
            LlmMemoryEvalBaselineProfile profile,
            boolean listProfiles,
            boolean describeProfile) {
    }

    private record LearningVerifyCommand(Path reportPath, String profileId) {
    }
}
