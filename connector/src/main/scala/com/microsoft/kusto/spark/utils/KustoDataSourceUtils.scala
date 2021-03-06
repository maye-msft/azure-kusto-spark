package com.microsoft.kusto.spark.utils

import java.security.InvalidParameterException
import java.util
import java.util.concurrent.{CountDownLatch, TimeUnit, TimeoutException}
import java.util.{NoSuchElementException, StringJoiner, Timer, TimerTask}

import com.microsoft.azure.kusto.data.{Client, ClientRequestProperties, Results}
import com.microsoft.kusto.spark.authentication._
import com.microsoft.kusto.spark.common.{KustoCoordinates, KustoDebugOptions}
import com.microsoft.kusto.spark.datasink.SinkTableCreationMode.SinkTableCreationMode
import com.microsoft.kusto.spark.datasink.{KustoSinkOptions, SinkTableCreationMode, WriteOptions}
import com.microsoft.kusto.spark.datasource.{KustoResponseDeserializer, KustoSchema, KustoSourceOptions, KustoStorageParameters}
import com.microsoft.kusto.spark.utils.CslCommandsGenerator._
import com.microsoft.kusto.spark.utils.{KustoConstants => KCONST}
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import java.util.Properties

import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConversions._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.matching.Regex
import org.apache.commons.lang3.StringUtils

object KustoDataSourceUtils {
  private val klog = Logger.getLogger("KustoConnector")
  val urlPattern: Regex = raw"https://(?:ingest-)?(.+).kusto.windows.net(?::443)?".r
  val sasPattern: Regex = raw"(?:https://)?([^.]+).blob.core.windows.net/([^?]+)?(.+)".r


  var ClientName = "Kusto.Spark.Connector"
  val NewLine = sys.props("line.separator")
  val MaxWaitTime: FiniteDuration = 1 minute

  try {
    val input = getClass.getClassLoader.getResourceAsStream("app.properties")
    val prop = new Properties( )
    prop.load(input)
    val version = prop.getProperty("application.version")
    ClientName = s"$ClientName:$version"
  } catch {
    case x: Throwable =>
      klog.warn("Couldn't parse the connector's version:" + x)
  }

  def setLoggingLevel(level: String): Unit = {
    setLoggingLevel(Level.toLevel(level))
  }

  def setLoggingLevel(level: Level): Unit = {
    Logger.getLogger("KustoConnector").setLevel(level)
  }

  private[kusto] def logInfo(reporter: String, message: String): Unit = {
    klog.info(s"$reporter: $message")
  }

  private[kusto] def logWarn(reporter: String, message: String): Unit = {
    klog.warn(s"$reporter: $message")
  }

  private[kusto] def logError(reporter: String, message: String): Unit = {
    klog.error(s"$reporter: $message")
  }

  private[kusto] def logFatal(reporter: String, message: String): Unit = {
    klog.fatal(s"$reporter: $message")
  }

  private[kusto] def extractSchemaFromResultTable(resultRows: util.ArrayList[util.ArrayList[String]]): String = {

    val tableSchemaBuilder = new StringJoiner(",")

    for (row <- resultRows) {
      // Each row contains {Name, CslType, Type}, converted to (Name:CslType) pairs
      tableSchemaBuilder.add(s"['${row.get(0)}']:${row.get(1)}")
    }

    tableSchemaBuilder.toString
  }

  private[kusto] def getSchema(database: String, query: String, client: Client): KustoSchema = {
    KustoResponseDeserializer(client.execute(database, query)).getSchema
  }

