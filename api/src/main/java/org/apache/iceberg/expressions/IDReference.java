/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.expressions;

import org.apache.iceberg.Schema;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.types.Types;

/**
 * A field reference that identifies a column by its immutable field ID, with an optional
 * human-readable name for debugging purposes only.
 *
 * <p>Unlike {@link NamedReference}, which resolves by column name and breaks when a column is
 * renamed, this reference survives schema evolution because field IDs in Iceberg are assigned once
 * and never reused or changed.
 *
 * <p>This class is intentionally not part of the {@link UnboundTerm} hierarchy. It is a
 * purpose-built serde type for expressions stored in table metadata (such as row access policies
 * and column masks) that must remain stable across schema evolution. It can be bound to a schema
 * via {@link #bind}, producing a standard {@link BoundReference} that participates normally in the
 * expression evaluation pipeline.
 *
 * @param <T> the Java type of values produced by this reference
 */
class IDReference<T> implements UnboundTerm<T> {

  private final int fieldId;
  private final String name; // optional, for human readability only

  /**
   * Creates a new IDReference for the given field ID.
   *
   * @param fieldId the immutable field ID assigned in the schema; must be positive
   * @param name a human-readable name for debugging; does not affect binding; may be null
   */
  IDReference(int fieldId, String name) {
    this.fieldId = fieldId;
    this.name = name;
  }

  /** Returns the field ID this reference resolves to. */
  public int fieldId() {
    return fieldId;
  }

  /**
   * Returns the stored human-readable name. This is purely informational — binding always uses the
   * field ID. May be null if no name was provided at construction time.
   */
  public String name() {
    return name;
  }

  /**
   * Returns a {@link NamedReference} for this field, using the stored name.
   *
   * <p>This method exists solely to satisfy the {@link Unbound} interface contract. The returned
   * reference uses the stored name, which may be stale after a column rename. Callers should use
   * {@link #bind} for correct field resolution — never use {@code ref().name()} as a binding key.
   */
  @Override
  public NamedReference<T> ref() {
    return new NamedReference<>(name != null ? name : "field-id=" + fieldId);
  }

  /**
   * Binds this reference to the given struct type by looking up the field by ID.
   *
   * <p>If the field ID is not found in the struct, this method throws {@link
   * org.apache.iceberg.exceptions.ValidationException}.
   *
   * <p>The {@code caseSensitive} parameter is ignored — binding is always by field ID.
   *
   * @param struct the struct type representing the current schema; must have been produced by a
   *     {@link org.apache.iceberg.Schema} so that {@code asSchema()} and {@code accessorForField}
   *     are available
   * @param caseSensitive ignored
   * @return a bound reference for the resolved field, using the field's current name from the live
   *     schema
   */
  @Override
  public BoundReference<T> bind(Types.StructType struct, boolean caseSensitive) {
    Schema schema = struct.asSchema();
    Types.NestedField field = schema.findField(fieldId);
    ValidationException.check(
        field != null, "Cannot find field with ID %d in struct: %s", fieldId, schema.asStruct());
    return new BoundReference<>(field, schema.accessorForField(field.fieldId()), field.name());
  }

  @Override
  public String toString() {
    return "ref(id=" + fieldId + (name != null ? ", name=" + name : "") + ")";
  }
}
