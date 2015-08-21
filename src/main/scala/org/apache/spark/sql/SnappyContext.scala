package org.apache.spark.sql

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.language.implicitConversions
import scala.reflect.ClassTag
import scala.reflect.runtime.{ universe => u }
import io.snappydata.util.SqlUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.Partition
import org.apache.spark.scheduler.local.LocalBackend
import org.apache.spark.sql.catalyst.analysis.Analyzer
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.{ LogicalPlan, Subquery }
import org.apache.spark.sql.catalyst.{ CatalystTypeConverters, ScalaReflection }
import org.apache.spark.sql.collection.{ UUIDRegionKey, Utils }
import org.apache.spark.sql.columnar._
import org.apache.spark.sql.execution._
import org.apache.spark.sql.execution.row._
import org.apache.spark.sql.execution.streamsummary.StreamSummaryAggregation
import org.apache.spark.Partitioner
import org.apache.spark.rdd.PairRDDFunctions
import org.apache.spark.sql.hive.SnappyStoreHiveCatalog
import org.apache.spark.sql.sources.{ CastLongTime, LogicalRelation, StoreStrategy, WeightageRule }
import org.apache.spark.sql.types.{ LongType, StructField, StructType }
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.{ StreamingContext, Time }
import org.apache.spark.{ Partitioner, SparkContext, TaskContext }
import org.apache.spark.sql.hive.QualifiedTableName

/**
 * An instance of the Spark SQL execution engine that delegates to supplied SQLContext
 * offering additional capabilities.
 *
 * Created by Soubhik on 5/13/15.
 */