  def parseSourceParameters(parameters: Map[String, String]): SourceParameters = {
    // Parse KustoTableCoordinates - these are mandatory options
    val database = parameters.get(KustoSourceOptions.KUSTO_DATABASE)
    val cluster = parameters.get(KustoSourceOptions.KUSTO_CLUSTER)

    if (database.isEmpty) {
      throw new InvalidParameterException("KUSTO_DATABASE parameter is missing. Must provide a destination database name")
    }

    if (cluster.isEmpty) {
      throw new InvalidParameterException("KUSTO_CLUSTER parameter is missing. Must provide a destination cluster name")
    }

    val table = parameters.get(KustoSinkOptions.KUSTO_TABLE)

    // Parse KustoAuthentication
    val applicationId = parameters.getOrElse(KustoSourceOptions.KUSTO_AAD_CLIENT_ID, "")
    val applicationKey = parameters.getOrElse(KustoSourceOptions.KUSTO_AAD_CLIENT_PASSWORD, "")
    var authentication: KustoAuthentication = null
    val keyVaultUri: String = parameters.getOrElse(KustoSourceOptions.KEY_VAULT_URI, "")
    var accessToken: String = ""
    var keyVaultAuthentication: Option[KeyVaultAuthentication] = None
    if (keyVaultUri != "") {
      // KeyVault Authentication
      val keyVaultAppId: String = parameters.getOrElse(KustoSourceOptions.KEY_VAULT_APP_ID, "")

      if (!keyVaultAppId.isEmpty) {
        keyVaultAuthentication = Some(KeyVaultAppAuthentication(keyVaultUri,
          keyVaultAppId,
          parameters.getOrElse(KustoSourceOptions.KEY_VAULT_APP_KEY, "")))
      } else {
        keyVaultAuthentication = Some(KeyVaultCertificateAuthentication(keyVaultUri,
          parameters.getOrElse(KustoDebugOptions.KEY_VAULT_PEM_FILE_PATH, ""),
          parameters.getOrElse(KustoDebugOptions.KEY_VAULT_CERTIFICATE_KEY, "")))
      }
    }

    if (!applicationId.isEmpty || !applicationKey.isEmpty) {
      authentication = AadApplicationAuthentication(applicationId, applicationKey, parameters.getOrElse(KustoSourceOptions.KUSTO_AAD_AUTHORITY_ID, "microsoft.com"))
    }
    else if ( {
      accessToken = parameters.getOrElse(KustoSourceOptions.KUSTO_ACCESS_TOKEN, "")
      !accessToken.isEmpty
    }) {
      authentication = KustoAccessTokenAuthentication(accessToken)
    } else if (keyVaultUri.isEmpty) {
      val token = DeviceAuthentication.acquireAccessTokenUsingDeviceCodeFlow(getClusterUrlFromAlias(cluster.get))
      authentication = KustoAccessTokenAuthentication(token)
    }

    SourceParameters(authentication, KustoCoordinates(getClusterNameFromUrlIfNeeded(cluster.get).toLowerCase(), database.get, table), keyVaultAuthentication)
  }

  case class SinkParameters(writeOptions: WriteOptions, sourceParametersResults: SourceParameters)

  case class SourceParameters(authenticationParameters: KustoAuthentication, kustoCoordinates: KustoCoordinates, keyVaultAuth: Option[KeyVaultAuthentication])

  def parseSinkParameters(parameters: Map[String, String], mode: SaveMode = SaveMode.Append): SinkParameters = {
    if (mode != SaveMode.Append) {
      throw new InvalidParameterException(s"Kusto data source supports only 'Append' mode, '$mode' directive is invalid. Please use df.write.mode(SaveMode.Append)..")
    }

    // Parse WriteOptions
    var tableCreation: SinkTableCreationMode = SinkTableCreationMode.FailIfNotExist
    var tableCreationParam: Option[String] = None
    var isAsync: Boolean = false
    var isAsyncParam: String = ""
    var batchLimit: Int = 0

    try {
      isAsyncParam = parameters.getOrElse(KustoSinkOptions.KUSTO_WRITE_ENABLE_ASYNC, "false")
      isAsync = parameters.getOrElse(KustoSinkOptions.KUSTO_WRITE_ENABLE_ASYNC, "false").trim.toBoolean
      tableCreationParam = parameters.get(KustoSinkOptions.KUSTO_TABLE_CREATE_OPTIONS)
      tableCreation = if (tableCreationParam.isEmpty) SinkTableCreationMode.FailIfNotExist else SinkTableCreationMode.withName(tableCreationParam.get)
      batchLimit = parameters.getOrElse(KustoSinkOptions.KUSTO_CLIENT_BATCHING_LIMIT, "100").trim.toInt
    } catch {
      case _: NoSuchElementException => throw new InvalidParameterException(s"No such SinkTableCreationMode option: '${tableCreationParam.get}'")
      case _: java.lang.IllegalArgumentException => throw new InvalidParameterException(s"KUSTO_WRITE_ENABLE_ASYNC is expecting either 'true' or 'false', got: '$isAsyncParam'")
    }

    val timeout = new FiniteDuration(parameters.getOrElse(KustoSinkOptions.KUSTO_TIMEOUT_LIMIT, KCONST.defaultWaitingIntervalLongRunning).toLong, TimeUnit.SECONDS)

    val ingestionPropertiesAsJson = parameters.get(KustoSinkOptions.KUSTO_SPARK_INGESTION_PROPERTIES_JSON)

    val writeOptions = WriteOptions(
      tableCreation,
      isAsync,
      parameters.getOrElse(KustoSinkOptions.KUSTO_WRITE_RESULT_LIMIT, "1"),
      parameters.getOrElse(DateTimeUtils.TIMEZONE_OPTION, "UTC"),
      timeout,
      ingestionPropertiesAsJson,
      batchLimit
    )

    val sourceParameters = parseSourceParameters(parameters)

    if (sourceParameters.kustoCoordinates.table.isEmpty) {
      throw new InvalidParameterException("KUSTO_TABLE parameter is missing. Must provide a destination table name")
    }

    logInfo("parseSinkParameters", s"Parsed write options for sink: {'timeout': ${writeOptions.timeout}, 'async': ${writeOptions.isAsync}, " +
      s"'tableCreationMode': ${writeOptions.tableCreateOptions}, 'writeLimit': ${writeOptions.writeResultLimit}, 'batchLimit': ${writeOptions.batchLimit}" +
      s", 'timeout': ${writeOptions.timeout}, 'timezone': ${writeOptions.timeZone}, 'ingestionProperties': $ingestionPropertiesAsJson}")

    SinkParameters(writeOptions, sourceParameters)
  }

