/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package io.smartdatalake.workflow.dataobject

import io.smartdatalake.definitions.Environment
import io.smartdatalake.util.misc.DataFrameUtil.DfSDL
import io.smartdatalake.workflow.SchemaViolationException
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.StructType

/**
 * A [[DataObject]] that allows for optional schema validation on read and on write.
 */
private[smartdatalake] trait SchemaValidation { this: DataObject =>
  /**
   * An optional, minimal schema that a [[DataObject]] schema must have to pass schema validation.
   *
   * The schema validation semantics are:
   * - Schema A is valid in respect to a minimal schema B when B is a subset of A. This means: the whole column set of B is contained in the column set of A.
   *  - A column of B is contained in A when A contains a column with equal name and data type.
   *  - Column order is ignored.
   *  - Column nullability is ignored.
   *  - Duplicate columns in terms of name and data type are eliminated (set semantics).
   *
   * Note: This is only used by the functionality defined in [[CanCreateDataFrame]] and [[CanWriteDataFrame]], that is,
   * when reading or writing Spark data frames from/to the underlying data container.
   * [[io.smartdatalake.workflow.action.Action]]s that bypass Spark data frames ignore the `schemaMin` attribute
   * if it is defined.
   */
  def schemaMin: Option[StructType]

  /**
   * Validate the schema of a given Spark Data Frame `df` against `schemaMin`.
   *
   * @param df The data frame to validate.
   * @throws SchemaViolationException is the `schemaMin` does not validate.
   */
  def validateSchemaMin(df: DataFrame): Unit = {
    schemaMin.foreach { structType =>
      val diff = df.schemaDiffTo(structType,
        ignoreNullable = Environment.schemaValidationIgnoresNullability,
        deep = Environment.schemaValidationDeepComarison
      )
      if (diff.nonEmpty) {
        throw new SchemaViolationException(
          s"""($id) does not have a valid schemaMin.
             |- Actual schema: ${df.schema.treeString}
             |- schemaMin: ${structType.treeString}
             |- difference in: ${StructType(diff.toSeq).treeString}""".stripMargin)
      }
    }
  }
}
