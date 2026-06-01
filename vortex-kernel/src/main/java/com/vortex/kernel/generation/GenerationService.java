package com.vortex.kernel.generation;

import com.vortex.common.dto.GenerationRequest;
import com.vortex.common.dto.GenerationResult;

public interface GenerationService {

    GenerationResult generate(GenerationRequest request);
}