  def getClientRequestProperties(parameters: Map[String, String]): Option[ClientRequestProperties] = {
    val crpOption = parameters.get(KustoSourceOptions.KUSTO_CLIENT_REQUEST_PROPERTIES_JSON)

    if (crpOption.isDefined) {
      val crp = ClientRequestProperties.fromString(crpOption.get)
      Some(crp)
    } else {
      None
    }
  }

  private[kusto] def reportExceptionAndThrow(
                                              reporter: String,
                                              exception: Exception,
                                              doingWhat: String = "",
                                              cluster: String = "",
                                              database: String = "",
                                              table: String = "",
                                              shouldNotThrow: Boolean = false): Unit = {
    val whatFailed = if (doingWhat.isEmpty) "" else s"when $doingWhat"
    val clusterDesc = if (cluster.isEmpty) "" else s", cluster: '$cluster' "
    val databaseDesc = if (database.isEmpty) "" else s", database: '$database'"
    val tableDesc = if (table.isEmpty) "" else s", table: '$table'"

    if (!shouldNotThrow) {
      logError(reporter, s"caught exception $whatFailed$clusterDesc$databaseDesc$tableDesc.${NewLine}EXCEPTION: ${ExceptionUtils.getStackTrace(exception)}")
      throw exception
    }

    logWarn(reporter, s"caught exception $whatFailed$clusterDesc$databaseDesc$tableDesc, exception ignored.${NewLine}EXCEPTION: ${ExceptionUtils.getStackTrace(exception)}")
  }

  private def getClusterNameFromUrlIfNeeded(url: String): String = {
    url match {
      case urlPattern(clusterAlias) => clusterAlias
      case _ => url
    }
  }

  private def getClusterUrlFromAlias(alias: String): String = {
    alias match {
      case urlPattern(_) => alias
      case _ => s"https://$alias.kusto.windows.net"
    }
  }

  /**
    * A function to run sequentially async work on TimerTask using a Timer.
    * The function passed is scheduled sequentially by the timer, until last calculated returned value by func does not
    * satisfy the condition of doWhile or a given number of times has passed.
    * After either this condition was satisfied or the 'numberOfTimesToRun' has passed (This can be avoided by setting
    * numberOfTimesToRun to a value less than 0), the finalWork function is called over the last returned value by func.
    * Returns a CountDownLatch object used to count down iterations and await on it synchronously if needed
    *
    * @param func               - the function to run
    * @param delayBeforeStart              - delay before first job
    * @param delayBeforeEach           - delay between jobs
    * @param timesToRun - stop jobs after numberOfTimesToRun.
    *                           set negative value to run infinitely
    * @param stopCondition            - stop jobs if condition holds for the func.apply output
    * @param finalWork          - do final work with the last func.apply output
    */
    def doWhile[A](func: () => A, delayBeforeStart: Long, delayBeforeEach: Int, timesToRun: Int, stopCondition: A => Boolean, finalWork: A => Unit): CountDownLatch = {
    val latch = new CountDownLatch(if (timesToRun > 0) timesToRun else 1)
    val t = new Timer()
    var currentWaitTime = delayBeforeEach

    class ExponentialBackoffTask extends TimerTask {
      def run(): Unit = {
        try {
          val res = func.apply()
          if (timesToRun > 0) {
            latch.countDown()
          }

          if (latch.getCount == 0) {
            throw new TimeoutException(s"runSequentially: timed out based on maximal allowed repetitions ($timesToRun), aborting")
          }

          if (!stopCondition.apply(res)) {
            finalWork.apply(res)
            while (latch.getCount > 0) latch.countDown()
          } else {
            currentWaitTime = if (currentWaitTime > MaxWaitTime.toMillis) currentWaitTime else currentWaitTime + currentWaitTime
            t.schedule(new ExponentialBackoffTask(), currentWaitTime)
          }
        } catch {
          case exception: Exception =>
            while (latch.getCount > 0) latch.countDown()
            throw exception
        }
      }
    }

    val task: TimerTask = new ExponentialBackoffTask()
    t.schedule(task, delayBeforeStart)
    latch
  }

