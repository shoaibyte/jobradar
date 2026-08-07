package dev.shoaib.jobradar.web.dto;

import dev.shoaib.jobradar.core.CompanyPriority;
import dev.shoaib.jobradar.core.RelocationPolicy;

/** Response shape for {@code GET /api/companies}: a companies.yml entry plus its live active-job count. */
public record CompanyView(
    String name, String ats, RelocationPolicy relocation, CompanyPriority priority, long activeCount) {}