protected[sql] final class SnappyContext(sc: SparkContext)
  extends SQLContext(sc) with Serializable {

  self =>

  // initialize GemFireXDDialect so that it gets registered
  GemFireXDDialect.init()

  @transient
  override protected[sql] val ddlParser = new SnappyDDLParser(sqlParser.parse)

  override protected[sql] def dialectClassName = if (conf.dialect == "sql") {
    classOf[SnappyParserDialect].getCanonicalName
  } else {
    conf.dialect
  }

  @transient
  override protected[sql] lazy val catalog =
    new SnappyStoreHiveCatalog(this)

  @transient
  override protected[sql] val cacheManager = new SnappyCacheManager(this)

  def saveStream[T: ClassTag](stream: DStream[T],
    aqpTables: Seq[String],
    formatter: (RDD[T], StructType) => RDD[Row],
    schema: StructType,
    transform: DataFrame => DataFrame = null) {
    stream.foreachRDD((rdd: RDD[T], time: Time) => {

      val rddRows = formatter(rdd, schema)

      val rows = if (transform != null) {
        // avoid conversion to Catalyst rows and back for both calls below,
        // so not using DataFrame.rdd call directly in second step below
        val rDF = createDataFrame(rddRows, schema, needsConversion = false)
        transform(rDF).queryExecution.toRdd
      } else rddRows

      collectSamples(rows, aqpTables, time.milliseconds)
    })
  }

  protected[sql] def collectSamples(rows: RDD[Row], aqpTables: Seq[String],
    time: Long,
    storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK) {
    val useCompression = conf.useCompression
    val columnBatchSize = conf.columnBatchSize
    val aqpTableNames = mutable.Set(aqpTables.map(
      catalog.newQualifiedTableName): _*)

    val sampleTables = catalog.tables.collect {
      case (name, sample: StratifiedSample) if aqpTableNames.contains(name) =>
        aqpTableNames.remove(name)
        (name, sample.options, sample.schema, sample.output,
          cacheManager.lookupCachedData(sample).getOrElse(sys.error(
            s"SnappyContext.saveStream: failed to lookup cached plan for " +
              s"sampling table $name")).cachedRepresentation)
    }

    val topKWrappers = catalog.topKStructures.filter {
      case (name, topkstruct) => aqpTableNames.remove(name)
    }

    if (aqpTableNames.nonEmpty) {
      throw new IllegalArgumentException("collectSamples: no sampling or " +
        s"topK structures for ${aqpTableNames.mkString(", ")}")
    }

    // TODO: this iterates rows multiple times
    val rdds = sampleTables.map {
      case (name, samplingOptions, schema, output, relation) =>
        (relation, rows.mapPartitions(rowIterator => {
          val sampler = StratifiedSampler(samplingOptions, Array.emptyIntArray,
            nameSuffix = "", columnBatchSize, schema, cached = true)
          val catalystConverters = schema.fields.map(f =>
            CatalystTypeConverters.createToCatalystConverter(f.dataType))
          // create a new holder for set of CachedBatches
          val batches = ExternalStoreRelation(useCompression,
            columnBatchSize, name, schema, relation, output)
          sampler.append(rowIterator, catalystConverters, (),
            batches.appendRow, batches.endRows)
          batches.forceEndOfBatch().iterator

        }))
    }
    // TODO: A different set of job is created for topK structure

    topKWrappers.foreach {
      case (name, (topKWrapper, topkRDD)) =>
        val clazz = SqlUtils.getInternalType(
          topKWrapper.schema(topKWrapper.key.name).dataType)
        val ct = ClassTag(clazz)
        SnappyContext.populateTopK(rows, topKWrapper, this,
          name, topkRDD, time)(ct)

    }

    // add to list in relation
    // TODO: avoid a separate job for each RDD and instead try to do it
    // TODO: using a single UnionRDD or something
    rdds.foreach {
      case (relation, rdd) =>
        val cached = rdd.persist(storageLevel)
        if (cached.count() > 0) {
          relation match {
            case externalStore: ExternalStoreRelation =>
              externalStore.appendUUIDBatch(cached.asInstanceOf[RDD[UUIDRegionKey]])
            case appendable: InMemoryAppendableRelation =>
              appendable.appendBatch(cached.asInstanceOf[RDD[CachedBatch]])
          }
        }
    }
  }

  def appendToCache(df: DataFrame, tableIdent: String,
    storageLevel: StorageLevel = StorageLevel.MEMORY_AND_DISK) {
    val useCompression = conf.useCompression
    val columnBatchSize = conf.columnBatchSize

    val tableName = catalog.newQualifiedTableName(tableIdent)
    val plan = catalog.lookupRelation(tableName, None)
    val relation = cacheManager.lookupCachedData(plan).getOrElse {
      cacheManager.cacheQuery(DataFrame(this, plan),
        Some(tableName.qualifiedName), storageLevel)

      cacheManager.lookupCachedData(plan).getOrElse {
        sys.error(s"couldn't cache table $tableName")
      }
    }

    val (schema, output) = (df.schema, df.logicalPlan.output)

    val cached = df.mapPartitions { rowIterator =>

      val batches = ExternalStoreRelation(useCompression, columnBatchSize,
        tableName, schema, relation.cachedRepresentation, output)

      val converter = CatalystTypeConverters.createToCatalystConverter(schema)
      rowIterator.map(converter(_).asInstanceOf[Row])
        .foreach(batches.appendRow((), _))
      batches.forceEndOfBatch().iterator
    }.persist(storageLevel)

    // trigger an Action to materialize 'cached' batch
    if (cached.count() > 0) {
      relation.cachedRepresentation match {
        case externalStore: ExternalStoreRelation =>
          externalStore.appendUUIDBatch(cached.asInstanceOf[RDD[UUIDRegionKey]])
        case appendable: InMemoryAppendableRelation =>
          appendable.appendBatch(cached.asInstanceOf[RDD[CachedBatch]])
      }
    }
  }

  def truncateTable(tableName: String): Unit = {
    cacheManager.lookupCachedData(catalog.lookupRelation(
      tableName)).foreach(_.cachedRepresentation.
      asInstanceOf[InMemoryAppendableRelation].truncate())
  }

  def registerTable[A <: Product: u.TypeTag](tableName: String) = {
    if (u.typeOf[A] =:= u.typeOf[Nothing]) {
      sys.error("Type of case class object not mentioned. " +
        "Mention type information for e.g. registerSampleTableOn[<class>]")
    }

    SparkPlan.currentContext.set(self)
    val schema = ScalaReflection.schemaFor[A].dataType
      .asInstanceOf[StructType]

    val plan: LogicalRDD = LogicalRDD(schema.toAttributes,
      new DummyRDD(this))(this)

    catalog.registerTable(Seq(tableName), plan)
  }

  def registerSampleTable(tableName: String, schema: StructType,
    samplingOptions: Map[String, Any], streamTable: Option[String] = None,
    jdbcSource: Option[Map[String, String]] = None): SampleDataFrame = {
    catalog.registerSampleTable(tableName, schema, samplingOptions,
      None, streamTable.map(catalog.newQualifiedTableName), jdbcSource)
  }

  def registerSampleTableOn[A <: Product: u.TypeTag](tableName: String,
    samplingOptions: Map[String, Any], streamTable: Option[String] = None,
    jdbcSource: Option[Map[String, String]] = None): DataFrame = {
    if (u.typeOf[A] =:= u.typeOf[Nothing]) {
      sys.error("Type of case class object not mentioned. " +
        "Mention type information for e.g. registerSampleTableOn[<class>]")
    }
    SparkPlan.currentContext.set(self)
    val schemaExtract = ScalaReflection.schemaFor[A].dataType
      .asInstanceOf[StructType]
    registerSampleTable(tableName, schemaExtract, samplingOptions,
      streamTable, jdbcSource)
  }

  def registerAndInsertIntoExternalStore(df: DataFrame, tableName: String,
    schema: StructType, jdbcSource: Map[String, String]): Unit = {
    catalog.registerAndInsertIntoExternalStore(df, tableName, schema, jdbcSource)
  }

  def registerTopK(tableName: String, streamTableName: String,
    topkOptions: Map[String, Any], isStreamSummary: Boolean) = {
    val topKRDD = SnappyContext.createTopKRDD(tableName, this.sc, isStreamSummary)
    catalog.registerTopK(tableName, streamTableName,
      catalog.getStreamTableRelation(streamTableName).schema, topkOptions, topKRDD)

  }

  /**
   * Registers the given [[DataFrame]] as a external table in the catalog.
   */
  private[sql] def registerExternalTable(df: DataFrame, tableName: String): Unit = {
    catalog.registerTable(Seq(tableName), df.logicalPlan)
  }

  // insert/update/delete/drop operations on an external table

  def insert(tableName: String, rows: Row*): Int = {
    catalog.lookupRelation(tableName) match {
      case LogicalRelation(r: RowInsertableRelation) => r.insert(rows)
      case _ => throw new AnalysisException(
        s"$tableName is not a row insertable table")
    }
  }

  def update(tableName: String, filterExpr: String, newColumnValues: Row,
    updateColumns: String*): Int = {
    catalog.lookupRelation(tableName) match {
      case LogicalRelation(u: UpdatableRelation) =>
        u.update(filterExpr, newColumnValues, updateColumns)
      case _ => throw new AnalysisException(
        s"$tableName is not an updatable table")
    }
  }

  def delete(tableName: String, filterExpr: String): Int = {
    catalog.lookupRelation(tableName) match {
      case LogicalRelation(d: DeletableRelation) => d.delete(filterExpr)
      case _ => throw new AnalysisException(
        s"$tableName is not a deletable table")
    }
  }

  def dropExternalTable(tableName: String): Unit = {
    val df = table(tableName)
    // additional cleanup for external tables, if required
    df.logicalPlan match {
      case LogicalRelation(br) =>
        cacheManager.tryUncacheQuery(df)
        catalog.unregisterTable(Seq(tableName))
        br match {
          case d: DeletableRelation => d.destroy()
        }
      case _ => throw new AnalysisException(
        s"Table $tableName not an external table")
    }
  }

  // end of insert/update/delete/drop operations

  @transient
  override protected[sql] lazy val analyzer: Analyzer =
    new Analyzer(catalog, functionRegistry, conf) {
      override val extendedResolutionRules =
        ExtractPythonUdfs ::
          sources.PreInsertCastAndRename ::
          WeightageRule ::
          Nil

      override val extendedCheckRules = Seq(
        sources.PreWriteCheck(catalog))
    }

  @transient override protected[sql] val planner = new SparkPlanner {
    val snappyContext = self

    override def strategies: Seq[Strategy] = Seq(
      SnappyStrategies, StreamStrategy, StoreStrategy) ++ super.strategies

    object SnappyStrategies extends Strategy {
      def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
        case s @ StratifiedSample(options, child, _) =>
          s.getExecution(planLater(child)) :: Nil
        case PhysicalOperation(projectList, filters,
          mem: columnar.InMemoryAppendableRelation) =>
          pruneFilterProject(
            projectList,
            filters,
            identity[Seq[Expression]], // All filters still need to be evaluated
            InMemoryAppendableColumnarTableScan(_, filters, mem)) :: Nil
        case _ => Nil
      }
    }

  }

  /**
   * Queries the topK structure between two points in time. If the specified
   * time lies between a topK interval the whole interval is considered
   *
   * @param topKName - The topK structure that is to be queried.
   * @param startTime start time as string of the format "yyyy-mm-dd hh:mm:ss".
   *                  If passed as null, oldest interval is considered as the start interval.
   * @param endTime  end time as string of the format "yyyy-mm-dd hh:mm:ss".
   *                 If passed as null, newest interval is considered as the last interval.
   * @param k Optional. Number of elements to be queried. This is to be passed only for stream summary
   * @return returns the top K elements with their respective frequencies between two time
   */
  def queryTopK[T: ClassTag](topKName: String,
    startTime: String = null, endTime: String = null,
    k: Int = -1): DataFrame = {
    val stime = if (startTime == null) 0L
    else CastLongTime.getMillis(java.sql.Timestamp.valueOf(startTime))

    val etime = if (endTime == null) Long.MaxValue
    else CastLongTime.getMillis(java.sql.Timestamp.valueOf(endTime))

    queryTopK[T](topKName, stime, etime, k)
  }

  def queryTopK[T: ClassTag](topKName: String,
    startTime: Long, endTime: Long): DataFrame =
    queryTopK[T](topKName, startTime, endTime, -1)

  def queryTopK[T: ClassTag](topKIdent: String,
    startTime: Long, endTime: Long, k: Int): DataFrame = {
    val topKTableName = catalog.newQualifiedTableName(topKIdent)

    val (topkWrapper, _) = catalog.topKStructures(topKTableName)
    topkWrapper.rwlock.executeInReadLock {
      //requery the catalog to obtain the TopKRDD
      val (_, rdd) = catalog.topKStructures(topKTableName)
      val size = if (k > 0) k else topkWrapper.size

      val topKName = topKTableName.qualifiedName
      if (topkWrapper.stsummary) {

        queryTopkStreamSummary(topKName, startTime, endTime, topkWrapper, size, rdd)
      } else {
        queryTopkHokusai(topKName, startTime, endTime, topkWrapper, rdd, size)

      }
    }
  }

  import snappy.RDDExtensions

  def queryTopkStreamSummary[T: ClassTag](topKName: String,
    startTime: Long, endTime: Long,
    topkWrapper: TopKWrapper, k: Int, topkRDD: RDD[(Int, TopK)]): DataFrame = {
    val rdd = topkRDD.mapPartitionsPreserve[(T, Approximate)] { iter =>
      {
        iter.next()._2 match {
          case x: StreamSummaryAggregation[_] => {
            val arrayTopK = x.asInstanceOf[StreamSummaryAggregation[T]].getTopKBetweenTime(startTime,
              endTime, x.capacity)
            arrayTopK.map(_.toIterator).getOrElse(Iterator.empty)
          }
          case _ => Iterator.empty
        }
      }
    }
    val topKRDD = rdd.reduceByKey(_ + _).mapPreserve {
      case (key, approx) =>
        Row(key, approx.estimate, approx.lowerBound)
    }

    val aggColumn = "EstimatedValue"
    val errorBounds = "DeltaError"
    val topKSchema = StructType(Array(topkWrapper.key,
      StructField(aggColumn, LongType),
      StructField(errorBounds, LongType)))

    val df = createDataFrame(topKRDD, topKSchema)
    df.sort(df.col(aggColumn).desc).limit(k)
  }

  def queryTopkHokusai[T: ClassTag](topKName: String,
    startTime: Long, endTime: Long,
    topkWrapper: TopKWrapper, topkRDD: RDD[(Int, TopK)], k: Int): DataFrame = {

    // TODO: perhaps this can be done more efficiently via a shuffle but
    // using the straightforward approach for now

    // first collect keys from across the cluster
    val rdd = topkRDD.mapPartitionsPreserve[(T, Approximate)] { iter =>
      {
        iter.next()._2 match {
          case x: TopKHokusai[_] => {
            val arrayTopK = if (x.windowSize == Long.MaxValue)
              Some(x.asInstanceOf[TopKHokusai[T]].getTopKInCurrentInterval)
            else
              x.asInstanceOf[TopKHokusai[T]].getTopKBetweenTime(startTime, endTime)

            arrayTopK.map(_.toIterator).getOrElse(Iterator.empty)
          }
          case _ => Iterator.empty
        }
      }
    }
    val topKRDD = rdd.reduceByKey(_ + _).mapPreserve {
      case (key, approx) =>
        Row(key, approx.estimate, approx)
    }

    val aggColumn = "EstimatedValue"
    val errorBounds = "ErrorBoundsInfo"
    val topKSchema = StructType(Array(topkWrapper.key,
      StructField(aggColumn, LongType),
      StructField(errorBounds, ApproximateType)))

    val df = createDataFrame(topKRDD, topKSchema)
    df.sort(df.col(aggColumn).desc).limit(k)
  }

  private var storeConfig: Map[String, String] = _

  def setExternalStoreConfig(conf: Map[String, String]) = {
    this.storeConfig = conf
  }

  def getExternalStoreConfig: Map[String, String] = {
    storeConfig
  }

  def isLoner = sparkContext.schedulerBackend match {
    case lb: LocalBackend => true
    case _ => false
  }

}

