// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "properties.h"
#include <vespa/eval/eval/value_type.h>
#include <vespa/vespalib/util/exception.h>
#include <memory>
#include <string>

namespace vespalib::eval { struct Value; }

namespace search::fef {

class IIndexEnvironment;
class IObjectStore;
class IQueryEnvironment;

/**
 * Exception for when the value type is an error.
 */
class InvalidValueTypeException : public vespalib::Exception {
private:
    std::string _type_str;

public:
    InvalidValueTypeException(const std::string& query_key, const std::string& type_str_in);
    const std::string& type_str() const { return _type_str; }
};

/**
 * Exception for when a tensor value could not be created from an expression.
 */
class InvalidTensorValueException : public vespalib::Exception {
private:
    std::string _expr;

public:
    InvalidTensorValueException(const vespalib::eval::ValueType& type, const std::string& expr_in);
    const std::string& expr() const { return _expr; }
};

/**
 * Class representing a vespalib::eval::Value (number or tensor) passed down with the query.
 *
 * The value type and optional default value are defined in IIndexEnvironment properties and extracted at config time.
 * Per query, the value is extracted from IQueryEnvironment properties. This is stored in the shared IObjectStore.
 */
class QueryValue {
private:
    std::string _key; // 'foo'
    std::string _name; // 'query(foo)'
    std::string _old_key; // '$foo'
    std::string _stored_value_key; // query.value.foo
    vespalib::eval::ValueType _type;

    Property config_lookup(const IIndexEnvironment& env) const;
    Property request_lookup(const IQueryEnvironment& env) const;

public:
    QueryValue();
    QueryValue(const std::string& key, vespalib::eval::ValueType type);
    ~QueryValue();

    /**
     * Create a QueryValue using properties from the given index environment to extract the value type.
     *
     * Throws InvalidValueTypeException if the value type is an error.
     */
    static QueryValue from_config(const std::string& key, const IIndexEnvironment& env);

    const vespalib::eval::ValueType& type() const { return _type; }

    /**
     * Create a default value based on properties from the given index environment.
     *
     * An empty value is created if not found.
     * Throws InvalidTensorValueException if a tensor value could not be created.
     */
    std::unique_ptr<vespalib::eval::Value> make_default_value(const IIndexEnvironment& env) const;

    void prepare_shared_state(const fef::IQueryEnvironment& env, fef::IObjectStore& store) const;

    const vespalib::eval::Value* lookup_value(const fef::IObjectStore& store) const;

    double lookup_number(const fef::IQueryEnvironment& env, double default_value) const;

};

}

