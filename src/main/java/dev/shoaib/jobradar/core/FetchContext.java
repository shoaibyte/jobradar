package dev.shoaib.jobradar.core;

import java.time.Clock;

public record FetchContext(HttpFetcher http, CompanyRegistry registry, Clock clock) {}
