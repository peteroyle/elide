/*
 * Copyright 2019, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.spring.config;

import com.yahoo.elide.Elide;
import com.yahoo.elide.ElideSettingsBuilder;
import com.yahoo.elide.Injector;
import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.audit.Slf4jLogger;
import com.yahoo.elide.contrib.dynamicconfighelpers.compile.ElideDynamicEntityCompiler;
import com.yahoo.elide.contrib.swagger.SwaggerBuilder;
import com.yahoo.elide.core.DataStore;
import com.yahoo.elide.core.EntityDictionary;
import com.yahoo.elide.core.filter.dialect.RSQLFilterDialect;
import com.yahoo.elide.datastores.aggregation.AggregationDataStore;
import com.yahoo.elide.datastores.aggregation.QueryEngine;
import com.yahoo.elide.datastores.aggregation.metadata.MetaDataStore;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.SQLQueryEngine;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.annotation.FromSubquery;
import com.yahoo.elide.datastores.aggregation.queryengines.sql.annotation.FromTable;
import com.yahoo.elide.datastores.jpa.JpaDataStore;
import com.yahoo.elide.datastores.jpa.transaction.NonJtaTransaction;
import com.yahoo.elide.datastores.multiplex.MultiplexManager;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.models.Info;
import io.swagger.models.Swagger;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Set;
import java.util.TimeZone;
import javax.persistence.EntityManagerFactory;

/**
 * Auto Configuration For Elide Services.  Override any of the beans (by defining your own) to change
 * the default behavior.
 */
@Configuration
@EnableConfigurationProperties(ElideConfigProperties.class)
@Slf4j
public class ElideAutoConfiguration {

    /**
     * Creates a entity compiler for compiling dynamic config classes.
     * @param settings Config Settings.
     * @return An instance of ElideDynamicEntityCompiler.
     * @throws Exception Exception thrown.
     */
    @Bean
    @ConditionalOnMissingBean
    public ElideDynamicEntityCompiler buildElideDynamicEntityCompiler(ElideConfigProperties settings) throws Exception {

        ElideDynamicEntityCompiler compiler = null;

        if (isDynamicConfigEnabled(settings)) {
            compiler = new ElideDynamicEntityCompiler(settings.getDynamicConfig().getPath());
        }
        return compiler;
    }

    /**
     * Creates the Elide instance with standard settings.
     * @param dictionary Stores the static metadata about Elide models.
     * @param dataStore The persistence store.
     * @param settings Elide settings.
     * @return A new elide instance.
     */
    @Bean
    @ConditionalOnMissingBean
    public Elide initializeElide(EntityDictionary dictionary,
            DataStore dataStore, ElideConfigProperties settings) {

        ElideSettingsBuilder builder = new ElideSettingsBuilder(dataStore)
                .withEntityDictionary(dictionary)
                .withDefaultMaxPageSize(settings.getMaxPageSize())
                .withDefaultPageSize(settings.getPageSize())
                .withJoinFilterDialect(new RSQLFilterDialect(dictionary))
                .withSubqueryFilterDialect(new RSQLFilterDialect(dictionary))
                .withAuditLogger(new Slf4jLogger())
                .withISO8601Dates("yyyy-MM-dd'T'HH:mm'Z'", TimeZone.getTimeZone("UTC"));

        return new Elide(builder.build());
    }

    /**
     * Creates the entity dictionary for Elide which contains static metadata about Elide models.
     * Override to load check classes or life cycle hooks.
     * @param beanFactory Injector to inject Elide models.
     * @param dynamicCompiler An instance of objectprovider for ElideDynamicEntityCompiler.
     * @param settings Elide configuration settings.
     * @return a newly configured EntityDictionary.
     * @throws ClassNotFoundException Exception thrown.
     */
    @Bean
    @ConditionalOnMissingBean
    public EntityDictionary buildDictionary(AutowireCapableBeanFactory beanFactory,
            ObjectProvider<ElideDynamicEntityCompiler> dynamicCompiler, ElideConfigProperties settings)
            throws ClassNotFoundException {
        EntityDictionary dictionary = new EntityDictionary(new HashMap<>(),
                new Injector() {
                    @Override
                    public void inject(Object entity) {
                        beanFactory.autowireBean(entity);
                    }

                    @Override
                    public <T> T instantiate(Class<T> cls) {
                        return beanFactory.createBean(cls);
                    }
                });

        dictionary.scanForSecurityChecks();

        if (isDynamicConfigEnabled(settings)) {
            ElideDynamicEntityCompiler compiler = dynamicCompiler.getIfAvailable();
            Set<Class<?>> annotatedClass = compiler.findAnnotatedClasses(SecurityCheck.class);
            dictionary.addSecurityChecks(annotatedClass);
        }

        return dictionary;
    }

