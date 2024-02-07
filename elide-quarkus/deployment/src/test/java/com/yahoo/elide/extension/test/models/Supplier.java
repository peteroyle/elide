/*
 * Copyright 2021, Yahoo Inc.
 * Licensed under the Apache License, Version 2.0
 * See LICENSE file in project root for terms.
 */
package com.yahoo.elide.extension.test.models;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.Include;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Include
@Entity
@CreatePermission(expression = "Deny")
public class Supplier {

    @Id
    private long id;

    private String name;

}
