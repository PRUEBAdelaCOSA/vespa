// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/vsm/common/storagedocument.h>

namespace document { class DocumentTypeRepo; }

namespace vsm
{

class DocumentTypeMapping
{
public:
    DocumentTypeMapping();
    ~DocumentTypeMapping();

    /**
     * Prepares the given document by sharing the field info map
     * registered for that document type.
     **/
    bool prepareBaseDoc(SharedFieldPathMap & doc) const;

    /**
     * Builds a field info map for all registered document types.
     **/
    void init(const std::string & defaultDocumentType,
              const StringFieldIdTMapT & fieldList,
              const document::DocumentTypeRepo &repo);

    const document::DocumentType & getCurrentDocumentType() const;
    const std::string & getDefaultDocumentTypeName() const
    { return _defaultDocumentTypeName; }
    const document::DocumentType *getDefaultDocumentType() const
    { return _defaultDocumentType; }

private:
    /**
     * Builds a field info map for the given type id. This is a
     * mapping from field id to field path and field value for all
     * field names in the given list based on the given document type.
     **/
    void buildFieldMap(const document::DocumentType *docType,
                       const StringFieldIdTMapT & fieldList,
                       const std::string & typeId);
    using FieldPathMapMapT = vespalib::hash_map<std::string, FieldPathMapT>;
    using DocumentTypeUsage = std::multimap<size_t, const document::DocumentType *>;
    FieldPathMapMapT        _fieldMap;
    std::string        _defaultDocumentTypeName;
    const document::DocumentType *_defaultDocumentType;
    DocumentTypeUsage       _documentTypeFreq;
};

}