object snappy extends Serializable {

  implicit def snappyOperationsOnDataFrame(df: DataFrame): SnappyOperations = {
    df.sqlContext match {
      case sc: SnappyContext => SnappyOperations(sc, df)
      case sc => throw new AnalysisException("Extended snappy operations " +
        s"require SnappyContext and not ${sc.getClass.getSimpleName}")
    }
  }

  private def stratifiedSampleOrError(plan: LogicalPlan): StratifiedSample = {
    plan match {
      case ss: StratifiedSample => ss
      case Subquery(_, child) => stratifiedSampleOrError(child)
      case s => throw new AnalysisException("Stratified sampling " +
        "operations require stratifiedSample plan and not " +
        s"${s.getClass.getSimpleName}")
    }
  }

  implicit def samplingOperationsOnDataFrame(df: DataFrame): SampleDataFrame = {
    df.sqlContext match {
      case sc: SnappyContext =>
        new SampleDataFrame(sc, stratifiedSampleOrError(df.logicalPlan))
      case sc => throw new AnalysisException("Extended snappy operations " +
        s"require SnappyContext and not ${sc.getClass.getSimpleName}")
    }
  }

  implicit def snappyOperationsOnDStream[T: ClassTag](
    ds: DStream[T]): SnappyDStreamOperations[T] =
    SnappyDStreamOperations(SnappyContext(ds.context.sparkContext), ds)

