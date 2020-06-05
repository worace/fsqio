package io.fsq.twofishes.indexer.importers.geonames

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.routing.{Broadcast, RoundRobinPool}
import com.google.common.geometry.S2CellId
import com.twitter.ostrich.stats.Stats
import org.locationtech.jts.geom.{Point => JTSPoint}
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.io.{WKBReader, WKBWriter}
import io.fsq.twofishes.indexer.mongo.{IndexerQueryExecutor, RevGeoIndex}
import io.fsq.twofishes.indexer.mongo.RogueImplicits._
import io.fsq.twofishes.model.gen.{ThriftPolygonIndex, ThriftS2CoveringIndex, ThriftS2InteriorIndex}
import io.fsq.twofishes.util.{
  DurationUtils,
  GeometryCleanupUtils,
  GeometryUtils,
  RevGeoConstants,
  S2CoveringConstants,
  ShapefileS2Util
}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.bson.types.ObjectId
import org.slf4s.Logging
import scala.collection.JavaConverters._

// ====================
// ===== Messages =====
// ====================
case class CoverOptions(
  forS2CoveringIndex: Boolean = true,
  forS2InteriorIndex: Boolean = true,
  forRevGeoIndex: Boolean = true
)
sealed trait CoverMessage
case class Done() extends CoverMessage
case class CalculateCoverFromMongo(polyIds: List[ObjectId], options: CoverOptions) extends CoverMessage
case class CalculateCover(polyId: ObjectId, geomBytes: Array[Byte], options: CoverOptions) extends CoverMessage
case class FinishedCover() extends CoverMessage

class NullActor extends Actor {
  def receive = {
    case x =>
  }
}

object GlobalCounter {
  val count = new AtomicInteger
}

class S2CoveringWorker extends Actor with DurationUtils with RevGeoConstants with S2CoveringConstants with Logging {
  val wkbReader = new WKBReader()
  val wkbWriter = new WKBWriter()

  lazy val executor = IndexerQueryExecutor.instance

  def calculateCoverFromMongo(msg: CalculateCoverFromMongo) {
    val records = executor.fetch(Q(ThriftPolygonIndex).where(_.id in msg.polyIds))
    records.foreach(p => calculateCover(p.id, p.polygonOrThrow.array(), msg.options))
  }

  def calculateCover(msg: CalculateCover) {
    calculateCover(msg.polyId, msg.geomBytes, msg.options)
  }

