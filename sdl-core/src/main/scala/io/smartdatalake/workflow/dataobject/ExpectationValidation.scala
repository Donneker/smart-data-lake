/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2022 ELCA Informatique SA (<https://www.elca.ch>)
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

import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.misc.SmartDataLakeLogger
import io.smartdatalake.util.spark.{DefaultExpressionData, SparkExpressionUtil}
import io.smartdatalake.workflow.dataframe._
import io.smartdatalake.workflow.dataframe.spark.SparkColumn
import io.smartdatalake.workflow.{ActionPipelineContext, DataFrameSubFeed, ExecutionPhase}

import java.util.UUID

/**
 * A trait that allows for optional constraint validation and expectation evaluation on write when implemented by a [[DataObject]].
 */
private[smartdatalake] trait ExpectationValidation { this: DataObject with SmartDataLakeLogger =>

  /**
   * List of constraint definitions to validate on write, see [[Constraint]] for details.
   * Constraints are expressions defined on row-level and validated during evaluation of the DataFrame.
   * If validation fails an exception is thrown and further processing is stopped.
   * Note that this is done while evaluating the DataFrame when writing to the DataObject. It doesn't need a separate action on the DataFrame.
   * If a constraint validation for a row fails, it will throw an exception and abort writing to the DataObject.
   */
  // TODO: can we avoid Spark retries when validation exceptions are thrown in Spark tasks?
  def constraints: Seq[Constraint]

  /**
   * Map of expectation name and definition to evaluate on write, see [[Expectation]] for details.
   * Expectations are aggregation expressions defined on dataset-level and evaluated on every write.
   * By default their result is logged with level info (ok) and error (failed), but this can be customized to be logged as warning.
   * In case of failed expectations logged as error, an exceptions is thrown and further processing is stopped.
   * Note that the exception is thrown after writing to the DataObject is finished.
   *
   * The following expectations names are reserved to create default metrics and should not be used:
   * - count
   */
  def expectations: Seq[Expectation]

  def setupConstraintsAndJobExpectations(df: GenericDataFrame)(implicit context: ActionPipelineContext): (GenericDataFrame, DataFrameObservation) = {
    // add constraint validation column
    val dfConstraints = setupConstraintsValidation(df)
    // setup job expectations as DataFrame observation
    val jobExpectations = expectations.filter(_.scope == ExpectationScope.Job)
    val (dfJobExpectations, observation) = {
      implicit val functions: DataFrameFunctions = DataFrameSubFeed.getFunctions(df.subFeedType)
      val expectationColumns = (defaultExpectations ++ jobExpectations).flatMap(_.getAggExpressionColumns(this.id))
      setupObservation(dfConstraints, expectationColumns, context.isExecPhase)
    }
    // setup add caching if there are expectations with scope != job
    if (expectations.exists(_.scope != ExpectationScope.Job)) (dfJobExpectations.cache, observation)
    else (dfJobExpectations, observation)
  }

  private val defaultExpectations = Seq(SQLExpectation(name = "count", aggExpression = "count(*)" ))

  /**
   * Collect metrics for expectations with scope = JobPartition
   */
  private def getScopeJobPartitionAggMetrics(df: GenericDataFrame, partitionValues: Seq[PartitionValues]): Map[String,_] = {
    implicit val functions: DataFrameFunctions = DataFrameSubFeed.getFunctions(df.subFeedType)
    import ExpectationValidation._
    val jobPartitionExpectations = expectations.filter(_.scope == ExpectationScope.JobPartition)
      .map(e => (e, e.getAggExpressionColumns(this.id)))
      .filter(_._2.nonEmpty)
    if (jobPartitionExpectations.nonEmpty) {
      this match {
        case partitionedDataObject: DataObject with CanHandlePartitions if partitionedDataObject.partitions.nonEmpty =>
          val aggExpressions = jobPartitionExpectations.flatMap(_._2)
          if (aggExpressions.nonEmpty) {
            logger.info(s"($id) collecting aggregate column metrics for expectations with scope = JobPartition")
            val dfMetrics = df.groupBy(partitionedDataObject.partitions.map(functions.col)).agg(aggExpressions)
            val colNames = dfMetrics.schema.columns
            def colNameIndex(colName: String) = colNames.indexOf(colName)
            val metrics = dfMetrics.collect.flatMap { row =>
              val partitionValuesStr = partitionedDataObject.partitions.map(c => Option(row.getAs[Any](colNameIndex(c))).map(_.toString).getOrElse(None)).mkString(partitionDelimiter)
              val metricsNameAndValue = jobPartitionExpectations.map(_._1).map(e => (e.name, Option(row.getAs[Any](colNameIndex(e.name))).getOrElse(None)))
              metricsNameAndValue.map { case (name, value) => (name + partitionDelimiter + partitionValuesStr, value) }
            }
            metrics.toMap
          } else Map()
        case _ => throw new IllegalStateException(s"($id) Expectation with scope = JobPartition defined for unpartitioned DataObject")
      }
    } else Map()
  }

  /**
   * Collect metrics for expectations with scope = All
   */
  private def getScopeAllAggMetrics(df: GenericDataFrame): Map[String,_] = {
    implicit val functions: DataFrameFunctions = DataFrameSubFeed.getFunctions(df.subFeedType)
    val allExpectationsWithExpressions = expectations.filter(_.scope == ExpectationScope.All)
      .map(e => (e,e.getAggExpressionColumns(this.id)))
      .filter(_._2.nonEmpty)
    val aggExpressions = allExpectationsWithExpressions.flatMap(_._2)
    if (aggExpressions.nonEmpty) {
      logger.info(s"($id) collecting aggregate column metrics for expectations with scope = All")
      val dfMetrics = df.agg(aggExpressions)
      val colNames = dfMetrics.schema.columns
      def colNameIndex(colName: String) = colNames.indexOf(colName)
      val metrics = dfMetrics.collect.flatMap {
        case row: GenericRow => allExpectationsWithExpressions.map(_._1).map(e => (e.name, Option(row.getAs[Any](colNameIndex(e.name))).getOrElse(None)))
      }
      metrics.toMap
    } else Map()
  }

  def validateExpectations(dfJob: GenericDataFrame, dfAll: GenericDataFrame, partitionValues: Seq[PartitionValues], scopeJobMetrics: Map[String, _])(implicit context: ActionPipelineContext): Map[String, _] = {
    // the evaluation of expectations is made with Spark expressions
    // collect metrics with scope = JobPartition
    val scopeJobPartitionMetrics = getScopeJobPartitionAggMetrics(dfJob, partitionValues)
    // collect metrics with scope = All
    val scopeAllMetrics = getScopeAllAggMetrics(dfAll)
    // collect custom metrics
    val customMetrics = expectations.flatMap(e => e.getCustomMetrics(this.id, if (e.scope==ExpectationScope.All) dfAll else dfJob))
    // evaluate expectations using dummy ExpressionData
    val metrics = scopeJobMetrics ++ scopeJobPartitionMetrics ++ scopeAllMetrics ++ customMetrics
    val defaultExpressionData = DefaultExpressionData.from(context, Seq())
    val (expectationValidationCols, updatedMetrics) = expectations.foldLeft(Seq[(Expectation,SparkColumn)](), metrics) {
      case ((cols, metrics), expectation) =>
        val (newCols, updatedMetrics) = expectation.getValidationErrorColumn(this.id, metrics, partitionValues)
        (cols ++ newCols.map(c => (expectation,c)), updatedMetrics)
    }
    val validationResults = expectationValidationCols.map {
      case (expectation, col) =>
        val errorMsg = SparkExpressionUtil.evaluate[DefaultExpressionData, String](this.id, Some("expectations"), col.inner, defaultExpressionData)
        (expectation, errorMsg)
    }.toMap.filter(_._2.nonEmpty).mapValues(_.get) // keep only failed results
    // log all failed results (before throwing exception)
    validationResults
      .foreach(result => result._1.failedSeverity match {
        case ExpectationSeverity.Warn => logger.warn(s"($id) ${result._2}")
        case ExpectationSeverity.Error => logger.error(s"($id) ${result._2}")
      })
    // throw exception on error, but log metrics before
    val errors = validationResults.filterKeys(_.failedSeverity == ExpectationSeverity.Error)
    if (errors.nonEmpty) logger.error(s"($id) Expectation validation failed with metrics "+updatedMetrics.map{case(k,v) => s"$k=$v"}.mkString(" "))
    errors.foreach(result => throw ExpectationValidationException(result._2))
    // return consolidated and updated metrics
    updatedMetrics
  }

  private def setupConstraintsValidation(df: GenericDataFrame): GenericDataFrame = {
    if (constraints.nonEmpty) {
      implicit val functions: DataFrameFunctions = DataFrameSubFeed.getFunctions(df.subFeedType)
      import functions._
      // use primary key if defined
      val pkCols = Some(this).collect{case tdo: TableDataObject => tdo}.flatMap(_.table.primaryKey)
      // as alternative search all columns with simple datatype
      val dfSimpleCols = df.schema.filter(_.dataType.isSimpleType).columns
      // add validation as additional column
      val validationErrorColumns = constraints.map(_.getValidationExceptionColumn(this.id, pkCols, dfSimpleCols))
      val dfErrors = df
        .withColumn("_validation_errors", array_construct_compact(validationErrorColumns: _*))
      // use column in where condition to avoid elimination by optimizer before dropping the column again.
      dfErrors
        .where(size(col("_validation_errors")) < lit(constraints.size+1)) // this is always true - but we want to force evaluating column "_validation_errors" to throw exceptions
        .drop("_validation_errors")
    } else df
  }

  protected def forceGenericObservation = false
  private def setupObservation(df: GenericDataFrame, expectationColumns: Seq[GenericColumn], isExecPhase: Boolean): (GenericDataFrame, DataFrameObservation) = {
    val (dfObserved, observation) = df.setupObservation(this.id + "-" + UUID.randomUUID(), expectationColumns, isExecPhase, forceGenericObservation)
    (dfObserved, observation)
  }
}

object ExpectationValidation {
  private[smartdatalake] final val partitionDelimiter = "#"
}