  def verifyAsyncCommandCompletion(client: Client,
                                   database: String,
                                   commandResult: Results,
                                   samplePeriod: FiniteDuration = KCONST.defaultPeriodicSamplePeriod,
                                   timeOut: FiniteDuration): Unit = {
    val operationId = commandResult.getValues.get(0).get(0)
    val operationsShowCommand = CslCommandsGenerator.generateOperationsShowCommand(operationId)
    val sampleInMillis = samplePeriod.toMillis.toInt
    val timeoutInMillis = timeOut.toMillis
    val delayPeriodBetweenCalls = if (sampleInMillis < 1) 1 else sampleInMillis
    val timesToRun = if (timeOut < FiniteDuration.apply(0, SECONDS)) -1 else (timeoutInMillis / delayPeriodBetweenCalls + 5).toInt

    val stateCol = "State"
    val statusCol = "Status"
    val showCommandResult = client.execute(database, operationsShowCommand)
    val stateIdx = showCommandResult.getColumnNameToIndex.get(stateCol)
    val statusIdx = showCommandResult.getColumnNameToIndex.get(statusCol)

    var lastResponse: Option[util.ArrayList[String]] = None
    val task = doWhile[util.ArrayList[String]](
      func = () => client.execute(database, operationsShowCommand).getValues.get(0),
      delayBeforeStart = 0, delayBeforeEach = delayPeriodBetweenCalls, timesToRun = timesToRun,
      stopCondition = result => {
        result.get(stateIdx) == "InProgress"
      },
      finalWork = result => {
        lastResponse = Some(result)
      })
    var success = true
    if (timeOut < FiniteDuration.apply(0, SECONDS)) {
      task.await()
    } else {
      if (!task.await(timeoutInMillis, TimeUnit.SECONDS)) {
        // Timed out
        success = false
      }
    }

    if (lastResponse.isDefined && lastResponse.get.get(stateIdx) != "Completed") {
      throw new RuntimeException(
        s"Failed to execute Kusto operation with OperationId '$operationId', State: '${lastResponse.get.get(stateIdx)}'," +
          s" Status: '${lastResponse.get.get(statusIdx)}'"
      )
    }

    if (!success) {
      throw new RuntimeException(s"Timed out while waiting for operation with OperationId '$operationId'")
    }
  }

  private[kusto] def parseSas(url: String): KustoStorageParameters = {
    url match {
      case sasPattern(storageAccountId, container, sasKey) => KustoStorageParameters(storageAccountId, sasKey, container, secretIsAccountKey = false)
      case _ => throw new InvalidParameterException(
        "SAS url couldn't be parsed. Should be https://<storage-account>.blob.core.windows.net/<container>?<SAS-Token>"
      )
    }
  }

  private[kusto] def mergeKeyVaultAndOptionsAuthentication(paramsFromKeyVault: AadApplicationAuthentication,
                                                           authenticationParameters: Option[KustoAuthentication]): KustoAuthentication = {
    if (authenticationParameters.isEmpty) {
      // We have both keyVault and AAD application params, take from options first and throw if both are empty
      try {
        val app = authenticationParameters.asInstanceOf[AadApplicationAuthentication]
        AadApplicationAuthentication(
          ID = if (app.ID == "") {
            if (paramsFromKeyVault.ID == "") {
              throw new InvalidParameterException("AADApplication ID is empty. Please pass it in keyVault or options")
            }
            paramsFromKeyVault.ID
          } else {
            app.ID
          },
          password = if (app.password == "") {
            if (paramsFromKeyVault.password == "AADApplication key is empty. Please pass it in keyVault or options") {
              throw new InvalidParameterException("")
            }
            paramsFromKeyVault.password
          } else {
            app.password
          },
          authority = if (app.authority == "microsoft.com") paramsFromKeyVault.authority else app.authority
        )
      } catch {
        case _: ClassCastException => throw new UnsupportedOperationException("keyVault authentication can be combined only with AADAplicationAuthentication")
      }
    } else {
      paramsFromKeyVault
    }
  }

