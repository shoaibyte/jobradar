package dev.shoaib.jobradar.core;

import java.util.List;

public record MatchOutcome(MatchStrength strength, double score, List<String> reasons) {}
