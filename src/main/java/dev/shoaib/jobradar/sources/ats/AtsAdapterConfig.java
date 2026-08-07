package dev.shoaib.jobradar.sources.ats;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Triggers {@link AtsAdapterRegistrar} to register one adapter bean per watched ATS company. */
@Configuration
@Import(AtsAdapterRegistrar.class)
public class AtsAdapterConfig {
}
