package intelmedia.ws.monitoring

import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.ExecutorService
import collection.JavaConversions._
import scala.concurrent.duration._
import scalaz.concurrent.Strategy
import scalaz.stream._

/** Functions for adding various JVM metrics to a `Monitoring` instance. */
object JVM {

  /**
   * Add various JVM metrics to a `Monitoring` instance.
   */
  def instrument(I: Instruments)(implicit ES: ExecutorService = Monitoring.defaultPool): Unit = {
    val mxBean = ManagementFactory.getMemoryMXBean
    val gcs = ManagementFactory.getGarbageCollectorMXBeans.toList
    val pools = ManagementFactory.getMemoryPoolMXBeans.toList
    import I._
    gcs.foreach { gc =>
      val name = gc.getName.replace(' ', '-')
      val numCollections = numericGauge(s"jvm/gc/$name", 0)
      val collectionTime = numericGauge(s"jvm/gc/$name/time", 0)
      Strategy.Executor(ES) {
        Process.awakeEvery(3 seconds).map { _ =>
          numCollections.set(gc.getCollectionCount)
          collectionTime.set(gc.getCollectionTime.toDouble)
        }.run.run
      }
    }

    def MB(lbl: String): Gauge[Periodic[Stats], Double] =
      Gauge.scale(1/1e6)(numericGauge(lbl, 0.0))

    val totalInit = MB("jvm/memory/total/init")
    val totalUsed = MB("jvm/memory/total/used")
    val totalMax = MB("jvm/memory/total/max")
    val totalCommitted = MB("jvm/memory/total/committed")

    val heapInit = MB("jvm/memory/heap/init")
    val heapUsed = MB("jvm/memory/heap/used")
    val heapUsage = numericGauge("jvm/memory/heap/usage", 0.0)
    val heapMax = MB("jvm/memory/heap/max")
    val heapCommitted = MB("jvm/memory/heap/committed")

    val nonheapInit = MB("jvm/memory/nonheap/init")
    val nonheapUsed = MB("jvm/memory/nonheap/used")
    val nonheapUsage = numericGauge("jvm/memory/nonheap/usage", 0.0)
    val nonheapMax = MB("jvm/memory/nonheap/max")
    val nonheapCommitted = MB("jvm/memory/nonheap/committed")

    Strategy.Executor(ES) {
      Process.awakeEvery(3 seconds).map { _ =>
        import mxBean.{getHeapMemoryUsage => heap, getNonHeapMemoryUsage => nonheap}
        totalInit.set(heap.getInit + nonheap.getInit)
        totalUsed.set(heap.getUsed + nonheap.getUsed)
        totalMax.set(heap.getMax + nonheap.getMax)
        totalCommitted.set(heap.getCommitted + nonheap.getCommitted)
        heapInit.set(heap.getInit)
        heapUsed.set(heap.getUsed)
        heapUsage.set(heap.getUsed.toDouble / heap.getMax)
        heapMax.set(heap.getMax)
        heapCommitted.set(heap.getCommitted)
        nonheapInit.set(nonheap.getInit)
        nonheapUsed.set(nonheap.getUsed)
        nonheapUsage.set(nonheap.getUsed.toDouble / nonheap.getMax)
        nonheapMax.set(nonheap.getMax)
        nonheapCommitted.set(nonheap.getCommitted)
      }.run.run
    }
  }
}