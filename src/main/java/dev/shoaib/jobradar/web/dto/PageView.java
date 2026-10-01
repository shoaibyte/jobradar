package dev.shoaib.jobradar.web.dto;

import java.util.List;

/** A page of results; {@code page} is 0-based. */
public record PageView<T>(List<T> items, int page, int size, long total, int totalPages) {}
