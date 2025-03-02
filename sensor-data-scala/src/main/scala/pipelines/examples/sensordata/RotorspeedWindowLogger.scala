package pipelines.examples.sensordata

import pipelines.akkastream._
import pipelines.akkastream.scaladsl._
import pipelines.streamlets._
import pipelines.streamlets.avro._

class RotorspeedWindowLogger extends AkkaStreamlet {
  val in = AvroInlet[Metric]("in")
  val shape = StreamletShape(in)
  override def createLogic = new RunnableGraphStreamletLogic() {
    def runnableGraph = sourceWithOffsetContext(in).via(flow).to(sinkWithOffsetContext)
    def flow = {
      FlowWithOffsetContext[Metric]
        .grouped(5)
        .map { rotorSpeedWindow ⇒
          val (avg, _) = rotorSpeedWindow.map(_.value).foldLeft((0.0, 1)) { case ((avg, idx), next) ⇒ (avg + (next - avg) / idx, idx + 1) }

          system.log.info(s"Average rotorspeed is: $avg")

          avg
        }
        .mapContext(_.last) // TODO: this is a tricky one to understand...
    }
  }
}
