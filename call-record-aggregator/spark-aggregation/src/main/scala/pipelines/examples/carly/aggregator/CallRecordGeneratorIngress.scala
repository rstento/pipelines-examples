package pipelines.examples.carly.aggregator

import java.sql.Timestamp

import scala.util.Random
import scala.concurrent.duration._

import org.apache.spark.sql.{ Dataset, SparkSession }
import org.apache.spark.sql.streaming.OutputMode

import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.LongType

import pipelines.streamlets._
import pipelines.streamlets.avro._
import pipelines.spark.sql.SQLImplicits._
import pipelines.examples.carly.data.CallRecord
import pipelines.spark.{ SparkStreamlet, SparkStreamletLogic }
import org.apache.log4j.{ Level, Logger }

case class Rate(timestamp: Timestamp, value: Long)

class CallRecordGeneratorIngress extends SparkStreamlet {

  val rootLogger = Logger.getRootLogger()
  rootLogger.setLevel(Level.ERROR)

  val RecordsPerSecond = IntegerConfigParameter(
    "records-per-second",
    "Records per second to process.",
    Some(50))

  override def configParameters = Vector(RecordsPerSecond)

  val out = AvroOutlet[CallRecord]("out", _.user)
  val shape = StreamletShape(out)

  override def createLogic() = new SparkStreamletLogic {
    val recordsPerSecond = context.streamletConfig.getInt(RecordsPerSecond.key)
    override def buildStreamingQueries = {
      val outStream = DataGenerator.mkData(super.session, recordsPerSecond)
      writeStream(outStream, out, OutputMode.Append).toQueryExecution
    }
  }
}

object DataGenerator {
  def mkData(session: SparkSession, recordsPerSecond: Int): Dataset[CallRecord] = {
    // do we need to expose this through configuration?

    val MaxTime = 2.hours.toMillis
    val MaxUsers = 100000
    val TS0 = new java.sql.Timestamp(0)
    val ZeroTimestampProb = 0.05 // error rate

    // Random Data Generator
    val usersUdf = udf(() ⇒ "user-" + Random.nextInt(MaxUsers))
    val directionUdf = udf(() ⇒ if (Random.nextDouble() < 0.5) "incoming" else "outgoing")

    // Time-biased randomized filter - 1/2 hour cycles
    val sinTime: Long ⇒ Double = t ⇒ Math.sin((t / 1000 % 1800) * 1.0 / 1800 * Math.PI)
    val timeBoundFilter: Long ⇒ Double ⇒ Boolean = t ⇒ prob ⇒ (sinTime(t) + 0.5) > prob
    val timeFilterUdf = udf((ts: java.sql.Timestamp, rng: Double) ⇒ timeBoundFilter(ts.getTime)(rng))
    val zeroTimestampUdf = udf((ts: java.sql.Timestamp, rng: Double) ⇒ {
      if (rng < ZeroTimestampProb) {
        TS0
      } else {
        ts
      }
    })

    val rateStream = session.readStream
      .format("rate")
      .option("rowsPerSecond", recordsPerSecond)
      .load()
      .as[Rate]

    val randomDataset = rateStream.withColumn("rng", rand()).withColumn("tsRng", rand())
    val sampledData = randomDataset.where(timeFilterUdf($"timestamp", $"rng"))
      .withColumn("user", usersUdf())
      .withColumn("other", usersUdf())
      .withColumn("direction", directionUdf())
      .withColumn("duration", (round(abs(rand()) * MaxTime)).cast(LongType))
      .withColumn("updatedTimestamp", zeroTimestampUdf($"timestamp", $"tsRng"))
      .select($"user", $"other", $"direction", $"duration", $"updatedTimestamp" as "timestamp")
      .as[CallRecord]
    sampledData
  }
}

