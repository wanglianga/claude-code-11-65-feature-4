package com.agri.pit.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public final class CatalogDtos {

    public record CreateFeatureRequest(
            @NotBlank String code,
            @NotBlank String name,
            String description,
            String unit,
            @NotEmpty List<String> sourceTypes) {
    }

    public record CreateVersionRequest(
            @NotBlank String formula,
            Boolean required,
            @PositiveOrZero Long availabilityLagSeconds,
            String asOfPolicy,
            @PositiveOrZero Integer lookbackDays,
            String notes) {
    }

    public record ApproveVersionRequest(String approvedBy, String notes) {
    }

    public record FeatureSummary(
            String code,
            String name,
            String description,
            String unit,
            List<String> sourceTypes,
            String status,
            Integer currentVersion,
            String approvedAt) {
    }

    public record VersionView(
            long id,
            String featureCode,
            int versionNo,
            String formula,
            boolean required,
            long availabilityLagSeconds,
            String asOfPolicy,
            int lookbackDays,
            String status,
            String approvedBy,
            String approvedAt,
            String createdAt,
            String checksum,
            String notes,
            Long supersedesVersionId) {
    }

    public record FeatureDetail(FeatureSummary feature, List<VersionView> versions) {
    }
}
