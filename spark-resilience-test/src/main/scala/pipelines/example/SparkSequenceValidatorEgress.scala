package pipelines.example

import scala.collection.immutable.Seq

import org.apache.spark.sql.Dataset
import java.sql.Timestamp

import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.streaming._
import org.apache.spark.sql.functions._

import pipelines.streamlets.StreamletShape
import pipelines.streamlets.avro._
import pipelines.spark.{ SparkStreamlet, SparkStreamletLogic, StreamletQueryExecution }
import pipelines.spark.sql.SQLImplicits._

case class DataGroup(key: Long, groupSize: Int, receivedData: Seq[Long] = Seq.empty, timedOut: Boolean = false) {
  def expectedSet = (key.toInt * groupSize until (key.toInt + 1) * groupSize).map(_.toLong).toSet
  def isComplete: Boolean = receivedData.toSet.size == groupSize
  def missing: Set[Long] = expectedSet -- receivedData.toSet
  def missingReport: String = {
    def ranges(list: List[Long]): List[(Long, Long)] = {
      def innerRanges(first: Long, current: Long, rest: List[Long]): List[(Long, Long)] = {
        rest match {
          case Nil         ⇒ List((first, current))
          case head :: Nil ⇒ List((first, head))
          case head :: tail ⇒
            if (head == current + 1) {
              innerRanges(first, head, tail)
            } else {
              (first, current) :: innerRanges(head, head, tail)
            }
        }
      }
      if (list.nonEmpty) {
        innerRanges(list.head, list.head, list.tail)
      } else Nil
    }
    val miss = missing.toList.sorted
    ranges(miss).mkString(",")
  }
  def report: String = {
    def boolToYesNo(bool: Boolean) = if (bool) "yes" else "no"
    s"key [$key]: timeout=[${boolToYesNo(timedOut)}] complete=[${boolToYesNo(isComplete)}]" + missingReport
  }
}

case class TimestampedData(timestamp: Timestamp, key: Long, value: Long)
object TimestampedData {
  def fromData(d: Data): TimestampedData = TimestampedData(new Timestamp(d.timestamp * 1000), d.key, d.value)
}

object StateFunction extends Serializable {
  def flatMappingFunction(
      key: Long,
      values: Iterator[TimestampedData],
      state: GroupState[DataGroup]
  ): Iterator[DataGroup] = {

    // first, check for timeout
    val result = if (state.hasTimedOut) {
      state.getOption match {
        case Some(st) ⇒
          state.remove()
          Seq(st.copy(timedOut = true))
        case None ⇒
          Seq(DataGroup(key, SequenceSettings.GroupSize, Seq(), timedOut = true))
      }
    } else {
      // get existing or create a new state payload
      val currentState: DataGroup = state.getOption
        .getOrElse {
          // new state
          state.setTimeoutDuration(SequenceSettings.TimeoutDuration)
          DataGroup(key, SequenceSettings.GroupSize)
        }
      // enrich the state with the new events
      val receivedValues = values.map(data ⇒ data.value)
      val updatedState = currentState.copy(receivedData = currentState.receivedData ++ receivedValues)

      if (updatedState.isComplete) {
        state.remove()
        Seq(updatedState)
      } else {
        state.update(updatedState)
        Seq.empty[DataGroup]
      }
    }
    result.toIterator
  }
}

class SparkSequenceValidatorEgress extends SparkStreamlet {
  val in = AvroInlet[Data]("in")
  val shape = StreamletShape(in)

  override def createLogic() = new SparkStreamletLogic() {
    override def buildStreamingQueries = {
      process(readStream(in))
    }

    def process(inDataset: Dataset[Data]): StreamletQueryExecution = {

      val keyedData = inDataset.map(TimestampedData.fromData)
        .withWatermark("timestamp", "30 seconds")
        .groupByKey(tsData ⇒ tsData.key)

      val incompleteDataEvents = keyedData.flatMapGroupsWithState(
        OutputMode.Append(), GroupStateTimeout.ProcessingTimeTimeout
      )(StateFunction.flatMappingFunction)

      val eventCount = incompleteDataEvents.map(dataGroup ⇒
        if (dataGroup.isComplete) ("complete", 1L) else ("incomplete", 1L)
      ).toDF("status", "count")
      val stats = eventCount.groupBy($"status").agg(sum($"count"))

      val q1 = incompleteDataEvents
        .map { dg ⇒ dg.report }
        .writeStream
        .format("console")
        .option("truncate", false)
        .option("checkpointLocation", context.checkpointDir("q1"))
        .queryName("incomplete-events")
        .outputMode(OutputMode.Append())
        .start()

      val q2 = stats.writeStream
        .format("console")
        .option("checkpointLocation", context.checkpointDir("q2"))
        .outputMode(OutputMode.Complete())
        .queryName("stats")
        .start()

      StreamletQueryExecution(q1, q2)
    }
  }
}
