package com.vortex.app.eval;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMemoryEvalCliApplicationTest {

    @Test
    void shouldRemainUtilityStyleMainEntry() throws Exception {
        Constructor<LlmMemoryEvalCliApplication> constructor =
                LlmMemoryEvalCliApplication.class.getDeclaredConstructor();

        assertThat(Modifier.isFinal(LlmMemoryEvalCliApplication.class.getModifiers())).isTrue();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
    }
}
