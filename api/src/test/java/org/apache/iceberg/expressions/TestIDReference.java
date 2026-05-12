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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.iceberg.Schema;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

class TestIDReference {

  // A baseline schema: field ID 5 is named "region", field ID 7 is named "credit_card".
  private static final Schema SCHEMA_V1 =
      new Schema(
          Types.NestedField.required(5, "region", Types.StringType.get()),
          Types.NestedField.optional(7, "credit_card", Types.StringType.get()));

  // Schema V2 renames field 5 from "region" to "geo_region". Field ID is unchanged.
  private static final Schema SCHEMA_V2 =
      new Schema(
          Types.NestedField.required(5, "geo_region", Types.StringType.get()),
          Types.NestedField.optional(7, "credit_card", Types.StringType.get()));

  /**
   * Test 1 — Happy path: binding resolves by field ID.
   *
   * <p>Verifies that an IDReference with field ID 5 and name "region" binds correctly against
   * SCHEMA_V1 and produces a BoundReference whose type and field ID match.
   *
   * <p>Why: baseline correctness. If this fails, nothing else matters.
   */
  @Test
  void testBindByFieldId() {
    IDReference<String> ref = new IDReference<>(5, "region");

    BoundReference<String> bound = ref.bind(SCHEMA_V1.asStruct(), true);

    assertThat(bound.fieldId()).isEqualTo(5);
    assertThat(bound.type()).isEqualTo(Types.StringType.get());
    // The bound name comes from the live schema, which is "region" in V1.
    assertThat(bound.name()).isEqualTo("region");
  }

  /**
   * Test 2 — The core proof: IDReference survives a column rename.
   *
   * <p>Serializes an IDReference for field ID 5 (named "region" in V1). Then binds it against
   * SCHEMA_V2 where field ID 5 has been renamed to "geo_region". The reference must still resolve
   * successfully — and the bound name must reflect the new name from the live schema.
   *
   * <p>Why: this is the entire reason IDReference exists. A NamedReference("region") would fail
   * against SCHEMA_V2. An IDReference(5) must not.
   */
  @Test
  void testBindSurvivesColumnRename() {
    // Simulate: the catalog serialized this reference when the field was named "region".
    IDReference<String> ref = new IDReference<>(5, "region");

    // Now the schema has evolved: field ID 5 is renamed to "geo_region".
    // Binding must succeed — the reference resolves by ID, not by name.
    BoundReference<String> bound = ref.bind(SCHEMA_V2.asStruct(), true);

    assertThat(bound.fieldId()).isEqualTo(5);
    // Critically: the bound name reflects the *current* schema name, not the stored name.
    // This ensures that plan display and error messages use the accurate current name.
    assertThat(bound.name()).isEqualTo("geo_region");
    assertThat(bound.type()).isEqualTo(Types.StringType.get());
  }

  /**
   * Test 3 — Unknown field ID throws ValidationException.
   *
   * <p>Binding an IDReference with a field ID that does not exist in the schema must throw
   * ValidationException with a message that includes the missing field ID.
   *
   * <p>Why: bad metadata must fail loudly, not silently produce wrong results. A missing field ID
   * could mean the column was dropped — the restriction should not silently stop applying.
   */
  @Test
  void testBindUnknownFieldIdThrows() {
    IDReference<String> ref = new IDReference<>(99, "nonexistent");

    assertThatThrownBy(() -> ref.bind(SCHEMA_V1.asStruct(), true))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("99");
  }

  /**
   * Test 4 — fieldId() accessor returns the construction-time value.
   *
   * <p>Why: basic correctness for serialization — the parser writes this value to JSON and must
   * read back the same value.
   */
  @Test
  void testFieldIdAccessor() {
    IDReference<String> ref = new IDReference<>(7, "credit_card");
    assertThat(ref.fieldId()).isEqualTo(7);
  }

  /**
   * Test 5 — The name stored in IDReference is purely decorative and does not affect binding.
   *
   * <p>Two IDReferences with the same field ID but different names must bind to the same field.
   *
   * <p>Why: documents and enforces the intentional design. If name affected binding, a stale name
   * after a rename would cause the wrong field to be resolved — which is exactly the bug
   * IDReference exists to prevent.
   */
  @Test
  void testNameIsHumanReadableOnly() {
    IDReference<String> refWithCorrectName = new IDReference<>(5, "region");
    IDReference<String> refWithStaleName = new IDReference<>(5, "old_region_name_from_2019");

    BoundReference<String> bound1 = refWithCorrectName.bind(SCHEMA_V1.asStruct(), true);
    BoundReference<String> bound2 = refWithStaleName.bind(SCHEMA_V1.asStruct(), true);

    // Both resolve to the same field — the stored name is irrelevant to binding.
    assertThat(bound1.fieldId()).isEqualTo(bound2.fieldId());
    assertThat(bound1.name()).isEqualTo(bound2.name());
    assertThat(bound1.type()).isEqualTo(bound2.type());
  }

  /**
   * Test 6 — toString() includes the field ID and the stored name.
   *
   * <p>Why: toString() is used in error messages and query plan display. Regressions in its output
   * can make debugging significantly harder.
   */
  @Test
  void testToString() {
    IDReference<String> refWithName = new IDReference<>(5, "region");
    assertThat(refWithName.toString()).contains("5").contains("region");

    IDReference<String> refWithoutName = new IDReference<>(5, null);
    assertThat(refWithoutName.toString()).contains("5");
  }
}