  implicit class SparkContextOperations(val s: SparkContext) {
    def getOrCreateStreamingContext(batchInterval: Int = 2): StreamingContext = {
      StreamingCtxtHolder(s, batchInterval)
    }
  }

  implicit class RDDExtensions[T: ClassTag](rdd: RDD[T]) extends Serializable {

    /**
     * Return a new RDD by applying a function to all elements of this RDD.
     */
    def mapPreserve[U: ClassTag](f: T => U): RDD[U] = rdd.withScope {
      val cleanF = rdd.sparkContext.clean(f)
      new MapPartitionsPreserveRDD[U, T](rdd,
        (context, pid, iter) => iter.map(cleanF))
    }

    /**
     * Return a new RDD by applying a function to each partition of given RDD.
     * This variant also preserves the preferred locations of parent RDD.
     *
     * `preservesPartitioning` indicates whether the input function preserves
     * the partitioner, which should be `false` unless this is a pair RDD and
     * the input function doesn't modify the keys.
     */
    def mapPartitionsPreserve[U: ClassTag](
      f: Iterator[T] => Iterator[U],
      preservesPartitioning: Boolean = false): RDD[U] = rdd.withScope {
      val cleanedF = rdd.sparkContext.clean(f)
      new MapPartitionsPreserveRDD(rdd,
        (context: TaskContext, index: Int, iter: Iterator[T]) => cleanedF(iter),
        preservesPartitioning)
    }
  }

}

