package com.vortex.common.dto;

/** Describes whether reranking ran and whether its effect is observable. */
public enum RerankEffectStatus {
    NOT_EXECUTED,
    NON_IDENTIFIABLE,
    IDENTIFIABLE,
    ORDER_CHANGED
}
