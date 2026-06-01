package com.vortex.app.eval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "vortex.eval", name = "run-on-startup", havingValue = "true")
public class LlmMemoryEvalStartupRunner implements ApplicationRunner {

    private final LlmMemoryEvalExecutionService executionService;
    private final LlmMemoryEvalProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        try {
            executionService.executeConfiguredRun();
        } catch (RuntimeException e) {
            if (properties.isFailOnStartupError()) {
                throw e;
            }
            log.error("LLM memory eval startup run failed: {}", e.getMessage(), e);
        }
    }
}
