package com.calisthenics.diagnostic.viewer

/** Bounded on-screen trace only; null readings break the line instead of bridging missing motion. */
data class AngleTracePoint(val capturedNs: Long, val leftDegrees: Double?, val rightDegrees: Double?)
