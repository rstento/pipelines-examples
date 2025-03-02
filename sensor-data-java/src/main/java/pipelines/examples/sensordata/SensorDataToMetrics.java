package pipelines.examples.sensordata;

import java.util.Arrays;

import akka.stream.javadsl.*;
import akka.kafka.ConsumerMessage.CommittableOffset;

import akka.NotUsed;

import pipelines.streamlets.*;
import pipelines.streamlets.avro.*;
import pipelines.akkastream.*;
import pipelines.akkastream.javadsl.*;

public class SensorDataToMetrics extends AkkaStreamlet {
  AvroInlet<SensorData> in = AvroInlet.<SensorData>create("in", SensorData.class);
  AvroOutlet<Metric> out = AvroOutlet.<Metric>create("out", Metric.class)
          .withPartitioner(RoundRobinPartitioner.getInstance());

  public StreamletShape shape() {
   return StreamletShape.createWithInlets(in).withOutlets(out);
  }
  
  private FlowWithContext<SensorData,CommittableOffset,Metric,CommittableOffset,NotUsed> flowWithContext() {
    return FlowWithOffsetContext.<SensorData>create()
      .mapConcat(data ->
        Arrays.asList(
          new Metric(data.getDeviceId(), data.getTimestamp(), "power", data.getMeasurements().getPower()),
          new Metric(data.getDeviceId(), data.getTimestamp(), "rotorSpeed", data.getMeasurements().getRotorSpeed()),
          new Metric(data.getDeviceId(), data.getTimestamp(), "windSpeed", data.getMeasurements().getWindSpeed())
        )
      );
  }

  public StreamletLogic createLogic() {
    return new RunnableGraphStreamletLogic(getStreamletContext()) {
      public RunnableGraph createRunnableGraph() {
        return getSourceWithOffsetContext(in).via(flowWithContext()).to(getSinkWithOffsetContext(out));
      }
    };
  }
}
