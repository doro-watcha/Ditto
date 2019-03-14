/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.thingsearch.persistence.read;

import java.util.List;
import java.util.concurrent.CompletionStage;

import org.eclipse.ditto.model.query.Query;
import org.eclipse.ditto.services.models.thingsearch.SearchNamespaceReportResult;

import akka.NotUsed;
import akka.stream.javadsl.Source;

import org.eclipse.ditto.services.thingsearch.common.model.ResultList;

/**
 * Interface for thing operations on the persistence used within the search service.
 */
public interface ThingsSearchPersistence {

    /**
     * Initializes the search index if necessary.
     *
     * @return a {@link CompletionStage} which can be either used for blocking or non-blocking initialization.
     */
    CompletionStage<Void> initializeIndices();

    /**
     * Generate a report of things per Namespace.
     *
     * @return Source that emits the report.
     */
    Source<SearchNamespaceReportResult, NotUsed> generateNamespaceCountReport();

    /**
     * Returns the count of documents found by the given {@code query}.
     *
     * @param query the query for matching.
     * @param authorizationSubjectIds authorization subject IDs.
     * @return an {@link Source} which emits the count.
     * @throws NullPointerException if {@code query} is {@code null}.
     */
    Source<Long, NotUsed> count(Query query, List<String> authorizationSubjectIds);

    /**
     * Returns the count of documents found by the given {@code query} regardless of visibility.
     *
     * @param query the query for matching.
     * @return an {@link Source} which emits the count.
     * @throws NullPointerException if {@code query} is {@code null}.
     */
    Source<Long, NotUsed> sudoCount(Query query);

    /**
     * Returns the IDs for all found documents.
     *
     * @param query the query for matching.
     * @param authorizationSubjectIds authorization subject IDs.
     * @return an {@link Source} which emits the IDs.
     * @throws NullPointerException if {@code query} is {@code null}.
     */
    Source<ResultList<String>, NotUsed> findAll(Query query, List<String> authorizationSubjectIds);

}