object SnappyContext {

  /** The default version of hive used internally by Spark SQL. */
  val hiveDefaultVersion: String = "0.13.1"

  val HIVE_METASTORE_VERSION: String = "spark.sql.hive.metastore.version"
  val HIVE_METASTORE_JARS: String = "spark.sql.hive.metastore.jars"

  private val atomicContext = new AtomicReference[SnappyContext]()

  def apply(sc: SparkContext,
    init: SnappyContext => SnappyContext = identity): SnappyContext = {
    val context = atomicContext.get
    if (context != null) {
      context
    } else {
      atomicContext.compareAndSet(null, init(new SnappyContext(sc)))
      atomicContext.get
    }
  }

  def createTopKRDD(name: String, context: SparkContext, isStreamSummary: Boolean): RDD[(Int, TopK)] = {
    val partCount = Utils.getAllExecutorsMemoryStatus(context).
      keySet.size
    Utils.getFixedPartitionRDD[(Int, TopK)](context,
      (tc: TaskContext, part: Partition) => {
        scala.collection.Iterator(part.index -> TopKHokusai.createDummy(isStreamSummary))
      }, new Partitioner() {
        override def numPartitions: Int = partCount
        override def getPartition(key: Any) = scala.math.abs(key.hashCode()) % partCount
      }, partCount)
  }

