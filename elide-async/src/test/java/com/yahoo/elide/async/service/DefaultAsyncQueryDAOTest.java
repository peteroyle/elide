/*
 * Copyright 2020, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.async.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yahoo.elide.Elide;
import com.yahoo.elide.ElideSettings;
import com.yahoo.elide.ElideSettingsBuilder;
import com.yahoo.elide.async.models.AsyncQuery;
import com.yahoo.elide.async.models.AsyncQueryResult;
import com.yahoo.elide.async.models.QueryStatus;
import com.yahoo.elide.core.DataStore;
import com.yahoo.elide.core.DataStoreTransaction;
import com.yahoo.elide.core.EntityDictionary;
import com.yahoo.elide.core.RequestScope;
import com.yahoo.elide.core.filter.dialect.RSQLFilterDialect;
import com.yahoo.elide.security.checks.Check;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

public class DefaultAsyncQueryDAOTest {

    private DefaultAsyncQueryDAO asyncQueryDAO;
    private Elide elide;
    private DataStore dataStore;
    private AsyncQuery asyncQuery;
    private DataStoreTransaction tx;
    private EntityDictionary dictionary;

    @BeforeEach
    public void setupMocks() {
        dataStore = mock(DataStore.class);
        asyncQuery = mock(AsyncQuery.class);
        tx = mock(DataStoreTransaction.class);

        Map<String, Class<? extends Check>> checkMappings = new HashMap<>();

        dictionary = new EntityDictionary(checkMappings);
        dictionary.bindEntity(AsyncQuery.class);
        dictionary.bindEntity(AsyncQueryResult.class);

        ElideSettings elideSettings = new ElideSettingsBuilder(dataStore)
                .withEntityDictionary(dictionary)
                .withJoinFilterDialect(new RSQLFilterDialect(dictionary))
                .withSubqueryFilterDialect(new RSQLFilterDialect(dictionary))
                .withISO8601Dates("yyyy-MM-dd'T'HH:mm'Z'", TimeZone.getTimeZone("UTC"))
                .build();

        elide = new Elide(elideSettings);

        when(dataStore.beginTransaction()).thenReturn(tx);

        asyncQueryDAO = new DefaultAsyncQueryDAO(elide, dataStore);
    }

    @Test
    public void testAsyncQueryCleanerThreadSet() {
        assertEquals(elide, asyncQueryDAO.getElide());
        assertEquals(dictionary, asyncQueryDAO.getDictionary());
    }

    @Test
    public void testUpdateStatus() {
        AsyncQuery result = asyncQueryDAO.updateStatus(asyncQuery, QueryStatus.PROCESSING);

        assertEquals(result, asyncQuery);
        verify(tx, times(1)).save(any(AsyncQuery.class), any(RequestScope.class));
        verify(asyncQuery, times(1)).setStatus(QueryStatus.PROCESSING);
    }

   @Test
   public void testUpdateStatusAsyncQueryCollection() {
       Iterable<Object> loaded = Arrays.asList(asyncQuery, asyncQuery);
       when(tx.loadObjects(any(), any())).thenReturn(loaded);

       asyncQueryDAO.updateStatusAsyncQueryCollection("status=in=(PROCESSING,QUEUED);createdOn=le='2020-04-22T13:28Z'", QueryStatus.TIMEDOUT);

       verify(tx, times(2)).save(any(AsyncQuery.class), any(RequestScope.class));
       verify(asyncQuery, times(2)).setStatus(QueryStatus.TIMEDOUT);
   }

    @Test
    public void testDeleteAsyncQueryAndResultCollection() {
        Iterable<Object> loaded = Arrays.asList(asyncQuery, asyncQuery, asyncQuery);
        when(tx.loadObjects(any(), any())).thenReturn(loaded);

        asyncQueryDAO.deleteAsyncQueryAndResultCollection("createdOn=le='2020-03-23T02:02Z'");

        verify(dataStore, times(1)).beginTransaction();
        verify(tx, times(1)).loadObjects(any(), any());
        verify(tx, times(3)).delete(any(AsyncQuery.class), any(RequestScope.class));
    }

    @Test
    public void testCreateAsyncQueryResult() {
        Integer status = 200;
        String responseBody = "responseBody";
        UUID uuid = UUID.fromString("ba31ca4e-ed8f-4be0-a0f3-12088fa9263e");
        AsyncQueryResult result = asyncQueryDAO.createAsyncQueryResult(status, "responseBody", asyncQuery, uuid);

        assertEquals(status, result.getStatus());
        assertEquals(responseBody, result.getResponseBody());
        assertEquals(asyncQuery, result.getQuery());
        assertEquals(uuid, result.getId());
        verify(tx, times(1)).createObject(any(), any(RequestScope.class));
        verify(tx, times(1)).save(any(), any(RequestScope.class));

    }
}