  def calculateCover(polyId: ObjectId, geomBytes: Array[Byte], options: CoverOptions) {
    logDuration("totalCovering", "generated cover for %s".format(polyId)) {
      val currentCount = GlobalCounter.count.getAndIncrement()

      if (currentCount % 1000 == 0) {
        log.info("processed about %s polygons for s2 coverage".format(currentCount))
      }

      val geom = wkbReader.read(geomBytes)

      if (options.forS2CoveringIndex) {
        // println("generating cover for %s for s2covering index".format(polyId))
        val cells =
          logDuration("s2CoveringForS2CoveringIndex", "generated cover for %s for s2covering index".format(polyId)) {
            GeometryUtils
              .s2PolygonCovering(
                geom,
                minS2LevelForS2Covering,
                maxS2LevelForS2Covering,
                levelMod = Some(defaultLevelModForS2Covering),
                maxCellsHintWhichMightBeIgnored = Some(defaultMaxCellsHintForS2Covering)
              )
              .toList
          }

        val record = ThriftS2CoveringIndex(polyId, cells.map(_.id()))
        executor.insert(record)
      }

      if (options.forS2InteriorIndex) {
        // println("generating cover for %s for s2interior index".format(polyId))
        val cells = logDuration(
          "s2InteriorForS2InteriorIndex",
          "generated cover for %s for s2interior index".format(polyId)
        ) {
          GeometryUtils
            .s2PolygonCovering(
              geomCollection = geom,
              minS2Level = minS2LevelForS2Interior,
              maxS2Level = maxS2LevelForS2Interior,
              levelMod = Some(defaultLevelModForS2Covering),
              maxCellsHintWhichMightBeIgnored = Some(defaultMaxCellsHintForS2Interior),
              interior = true
            )
            .toList
        }

        val record = ThriftS2InteriorIndex(polyId, cells.map(_.id()))
        executor.insert(record)
      }

      if (options.forRevGeoIndex) {
        // println("generating cover for %s for revgeo index".format(polyId))
        val cells = logDuration("s2CoveringForRevGeoIndex", "generated cover for %s for revgeo index".format(polyId)) {
          GeometryUtils.s2PolygonCovering(
            geom,
            minS2LevelForRevGeo,
            maxS2LevelForRevGeo,
            levelMod = Some(defaultLevelModForRevGeo),
            maxCellsHintWhichMightBeIgnored = Some(defaultMaxCellsHintForRevGeo)
          )
        }

        logDuration(
          "coverClippingForRevGeoIndex",
          "clipped and outputted cover for %d cells (%s) for revgeo index".format(cells.size, polyId)
        ) {
          val recordShape = geom.buffer(0)
          val preparedRecordShape = PreparedGeometryFactory.prepare(recordShape)
          val records = cells.map((cellid: S2CellId) => {
            if (geom.isInstanceOf[JTSPoint]) {
              RevGeoIndex(
                cellid.id(),
                polyId,
                full = false,
                geom = Some(wkbWriter.write(geom))
              )
            } else {
              val s2shape = ShapefileS2Util.fullGeometryForCell(cellid)
              if (preparedRecordShape.contains(s2shape)) {
                RevGeoIndex(cellid.id(), polyId, full = true, geom = None)
              } else if (preparedRecordShape.within(s2shape)) {
                RevGeoIndex(
                  cellid.id(),
                  polyId,
                  full = false,
                  geom = Some(wkbWriter.write(geom))
                )
              } else {
                val intersection = s2shape.intersection(recordShape)
                val geomToIndex = if (intersection.getGeometryType == "GeometryCollection") {
                  GeometryCleanupUtils.cleanupGeometryCollection(intersection)
                } else {
                  intersection
                }
                RevGeoIndex(
                  cellid.id(),
                  polyId,
                  full = false,
                  geom = Some(wkbWriter.write(geomToIndex))
                )
              }
            }
          })
          records.foreach(r => executor.insert(r))
        }
      }
    }
  }

  def receive = {
    case msg: CalculateCover =>
      calculateCover(msg)
      sender ! FinishedCover()
    case msg: CalculateCoverFromMongo =>
      calculateCoverFromMongo(msg)
      sender ! FinishedCover()
  }
}

// ==================
// ===== Master =====
// ==================
class S2CoveringMaster(val latch: CountDownLatch) extends Actor with Logging {
  var start: Long = 0

  val _system = ActorSystem("RoundRobinRouterExample")
  val numThreads = Runtime.getRuntime.availableProcessors
  val router = _system.actorOf(
    Props[S2CoveringWorker].withRouter(new RoundRobinPool(numThreads)),
    name = "myRoundRobinRouterActor"
  )
  var inFlight = 0
  var seenDone = false

  // message handler
  def receive = {
    case msg: FinishedCover =>
      inFlight -= 1
      if (inFlight == 0 && seenDone) {
        shutdownWithMessage("finished all s2 covers, shutting down system")
      }
      if (inFlight < 0) {
        log.error("inFlight < 0 ... we're bad at a counting")
      }
    case msg: CalculateCover =>
      Stats.incr("s2.akkaWorkers.CalculateCover")
      inFlight += 1
      router ! msg
    case msg: CalculateCoverFromMongo =>
      inFlight += 1
      router ! msg
    case msg: Done =>
      log.info("all done with s2 cover indexing, sending poison pills")
      // send a PoisonPill to all workers telling them to shut down themselves
      router ! Broadcast(PoisonPill)
      seenDone = true
      if (inFlight == 0) {
        shutdownWithMessage("had already finished all s2 covers, shutting down system")
      }
  }

  private def shutdownWithMessage(message: String): Unit = {
    log.info(message)
    latch.countDown()
    self ! PoisonPill
  }

  override def preStart() {
    start = System.currentTimeMillis
  }

  override def postStop() {
    // tell the world that the calculation is complete
    log.info(
      "s2 covering calculation time: \t%s millis"
        .format((System.currentTimeMillis - start))
    )
  }
}