  def getEpoch0AndIterator[T: ClassTag](name: String, topkWrapper: TopKWrapper,
    iterator: Iterator[(T, Any)]): (() => Long, Iterator[(T, Any)], Int) = {
    if (iterator.hasNext) {
      var tupleIterator = iterator
      val tsCol = if (topkWrapper.timeInterval > 0)
        topkWrapper.timeSeriesColumn
      else -1
      val epoch = () => {
        if (topkWrapper.epoch != -1L) {
          topkWrapper.epoch
        } else if (tsCol >= 0) {
          var epoch0 = -1L
          val iter = tupleIterator.asInstanceOf[Iterator[(T, (Long, Long))]]
          val tupleBuf = new mutable.ArrayBuffer[(T, (Long, Long))](4)

          // assume first row will have the least time
          // TODO: this assumption may not be correct and we may need to
          // do something more comprehensive
          do {
            val tuple = iter.next()
            epoch0 = tuple match {
              case (_, (_, epochh)) => epochh
            }

            tupleBuf += tuple.copy()
          } while (epoch0 <= 0)
          tupleIterator = tupleBuf.iterator ++ iter
          epoch0
        } else {
          System.currentTimeMillis()
        }

      }
      (epoch, tupleIterator, tsCol)
    } else {
      null
    }
  }

  def addDataForTopK[T: ClassTag](name: String, topKWrapper: TopKWrapper,
    tupleIterator: Iterator[(T, Any)], topK: TopK, tsCol: Int, time: Long): Unit = {

    val streamSummaryAggr: StreamSummaryAggregation[T] = if (topKWrapper.stsummary) {
      topK.asInstanceOf[StreamSummaryAggregation[T]]
    } else {
      null
    }
    val topKHokusai = if (!topKWrapper.stsummary) {
      topK.asInstanceOf[TopKHokusai[T]]
    } else {
      null
    }
    val topKKeyIndex = topKWrapper.schema.fieldIndex(topKWrapper.key.name)
    if (tsCol < 0) {
      if (topKWrapper.stsummary) {
        throw new IllegalStateException(
          "Timestamp column is required for stream summary")
      }
      topKWrapper.frequencyCol match {
        case None =>
          topKHokusai.addEpochData(
            tupleIterator.map(_._1).toSeq)
        case Some(freqCol) =>
          val datamap = mutable.Map[T, Long]()

          tupleIterator.asInstanceOf[Iterator[(T, Long)]] foreach {
            case (key, freq) =>
              datamap.get(key) match {
                case Some(prevvalue) => datamap +=
                  (key -> (prevvalue + freq))
                case None => datamap +=
                  (key -> freq)
              }

          }
          topKHokusai.addEpochData(datamap, time)
      }
    } else {
      val dataBuffer = new mutable.ArrayBuffer[KeyFrequencyWithTimestamp[T]]
      val buffer = topKWrapper.frequencyCol match {
        case None =>
          tupleIterator.asInstanceOf[Iterator[(T, Long)]] foreach {
            case (key, timeVal) =>
              dataBuffer += new KeyFrequencyWithTimestamp[T](key, 1L, timeVal)
          }
          dataBuffer
        case Some(freqCol) =>
          tupleIterator.asInstanceOf[Iterator[(T, (Long, Long))]] foreach {
            case (key, (freq, timeVal)) =>
              dataBuffer += new KeyFrequencyWithTimestamp[T](key,
                freq, timeVal)
          }
          dataBuffer
      }
      if (topKWrapper.stsummary)
        streamSummaryAggr.addItems(buffer)
      else
        topKHokusai.addTimestampedData(buffer)

    }
  }

