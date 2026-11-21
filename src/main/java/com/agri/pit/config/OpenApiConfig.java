package com.agri.pit.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pointInTimeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agriculture drought claim point-in-time feature service")
                        .version("1.0.0")
                        .description("""
                                Feature catalog, point-in-time warehouse, backfill orchestrator, leakage detector,
                                scoring snapshots, and revision impact analysis for drought claim verification.

                                **Two clocks.** Every warehouse record stores `observedAt` (when the phenomenon happened)
                                and `ingestedAt` (when the platform could actually see it). Features are always selected
                                with `ingestedAt <= availabilityCeiling`, never by observation time alone.

                                **Three guarantees.**
                                1. A scene acquired before the hazard but ingested after the scoring cutoff is excluded
                                   from the original run and recorded as a leakage exclusion.
                                2. When two parcel boundary versions cover the same ground, each raster cell belongs to
                                   exactly one version; the case's locked version wins and a boundary-variant run sees the
                                   shared pixels excluded instead of double counting them.
                                3. A weather revision that arrives after settlement only creates a pending review
                                   candidate. The frozen snapshot and the settled payout are never rewritten.

                                **Backfill lab.** New model features (effective GDD after sowing, pre-hazard NDVI
                                recovery slope) are backfilled over three years of historical cases under strict
                                evidence rules: a dependency graph registers what each feature needs; an availability
                                scanner separates EVALUABLE cases from POST_HOC_ONLY ones (a planting declaration
                                ingested after the case cutoff is look-ahead risk, never adopted); a versioned policy
                                library defines cloud-occlusion / late-declaration / missing-weather handling; the
                                scheduler shards backfills by definition version; and immutable experiment snapshots
                                store inputs, outputs and missing reasons. A policy change always starts a new
                                experiment — reported metrics are never overwritten.
                                """)
                        .contact(new Contact().name("Claims platform"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/").description("Local instance")));
    }
}