    /**
     * Create a QueryEngine instance for aggregation data store to use.
     * @param entityManagerFactory The JPA factory which creates entity managers.
     * @param dynamicCompiler An instance of objectprovider for ElideDynamicEntityCompiler.
     * @param settings Elide configuration settings.
     * @return An instance of a QueryEngine
     * @throws ClassNotFoundException Exception thrown.
     */
    @Bean
    @ConditionalOnMissingBean
    public QueryEngine buildQueryEngine(EntityManagerFactory entityManagerFactory,
            ObjectProvider<ElideDynamicEntityCompiler> dynamicCompiler, ElideConfigProperties settings)
            throws ClassNotFoundException {

        MetaDataStore metaDataStore = null;

        if (isDynamicConfigEnabled(settings)) {
            metaDataStore = new MetaDataStore(dynamicCompiler.getIfAvailable());
        } else {
            metaDataStore = new MetaDataStore();
        }

        return new SQLQueryEngine(metaDataStore, entityManagerFactory);
    }

    /**
     * Creates the DataStore Elide.  Override to use a different store.
     * @param entityManagerFactory The JPA factory which creates entity managers.
     * @param queryEngine QueryEngine instance for aggregation data store.
     * @param dynamicCompiler An instance of objectprovider for ElideDynamicEntityCompiler.
     * @param settings Elide configuration settings.
     * @return An instance of a JPA DataStore.
     * @throws ClassNotFoundException Exception thrown.
     */
    @Bean
    @ConditionalOnMissingBean
    public DataStore buildDataStore(EntityManagerFactory entityManagerFactory, QueryEngine queryEngine,
            ObjectProvider<ElideDynamicEntityCompiler> dynamicCompiler, ElideConfigProperties settings)
            throws ClassNotFoundException {
        AggregationDataStore.AggregationDataStoreBuilder aggregationDataStoreBuilder = AggregationDataStore.builder()
                .queryEngine(queryEngine);
        if (isDynamicConfigEnabled(settings)) {
            ElideDynamicEntityCompiler compiler = dynamicCompiler.getIfAvailable();
            Set<Class<?>> annotatedClass = compiler.findAnnotatedClasses(FromTable.class);
            annotatedClass.addAll(compiler.findAnnotatedClasses(FromSubquery.class));
            aggregationDataStoreBuilder.dynamicCompiledClasses(annotatedClass);
        }
        AggregationDataStore aggregationDataStore = aggregationDataStoreBuilder.build();

        JpaDataStore jpaDataStore = new JpaDataStore(
                () -> { return entityManagerFactory.createEntityManager(); },
                    (em -> { return new NonJtaTransaction(em); }));

        // meta data store needs to be put at first to populate meta data models
        return new MultiplexManager(jpaDataStore, queryEngine.getMetaDataStore(), aggregationDataStore);
    }

    /**
     * Creates a singular swagger document for JSON-API.
     * @param dictionary Contains the static metadata about Elide models.
     * @param settings Elide configuration settings.
     * @return An instance of a JPA DataStore.
     */
    @Bean
    @ConditionalOnMissingBean
    public Swagger buildSwagger(EntityDictionary dictionary, ElideConfigProperties settings) {
        Info info = new Info()
                .title(settings.getSwagger().getName())
                .version(settings.getSwagger().getVersion());

        SwaggerBuilder builder = new SwaggerBuilder(dictionary, info).withLegacyFilterDialect(false);

        Swagger swagger = builder.build().basePath(settings.getJsonApi().getPath());

        return swagger;
    }

    private boolean isDynamicConfigEnabled(ElideConfigProperties settings) {

        boolean enabled = false;
        if (settings.getDynamicConfig() != null) {
            enabled = settings.getDynamicConfig().isEnabled();
        }

        return enabled;

    }
}