  def populateTopK[T: ClassTag](rows: RDD[Row], topkWrapper: TopKWrapper,
    context: SnappyContext, name: QualifiedTableName, topKRDD: RDD[(Int, TopK)],
    time: Long) {
    val partitioner = topKRDD.partitioner.get
    val pairRDD = rows.map[(Int, (Any, Any))](topkWrapper.rowToTupleConverter(_, partitioner))
    val nameAsString = name.toString
    val newTopKRDD = topKRDD.cogroup(pairRDD).mapPartitions[(Int, TopK)](
      iterator => {
        val (key, (topkIterable, dataIterable)) = iterator.next()

        val topK = topkIterable.head match {
          case x: TopKHokusai[_] => x
          case y: StreamSummaryAggregation[_] => y
          case z =>

            val (epoch0, iter, tsCol) = getEpoch0AndIterator[T](nameAsString, topkWrapper,
              dataIterable.asInstanceOf[Iterable[(T, Any)]].iterator)
            if (topkWrapper.stsummary) {
              StreamSummaryAggregation.create[T](topkWrapper.size,
                topkWrapper.timeInterval, epoch0, topkWrapper.maxinterval)
            } else {
              TopKHokusai.create[T](topkWrapper.cms, topkWrapper.size,
                tsCol, topkWrapper.timeInterval, epoch0)
            }
        }
        val tsCol = if (topkWrapper.timeInterval > 0)
          topkWrapper.timeSeriesColumn
        else -1

        SnappyContext.addDataForTopK[T](nameAsString, topkWrapper,
          dataIterable.asInstanceOf[Iterable[(T, Any)]].iterator,
          topK, tsCol, time)

        scala.collection.Iterator(key -> topK)
      }, true)

    newTopKRDD.persist()
    //To allow execution of RDD
    newTopKRDD.count
    context.catalog.topKStructures.put(name, topkWrapper -> newTopKRDD)
    //Unpersist old rdd in a write lock

    topkWrapper.rwlock.executeInWriteLock {
      topKRDD.unpersist(false)
    }
  }
}

//end of SnappyContext

private[sql] case class SnappyOperations(context: SnappyContext,
  df: DataFrame) {

  /**
   * Creates stratified sampled data from given DataFrame
   * {{{
   *   peopleDf.stratifiedSample(Map("qcs" -> Array(1,2), "fraction" -> 0.01))
   * }}}
   */
  def stratifiedSample(options: Map[String, Any]): SampleDataFrame =
    new SampleDataFrame(context, StratifiedSample(options, df.logicalPlan)())

  def createTopK(ident: String, options: Map[String, Any]): Unit = {
    val name = context.catalog.newQualifiedTableName(ident)
    val schema = df.logicalPlan.schema

    // Create a very long timeInterval when the topK is being created
    // on a DataFrame.

    val topKWrapper = TopKWrapper(name, options, schema)

    val clazz = SqlUtils.getInternalType(
      topKWrapper.schema(topKWrapper.key.name).dataType)
    val ct = ClassTag(clazz)
    val topKRDD = SnappyContext.createTopKRDD(name.toString, context.sparkContext, topKWrapper.stsummary)
    context.catalog.topKStructures.put(name, topKWrapper -> topKRDD)
    SnappyContext.populateTopK(df.rdd, topKWrapper, context,
      name, topKRDD, System.currentTimeMillis())(ct)

    /*df.foreachPartition((x: Iterator[Row]) => {
      context.addDataForTopK(name, topKWrapper, x)(ct)
    })*/

  }

  /**
   * Table must be registered using #registerSampleTable.
   */
  def insertIntoSampleTables(sampleTableName: String*) =
    context.collectSamples(df.rdd, sampleTableName, System.currentTimeMillis())

  /**
   * Append to an existing cache table.
   * Automatically uses #cacheQuery if not done already.
   */
  def appendToCache(tableName: String) = context.appendToCache(df, tableName)

  def registerAndInsertIntoExternalStore(tableName: String,
    jdbcSource: Map[String, String]): Unit = {
    context.registerAndInsertIntoExternalStore(df, tableName,
      df.schema, jdbcSource)
  }
}

private[sql] case class SnappyDStreamOperations[T: ClassTag](
  context: SnappyContext, ds: DStream[T]) {

  def saveStream(sampleTab: Seq[String],
    formatter: (RDD[T], StructType) => RDD[Row],
    schema: StructType,
    transform: DataFrame => DataFrame = null): Unit =
    context.saveStream(ds, sampleTab, formatter, schema, transform)
}