  private[kusto] def mergeKeyVaultAndOptionsStorageParams(storageAccount: Option[String],
                                                          storageContainer: Option[String],
                                                          storageSecret: Option[String],
                                                          storageSecretIsAccountKey: Boolean,
                                                          keyVaultAuthentication: KeyVaultAuthentication): Option[KustoStorageParameters] = {
    if (!storageSecretIsAccountKey) {
      // If SAS option defined - take sas
      Some(KustoDataSourceUtils.parseSas(storageSecret.get))
    } else {
      if (storageAccount.isEmpty || storageContainer.isEmpty || storageSecret.isEmpty) {
        val keyVaultParameters = KeyVaultUtils.getStorageParamsFromKeyVault(keyVaultAuthentication)
        // If KeyVault contains sas take it
        if (!keyVaultParameters.secretIsAccountKey) {
          Some(keyVaultParameters)
        } else {
          if ((storageAccount.isEmpty && keyVaultParameters.account.isEmpty) ||
            (storageContainer.isEmpty && keyVaultParameters.container.isEmpty) ||
            (storageSecret.isEmpty && keyVaultParameters.secret.isEmpty)) {

            // We don't have enough information to access blob storage
            None
          }
          else {
            // Try combine
            val account = if (storageAccount.isEmpty) Some(keyVaultParameters.account) else storageAccount
            val secret = if (storageSecret.isEmpty) Some(keyVaultParameters.secret) else storageSecret
            val container = if (storageContainer.isEmpty) Some(keyVaultParameters.container) else storageContainer

            getAndValidateTransientStorageParametersIfExist(account, container, secret, storageSecretIsAccountKey = true)
          }
        }
      } else {
        Some(KustoStorageParameters(storageAccount.get, storageSecret.get, storageContainer.get, storageSecretIsAccountKey))
      }
    }
  }

  private[kusto] def getAndValidateTransientStorageParametersIfExist(storageAccount: Option[String],
                                                                     storageContainer: Option[String],
                                                                     storageAccountSecret: Option[String],
                                                                     storageSecretIsAccountKey: Boolean): Option[KustoStorageParameters] = {

    val paramsFromSas = if (!storageSecretIsAccountKey && storageAccountSecret.isDefined) {
      if (storageAccountSecret.get == null) {
        throw new InvalidParameterException("storage secret from parameters is null")
      }
      Some(parseSas(storageAccountSecret.get))
    } else None

    if (paramsFromSas.isDefined) {
      if (storageAccount.isDefined && !storageAccount.get.equals(paramsFromSas.get.account)) {
        throw new InvalidParameterException("Storage account name does not match the name in storage access SAS key.")
      }

      if (storageContainer.isDefined && !storageContainer.get.equals(paramsFromSas.get.container)) {
        throw new InvalidParameterException("Storage container name does not match the name in storage access SAS key.")
      }

      paramsFromSas
    }
    else if (storageAccount.isDefined && storageContainer.isDefined && storageAccountSecret.isDefined) {
      if (storageAccount.get == null || storageAccountSecret.get == null || storageContainer.get == null) {
        throw new InvalidParameterException("storageAccount key from parameters is null")
      }
      Some(KustoStorageParameters(storageAccount.get, storageAccountSecret.get, storageContainer.get, storageSecretIsAccountKey))
    }
    else None
  }

  private[kusto] def countRows(client: Client, query: String, database: String): Int = {
    client.execute(database, generateCountQuery(query)).getValues.get(0).get(0).toInt
  }

  private[kusto] def estimateRowsCount(client: Client, query: String, database: String): Int = {
    var count = 0
    val estimationResult: util.ArrayList[String] = Await.result(Future(
      client.execute(database, generateEstimateRowsCountQuery(query)).getValues.get(0)), KustoConstants.timeoutForCountCheck)
    if(StringUtils.isBlank(estimationResult.get(1))){
      // Estimation can be empty for certain cases
      val justCountResult = Await.result(Future(
        client.execute(database, generateCountQuery(query)).getValues.get(0)), KustoConstants.timeoutForCountCheck)
      count = justCountResult.get(0).toInt
    } else {
      // Zero estimation count does not indicate zero results, therefore we add 1 here so that we won't return an empty RDD
      count = estimationResult.get(1).toInt + 1
    }

    count
  }
}
