package com.jcdecaux.datacorp.spark.storage

import com.jcdecaux.datacorp.spark.Builder
import com.jcdecaux.datacorp.spark.config.Conf
import com.jcdecaux.datacorp.spark.config.Conf.Serializer
import com.jcdecaux.datacorp.spark.enums.Storage
import com.jcdecaux.datacorp.spark.exception.UnknownException
import com.jcdecaux.datacorp.spark.storage.connector._
import com.jcdecaux.datacorp.spark.storage.repository.SparkRepository
import com.typesafe.config.{Config, ConfigException, ConfigValueFactory}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{SaveMode, SparkSession}

import scala.reflect.runtime.{universe => ru}

/**
  * The SparkRepositoryBuilder will build a [[SparkRepository]] according to the given [[DataType]] and [[Storage]]
  *
  * @param spark   spark session
  * @param storage type of storage
  * @param config  a [[com.typesafe.config.Config]] object
  * @tparam DataType type of data
  */
class SparkRepositoryBuilder[DataType: ru.TypeTag](var spark: Option[SparkSession],
                                                   var storage: Option[Storage],
                                                   var config: Option[Config])
  extends Builder[SparkRepository[DataType]] {

  import Conf.Serializer._

  def this() = this(None, None, None)

  def this(spark: SparkSession) = this(Some(spark), None, None)

  def this(storage: Storage) = this(None, Some(storage), None)

  def this(config: Config) = this(None, None, Some(config))

  private[this] val conf: Conf = new Conf()

  if (config.isEmpty) {

    storage match {
      case Some(s) => set("storage", s)
      case _ =>
    }
    set("inferSchema", true)
    set("delimiter", ";")
    set("useHeader", true)
    set("header", true)
    set("saveMode", "Overwrite")
    set("dataAddress", "A1")
    set("treatEmptyValuesAsNulls", true)
    set("addColorColumns", false)
    set("timestampFormat", "yyyy-mm-dd hh:mm:ss.000")
    set("dateFormat", "yyyy-mm-dd")
    set("excerptSize", 10L)
  }

  private[this] var connector: Connector = _
  private[this] var sparkRepository: com.jcdecaux.datacorp.spark.storage.repository.SparkRepository[DataType] = _

  def set[T](key: String, value: T)(implicit converter: Serializer[T]): this.type = {
    conf.set(key, value)
    this
  }

  def getAs[T](key: String)(implicit converter: Serializer[T]): Option[T] = {
    conf.getAs[T](key)
  }

  // TODO : @Mounir: here we only handle parquet/csv/excel storage.
  //                 No changes will be made for cassandra and dynamodb connector if we set a suffix
  /**
    * Only affect file storage system to get a specific path (exp : Reach -> suffix [Rome])
    *
    * @param pathSuffix
    * @return
    */
  def setSuffix(pathSuffix: String): this.type = {
    config match {
      case Some(configuration) =>
        try {
          config = Some(configuration.withValue("path", ConfigValueFactory.fromAnyRef(configuration.getString("path") + "/" + pathSuffix)))
        } catch {
          case missing: ConfigException.Missing => log.error("To use suffix please make sure you have a path in your configuration")
          case e: Throwable => throw e
        }
      case _ =>
        log.debug("No connector configuration was found. Setting suffix variable")
        set("path", s"${getAs[String]("path").get}/$pathSuffix")
    }
    this
  }

  def setSpark(spark: SparkSession): this.type = {
    this.spark = Option(spark)
    this
  }

  def setStorage(storage: Storage): this.type = set("storage", storage)

  def setKeyspace(keyspace: String): this.type = set("keyspace", keyspace)

  def setTable(table: String): this.type = set("table", table)

  def setPartitionKeys(cols: Option[Seq[String]]): this.type = set("partitionKeyColumns", cols.get.toArray)

  def setClusteringKeys(cols: Option[Seq[String]]): this.type = set("clusteringKeyColumns", cols.get.toArray)

  def setPath(path: String): this.type = set("path", path)

  def setInferSchema(boo: Boolean): this.type = set("inferSchema", boo)

  def setSchema(schema: StructType): this.type = {

    // For spark version < 2.4, there was no method toDDL in StructType.
    val structDDL = try {
      val method = schema.getClass.getDeclaredMethod("toDDL")
      method.invoke(schema).toString
    } catch {
      case _: java.lang.NoSuchMethodException =>
        schema.map(sf => s"${sf.name} ${sf.dataType.sql}").mkString(", ")
      case e: Throwable => throw e
    }

    set("schema", structDDL)
  }

  def setDelimiter(delimiter: String): this.type = set("delimiter", delimiter)

  def setUseHeader(boo: Boolean): this.type = set("useHeader", boo)

  def setHeader(boo: Boolean): this.type = set("header", boo)

  def setSaveMode(saveMode: SaveMode): this.type = set("saveMode", saveMode.toString)

  def setDataAddress(address: String): this.type = set("dataAddress", address)

  def setTreatEmptyValuesAsNulls(boo: Boolean): this.type = set("treatEmptyValuesAsNulls", boo)

  def setAddColorColumns(boo: Boolean): this.type = set("addColorColumns", boo)

  def setTimestampFormat(fmt: String): this.type = set("timestampFormat", fmt)

  def setDateFormat(fmt: String): this.type = set("dateFormat", fmt)

  def setMaxRowsInMemory(maxRowsInMemory: Long): this.type = set("maxRowsInMemory", maxRowsInMemory)

  def setExcerptSize(size: Long): this.type = set("excerptSize", size)

  def setWorkbookPassword(pwd: String): this.type = set("workbookPassword", pwd)

  /**
    * Build an object
    *
    * @return
    */
  override def build(): SparkRepositoryBuilder.this.type = {
    log.debug(s"Build SparkRepository[${ru.typeOf[DataType]}]")
    if (connector == null) {
      connector = createConnector()
    }
    sparkRepository = new com.jcdecaux.datacorp.spark.storage.repository.SparkRepository[DataType].setConnector(connector)
    this
  }

  /**
    * Create the connector according to the storage type
    *
    * @return [[Connector]]
    */
  protected[this] def createConnector(): Connector = {

    // Check if spark is set
    spark match {
      case None => throw new NullPointerException("SparkSession is not defined")
      case _ =>
    }

    // if a typesafe config is set, then return a corresponding connector
    config match {
      case Some(typesafeConfig) =>
        try {
          log.debug("Build connector with configuration")
          return new ConnectorBuilder(spark.get, typesafeConfig).build().get()
        } catch {
          case unknown: UnknownException.Storage => log.error("Unknown storage type in connector configuration")
          case e: Throwable => throw e
        }

      case _ => log.debug("No com.typesafe connector configuration was found, build with parameters")
    }

    // Otherwise, build a connector according to the current configuration
    new ConnectorBuilder(spark.get, conf)
      .build()
      .get()

  }

  def setConnector(connector: Connector): this.type = {
    log.info(s"Set user-defined ${connector.getClass} connector")
    this.connector = connector
    this
  }

  /**
    * Get the built spark repository
    *
    * @return [[SparkRepository]]
    */
  override def get(): com.jcdecaux.datacorp.spark.storage.repository.SparkRepository[DataType] = this.sparkRepository
}
