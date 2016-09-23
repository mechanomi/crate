/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import io.crate.exceptions.TableAlreadyExistsException;
import io.crate.metadata.*;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class CreateTableAnalyzedStatement extends AbstractDDLAnalyzedStatement {

    protected final FulltextAnalyzerResolver fulltextAnalyzerResolver;
    private AnalyzedTableElements analyzedTableElements;
    private Map<String, Object> mapping;
    private ColumnIdent routingColumn;
    private TableIdent tableIdent;
    private boolean noOp = false;
    private boolean ifNotExists = false;

    public CreateTableAnalyzedStatement(FulltextAnalyzerResolver fulltextAnalyzerResolver) {
        this.fulltextAnalyzerResolver = fulltextAnalyzerResolver;
    }

    public void table(TableIdent tableIdent, boolean ifNotExists, Schemas schemas) {
        tableIdent.validate();
        if (ifNotExists) {
            noOp = schemas.tableExists(tableIdent);
        } else if (schemas.tableExists(tableIdent)) {
            throw new TableAlreadyExistsException(tableIdent);
        }
        this.ifNotExists = ifNotExists;
        this.tableIdent = tableIdent;
    }

    public boolean noOp() {
        return noOp;
    }

    public boolean ifNotExists() {
        return ifNotExists;
    }

    @Override
    public <C, R> R accept(AnalyzedStatementVisitor<C, R> analyzedStatementVisitor, C context) {
        return analyzedStatementVisitor.visitCreateTableStatement(this, context);
    }

    public List<List<String>> partitionedBy() {
        return analyzedTableElements().partitionedBy();
    }

    public boolean isPartitioned() {
        return !analyzedTableElements().partitionedByColumns.isEmpty();
    }

    /**
     * name of the template to create
     *
     * @return the name of the template to create or <code>null</code>
     * if no template is created
     */
    public
    @Nullable
    String templateName() {
        if (isPartitioned()) {
            return PartitionName.templateName(tableIdent().schema(), tableIdent().name());
        }
        return null;
    }

    /**
     * template prefix to match against index names to which
     * this template should be applied
     *
     * @return a template prefix for matching index names or null
     * if no template is created
     */
    public
    @Nullable
    String templatePrefix() {
        if (isPartitioned()) {
            return templateName() + "*";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> mappingProperties() {
        return (Map) mapping().get("properties");
    }

    public Collection<String> primaryKeys() {
        return analyzedTableElements.primaryKeys();
    }

    public Collection<String> notNullColumns() {
        return analyzedTableElements.notNullColumns();
    }

    public Map<String, Object> mapping() {
        if (mapping == null) {
            mapping = analyzedTableElements.toMapping();
            if (routingColumn != null) {
                ((Map) mapping.get("_meta")).put("routing", routingColumn.fqn());
            }
            // merge in user defined mapping parameter
            mapping.putAll(tableParameter.mappings());
        }
        return mapping;
    }

    public FulltextAnalyzerResolver fulltextAnalyzerResolver() {
        return fulltextAnalyzerResolver;
    }

    public TableIdent tableIdent() {
        return tableIdent;
    }

    public void routing(ColumnIdent routingColumn) {
        if (routingColumn.name().equalsIgnoreCase("_id")) {
            return;
        }
        this.routingColumn = routingColumn;
    }

    public
    @Nullable
    ColumnIdent routing() {
        return routingColumn;
    }

    /**
     * return true if a columnDefinition with name <code>columnName</code> exists
     */
    public boolean hasColumnDefinition(ColumnIdent columnIdent) {
        return (analyzedTableElements().columnIdents().contains(columnIdent) ||
                columnIdent.name().equalsIgnoreCase("_id"));
    }

    public void analyzedTableElements(AnalyzedTableElements analyze) {
        this.analyzedTableElements = analyze;
    }

    public AnalyzedTableElements analyzedTableElements() {
        return analyzedTableElements;
    }

}
