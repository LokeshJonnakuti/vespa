// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.parser;

import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.DeployLogger;
import com.yahoo.config.application.api.FileRegistry;
import com.yahoo.config.model.api.ModelContext;
import com.yahoo.config.model.application.provider.BaseDeployLogger;
import com.yahoo.config.model.application.provider.MockFileRegistry;
import com.yahoo.config.model.deploy.TestProperties;
import com.yahoo.config.model.test.MockApplicationPackage;
import com.yahoo.document.DataType;
import com.yahoo.document.DataTypeName;
import com.yahoo.document.DocumentType;
import com.yahoo.document.DocumentTypeManager;
import com.yahoo.document.PositionDataType;
import com.yahoo.document.ReferenceDataType;
import com.yahoo.document.StructDataType;
import com.yahoo.document.WeightedSetDataType;
import com.yahoo.document.annotation.AnnotationReferenceDataType;
import com.yahoo.document.annotation.AnnotationType;
import com.yahoo.searchdefinition.DefaultRankProfile;
import com.yahoo.searchdefinition.DocumentOnlySchema;
import com.yahoo.searchdefinition.RankProfileRegistry;
import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.UnrankedRankProfile;
import com.yahoo.searchdefinition.document.SDDocumentType;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.searchdefinition.document.TemporaryImportedField;
import com.yahoo.searchdefinition.document.annotation.SDAnnotationType;
import com.yahoo.searchdefinition.parser.ConvertParsedTypes.TypeResolver;
import com.yahoo.vespa.documentmodel.DocumentSummary;
import com.yahoo.vespa.documentmodel.SummaryField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Class converting a collection of schemas from the intermediate format.
 *
 * @author arnej27959
 **/
public class ConvertSchemaCollection {

    private final IntermediateCollection input;
    private final List<ParsedSchema> orderedInput = new ArrayList<>();
    private final DocumentTypeManager docMan;
    private final ApplicationPackage applicationPackage;
    private final FileRegistry fileRegistry;
    private final DeployLogger deployLogger;
    private final ModelContext.Properties properties;
    private final RankProfileRegistry rankProfileRegistry;
    private final boolean documentsOnly;

    // for unit test
    ConvertSchemaCollection(IntermediateCollection input,
                            DocumentTypeManager documentTypeManager)
    {
        this(input, documentTypeManager,
             MockApplicationPackage.createEmpty(),
             new MockFileRegistry(),
             new BaseDeployLogger(),
             new TestProperties(),
             new RankProfileRegistry(),
             true);
    }

    public ConvertSchemaCollection(IntermediateCollection input,
                                   DocumentTypeManager documentTypeManager,
                                   ApplicationPackage applicationPackage,
                                   FileRegistry fileRegistry,
                                   DeployLogger deployLogger,
                                   ModelContext.Properties properties,
                                   RankProfileRegistry rankProfileRegistry,
                                   boolean documentsOnly)
    {
        this.input = input;
        this.docMan = documentTypeManager;
        this.applicationPackage = applicationPackage;
        this.fileRegistry = fileRegistry;
        this.deployLogger = deployLogger;
        this.properties = properties;
        this.rankProfileRegistry = rankProfileRegistry;
        this.documentsOnly = documentsOnly;

        input.resolveInternalConnections();
        order();
        pushTypesToDocuments();
    }

    void order() {
        var map = input.getParsedSchemas();
        for (var schema : map.values()) {
            findOrdering(schema);
        }
    }

    void findOrdering(ParsedSchema schema) {
        if (orderedInput.contains(schema)) return;
        for (var parent : schema.getAllResolvedInherits()) {
            findOrdering(parent);
        }
        orderedInput.add(schema);
    }

    void pushTypesToDocuments() {
        for (var schema : orderedInput) {
            for (var struct : schema.getStructs()) {
                schema.getDocument().addStruct(struct);
            }
            for (var annotation : schema.getAnnotations()) {
                schema.getDocument().addAnnotation(annotation);
            }
        }
    }

    private ConvertParsedTypes typeConverter;

    public void convertTypes() {
        typeConverter = new ConvertParsedTypes(orderedInput, docMan);
        typeConverter.convert(true);
    }

    public List<Schema> convertToSchemas() {
        var converter = new ConvertParsedSchemas(orderedInput,
                                                 docMan,
                                                 applicationPackage,
                                                 fileRegistry,
                                                 deployLogger,
                                                 properties,
                                                 rankProfileRegistry,
                                                 documentsOnly);
        return converter.convertToSchemas();
    }

}
