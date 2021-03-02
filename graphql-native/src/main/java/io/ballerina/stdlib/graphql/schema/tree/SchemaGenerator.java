/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.graphql.schema.tree;

import io.ballerina.runtime.api.TypeTags;
import io.ballerina.runtime.api.types.Field;
import io.ballerina.runtime.api.types.RecordType;
import io.ballerina.runtime.api.types.ServiceType;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.stdlib.graphql.schema.Schema;
import io.ballerina.stdlib.graphql.schema.SchemaField;
import io.ballerina.stdlib.graphql.schema.SchemaType;
import io.ballerina.stdlib.graphql.schema.TypeKind;
import io.ballerina.stdlib.graphql.schema.tree.nodes.TypeNode;
import io.ballerina.stdlib.graphql.utils.Utils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static io.ballerina.stdlib.graphql.engine.EngineUtils.BOOLEAN;
import static io.ballerina.stdlib.graphql.engine.EngineUtils.DECIMAL;
import static io.ballerina.stdlib.graphql.engine.EngineUtils.FLOAT;
import static io.ballerina.stdlib.graphql.engine.EngineUtils.INTEGER;
import static io.ballerina.stdlib.graphql.engine.EngineUtils.STRING;
import static io.ballerina.stdlib.graphql.utils.Utils.createError;

/**
 * Generates a GraphQL schema from a Ballerina service.
 *
 * @since 0.2.0
 */
public class SchemaGenerator {
    private ServiceType serviceType;
    private Map<String, SchemaType> typeMap;

    public SchemaGenerator(ServiceType serviceType) {
        this.serviceType = serviceType;
        this.typeMap = new HashMap<>();
    }

    public Schema generate() {
        TypeNode typeNode = this.findBasicTypes();
        return this.generateSchema(typeNode);
    }

    public Schema generateSchema(TypeNode typeNode) {
        Schema schema = new Schema();
        SchemaTreeGenerator schemaTreeGenerator = new SchemaTreeGenerator(this.serviceType, this.typeMap);
        SchemaType queryType = schemaTreeGenerator.getQueryType();
        schema.setQueryType(queryType);
        for (SchemaType schemaType : this.typeMap.values()) {
            schema.addType(schemaType);
        }
        return schema;
    }

    private TypeNode findBasicTypes() {
        TypeTreeGenerator typeTreeGenerator = new TypeTreeGenerator(this.serviceType);
        TypeNode typeNode = typeTreeGenerator.generateTypeTree();
        populateTypesMap(typeNode);
        return typeNode;
    }

    private void addType(SchemaType schemaType) {
        this.typeMap.put(schemaType.getName(), schemaType);
    }

    private SchemaType populateTypesMap(TypeNode typeNode) {
        if (typeNode.getChildren() == null || typeNode.getChildren().size() == 0) {
            Type type = typeNode.getType();
            if (type == null) {
                // This code shouldn't be reached
                String message = "Type not found for the resource: " + typeNode.getName();
                throw createError(message, Utils.ErrorCode.InvalidTypeError);
            } else {
                return getSchemaTypeFromType(type);
            }
        } else {
            for (TypeNode childTypeNode : typeNode.getChildren().values()) {
                populateTypesMap(childTypeNode);
            }
            Type type = typeNode.getType();
            if (type == null) {
                SchemaType schemaType = getSchemaTypeForHierarchicalResource(typeNode);
                this.addType(schemaType);
                return schemaType;
            } else {
                return getSchemaTypeFromType(type);
            }
        }
    }

    private SchemaType getSchemaTypeFromType(Type type) {
        int tag = type.getTag();
        SchemaType schemaType;

        if (tag == TypeTags.INT_TAG || tag == TypeTags.STRING_TAG || tag == TypeTags.DECIMAL_TAG ||
                tag == TypeTags.BOOLEAN_TAG || tag == TypeTags.FLOAT_TAG) {
            String name = getScalarTypeName(tag);
            if (this.typeMap.containsKey(name)) {
                schemaType = this.typeMap.get(name);
            } else {
                schemaType = new SchemaType(name, TypeKind.SCALAR);
            }
        } else if (tag == TypeTags.RECORD_TYPE_TAG) {
            RecordType recordType = (RecordType) type;
            String name = recordType.getName();
            if (this.typeMap.containsKey(name)) {
                schemaType = this.typeMap.get(name);
            } else {
                schemaType = new SchemaType(name, TypeKind.OBJECT);
                Collection<Field> fields = recordType.getFields().values();
                for (Field field : fields) {
                    schemaType.addField(getSchemaFieldFromRecordField(field));
                }
            }
        } else if (tag == TypeTags.MAP_TAG) {
            schemaType = new SchemaType(type.getName(), TypeKind.OBJECT);
        } else if (tag == TypeTags.ARRAY_TAG) {
            schemaType = new SchemaType(type.getName(), TypeKind.LIST);
        } else if (tag == TypeTags.UNION_TAG) {
            schemaType = new SchemaType(type.getName(), TypeKind.OBJECT);
        } else {
            String message = "Unsupported type for schema field: " + type.getName();
            throw createError(message, Utils.ErrorCode.NotSupportedError);
        }

        this.addType(schemaType);
        return schemaType;
    }

    private SchemaField getSchemaFieldFromRecordField(Field field) {
        SchemaField schemaField = new SchemaField(field.getFieldName());
        schemaField.setType(getSchemaTypeFromType(field.getFieldType()));
        return schemaField;
    }

    private SchemaType getSchemaTypeForHierarchicalResource(TypeNode typeNode) {
        SchemaType schemaType = new SchemaType(typeNode.getName(), TypeKind.OBJECT);
        for (TypeNode childTypeNode : typeNode.getChildren().values()) {
            SchemaField childField = new SchemaField(childTypeNode.getName());
            childField.setType(populateTypesMap(childTypeNode));
            schemaType.addField(childField);
        }
        return schemaType;
    }

    private static String getScalarTypeName(int tag) {
        if (tag == TypeTags.INT_TAG) {
            return INTEGER;
        } else if (tag == TypeTags.DECIMAL_TAG) {
            return DECIMAL;
        } else if (tag == TypeTags.FLOAT_TAG) {
            return FLOAT;
        } else if (tag == TypeTags.BOOLEAN_TAG) {
            return BOOLEAN;
        } else {
            return STRING;
        }
    }
}