package com.persimmon.ddd.tenancy;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code persimmon.ddd.tenancy.*}; nothing here does anything until {@code enabled=true}. */
@ConfigurationProperties("persimmon.ddd.tenancy")
public record TenancyProperties(
    @DefaultValue("false") boolean enabled,
    /* Header the default resolver reads. */
    @DefaultValue("X-Tenant-Id") String header,
    /* Only a gateway that strips the header from clients makes it trustworthy. */
    @DefaultValue("false") boolean trustHeader,
    /* reject (400) or root: what a request without a tenant gets. */
    @DefaultValue("reject") MissingTenant missingTenant,
    @DefaultValue("/actuator/**") List<String> excludePaths,
    /* Tables the SQL interceptor scopes by tenant_id; every other table with that column must be listed in exempt-tables. */
    @DefaultValue List<String> tables,
    @DefaultValue List<String> exemptTables,
    @DefaultValue("tenant_id") String column) {
  public enum MissingTenant {
    REJECT,
    ROOT
  }
}
