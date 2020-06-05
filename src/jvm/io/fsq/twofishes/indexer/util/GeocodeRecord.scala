// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.
package io.fsq.twofishes.indexer.util

import org.locationtech.jts.geom.{Coordinate, GeometryFactory}
import org.locationtech.jts.io.WKBReader
import io.fsq.common.scala.Identity._
import io.fsq.common.scala.Lists.Implicits._
import io.fsq.twofishes.core.YahooWoeTypes
import io.fsq.twofishes.gen._
import io.fsq.twofishes.model.gen.{
  ThriftBoundingBox,
  ThriftBoundingBoxProxy,
  ThriftDisplayName,
  ThriftDisplayNameProxy,
  ThriftGeocodeRecord,
  ThriftGeocodeRecordProxy,
  ThriftPoint,
  ThriftPointProxy
}
import io.fsq.twofishes.util.StoredFeatureId
import java.nio.ByteBuffer
import org.apache.thrift.{TDeserializer, TSerializer}
import org.apache.thrift.protocol.TCompactProtocol
import org.bson.types.ObjectId

class DisplayName(override val underlying: ThriftDisplayName) extends ThriftDisplayNameProxy {
  // I did this for porting convenience
  def name: String = nameOption.getOrElse("")
  def lang: String = langOption.getOrElse("")
}

object DisplayName {
  def apply(
    lang: String,
    name: String,
    flags: Int = 0,
    id: ObjectId = new ObjectId()
  ): DisplayName = {
    new DisplayName(
      ThriftDisplayName.newBuilder
        .lang(lang)
        .name(name)
        .flags(flags)
        .id(id)
        .result()
    )
  }
}

class Point(override val underlying: ThriftPoint) extends ThriftPointProxy

object Point {
  def apply(lat: Double, lng: Double): ThriftPoint = {
    new Point(
      ThriftPoint.newBuilder
        .lat(lat)
        .lng(lng)
        .result()
    )
  }
}

class BoundingBox(override val underlying: ThriftBoundingBox) extends ThriftBoundingBoxProxy

object BoundingBox {
  def apply(
    ne: ThriftPoint,
    sw: ThriftPoint
  ): BoundingBox = {
    new BoundingBox(
      ThriftBoundingBox.newBuilder
        .ne(ne)
        .sw(sw)
        .result()
    )
  }
}

object GeoJsonPoint {
  def apply(lat: Double, lng: Double): GeoJsonPoint =
    GeoJsonPoint(coordinates = List(lng, lat))
  val NilPoint = GeoJsonPoint("Point", List(0.0, 0.0))
}

case class GeoJsonPoint(
  `type`: String = "Point",
  coordinates: List[Double]
)

object GeocodeRecord {
  val dummyOid = new ObjectId()

  private[util] val deserializer = new ThreadLocal[TDeserializer] {
    override def initialValue = new TDeserializer(new TCompactProtocol.Factory())
  }

  private[util] val serializer = new ThreadLocal[TSerializer] {
    override def initialValue = new TSerializer(new TCompactProtocol.Factory())
  }

  def apply(
    id: Long,
    names: Seq[String],
    cc: String,
    woeType: Int,
    lat: Double,
    lng: Double,
    displayNames: Seq[ThriftDisplayName] = Vector.empty,
    parents: Seq[Long] = Vector.empty,
    population: Option[Int] = None,
    boost: Option[Int] = None,
    boundingBox: Option[ThriftBoundingBox] = None,
    displayBounds: Option[ThriftBoundingBox] = None,
    canGeocode: Boolean = true,
    slug: Option[String] = None,
    polygon: Option[Array[Byte]] = None,
    hasPoly: Boolean = false,
    attributes: Option[Array[Byte]] = None,
    extraRelations: Seq[Long] = Vector.empty,
    polyId: ObjectId = GeocodeRecord.dummyOid,
    ids: Seq[Long] = Vector.empty,
    polygonSource: Option[String] = None
  ): GeocodeRecord = {
    val thriftModel = ThriftGeocodeRecord.newBuilder
      .id(id)
      .names(names)
      .cc(cc)
      .woeType(YahooWoeType.findById(woeType).getOrElse(throw new Exception(s"Unknown YahooWoeType ID: ${woeType}")))
      .lat(lat)
      .lng(lng)
      .displayNames(displayNames)
      .parents(parents)
      .population(population)
      .boost(boost)
      .boundingBox(boundingBox)
      .displayBounds(displayBounds)
      .canGeocode(canGeocode)
      .slug(slug)
      .polygon(polygon.map(ByteBuffer.wrap))
      .hasPoly(hasPoly)
      .rawAttributes(attributes.map(ByteBuffer.wrap))
      .extraRelations(extraRelations)
      .polyId(polyId)
      .ids(ids)
      .polygonSource(polygonSource)
      .result()
    new GeocodeRecord(thriftModel)
  }
}

class GeocodeRecord(
  override val underlying: ThriftGeocodeRecord
) extends ThriftGeocodeRecordProxy {
  lazy val attributes: Option[GeocodeFeatureAttributes] = {
    rawAttributesOption.map(byteBuffer => {
      val attr = new RawGeocodeFeatureAttributes()
      GeocodeRecord.deserializer.get.deserialize(attr, byteBuffer.array())
      attr
    })
  }

  def withAttributes(attr: Option[GeocodeFeatureAttributes]): GeocodeRecord = {
    val serializedAttributesOpt = attr.map(a => ByteBuffer.wrap(GeocodeRecord.serializer.get.serialize(a)))
    new GeocodeRecord(underlying.toBuilder.rawAttributes(serializedAttributesOpt).result())
  }

  def featureId: StoredFeatureId = StoredFeatureId
    .fromLong(id)
    .getOrElse(throw new RuntimeException("can't convert %s to a StoredFeatureId".format(id)))

  def allIds = (List(id) ++ ids).distinct
  def featureIds: List[StoredFeatureId] = allIds.flatMap(StoredFeatureId.fromLong)

  def parentFeatureIds: Seq[StoredFeatureId] = parents.flatMap(StoredFeatureId.fromLong _)

  def center = {
    val geomFactory = new GeometryFactory()
    geomFactory.createPoint(new Coordinate(lng, lat))
  }

  // Note this overrides from the Spindle proxy, which is why `that` is a ThriftGeocodeRecord.
  override def compare(that: ThriftGeocodeRecord): Int = {
    YahooWoeTypes.getOrdering(woeTypeOrThrow) - YahooWoeTypes.getOrdering(that.woeTypeOrThrow)
  }

  def fixRomanianName(s: String) = {
    val romanianTranslationTable = List(
      // cedilla -> comma
      "Ţ" -> "Ț",
      "Ş" -> "Ș",
      // tilde and caron to breve
      "Ã" -> "Ă",
      "Ǎ" -> "Ă"
    ).flatMap({
      case (from, to) => {
        List(
          from.toLowerCase -> to.toLowerCase,
          from.toUpperCase -> to.toUpperCase
        )
      }
    })

    var newS = s
    romanianTranslationTable.foreach({
      case (from, to) => {
        newS = newS.replace(from, to)
      }
    })
    newS
  }

  def toGeocodeServingFeature(): GeocodeServingFeature = {
    // geom
    val geometryBuilder = FeatureGeometry.newBuilder
      .center(GeocodePoint(lat, lng))
      .source(polygonSourceOption)

    if (!polygonIsSet) {
      boundingBoxOption.foreach(bounds => {
        val currentBounds = (bounds.neOrThrow.lat, bounds.neOrThrow.lng, bounds.swOrThrow.lat, bounds.swOrThrow.lng)

        // This breaks at 180, I get that, to fix.
        val finalBounds = (
          List(bounds.neOrThrow.lat, bounds.swOrThrow.lat).max,
          List(bounds.neOrThrow.lng, bounds.swOrThrow.lng).max,
          List(bounds.neOrThrow.lat, bounds.swOrThrow.lat).min,
          List(bounds.neOrThrow.lng, bounds.swOrThrow.lng).min
        )

        if (finalBounds !=? currentBounds) {
          println("incorrect bounds %s -> %s".format(currentBounds, finalBounds))
        }

        geometryBuilder.bounds(
          GeocodeBoundingBox(
            GeocodePoint(finalBounds._1, finalBounds._2),
            GeocodePoint(finalBounds._3, finalBounds._4)
          )
        )
      })
    }

    displayBoundsOption.foreach(bounds => {
      val currentBounds = (bounds.neOrThrow.lat, bounds.neOrThrow.lng, bounds.swOrThrow.lat, bounds.swOrThrow.lng)

      // This breaks at 180, I get that, to fix.
      val finalBounds = (
        List(bounds.neOrThrow.lat, bounds.swOrThrow.lat).max,
        List(bounds.neOrThrow.lng, bounds.swOrThrow.lng).max,
        List(bounds.neOrThrow.lat, bounds.swOrThrow.lat).min,
        List(bounds.neOrThrow.lng, bounds.swOrThrow.lng).min
      )

      if (finalBounds !=? currentBounds) {
        println("incorrect bounds %s -> %s".format(currentBounds, finalBounds))
      }

      geometryBuilder.displayBounds(
        GeocodeBoundingBox(
          GeocodePoint(finalBounds._1, finalBounds._2),
          GeocodePoint(finalBounds._3, finalBounds._4)
        )
      )
    })

    polygonOption.foreach(poly => {
      geometryBuilder.wkbGeometry(poly)

      val wkbReader = new WKBReader()
      val g = wkbReader.read(poly.array())

      val envelope = g.getEnvelopeInternal()

      geometryBuilder.bounds(
        GeocodeBoundingBox(
          GeocodePoint(envelope.getMaxY(), envelope.getMaxX()),
          GeocodePoint(envelope.getMinY(), envelope.getMinX())
        )
      )
    })

    val allNames: Seq[ThriftDisplayName] = {
      displayNames.filterNot(n => n.langOption.has("post") || n.langOption.has("link"))
    }

    val nameCandidates = allNames.map(name => {
      var flags: List[FeatureNameFlags] = Nil
      if (name.langOption.has("abbr")) {
        flags ::= FeatureNameFlags.ABBREVIATION
      }

      FeatureNameFlags.values.foreach(v => {
        if ((v.getValue() & name.flags) > 0) {
          flags ::= v
        }
      })

      FeatureName.newBuilder
        .name(name.nameOrThrow)
        .lang(name.langOrThrow)
        .applyIf(flags.nonEmpty, _.flags(flags))
        .result()
    })

    var finalNames = nameCandidates
      .groupBy(n => "%s%s".format(n.lang, n.name))
      .flatMap({
        case (k, values) => {
          var allFlags = values.flatMap(_.flags)

          // If we collapsed multiple names, and not all of them had ALIAS,
          // then we should strip off that flag because some other entry told
          // us it didn't deserve to be ranked down
          if (values.size > 1 && values.exists(n => !n.flags.has(FeatureNameFlags.ALIAS))) {
            allFlags = allFlags.filterNot(_ =? FeatureNameFlags.ALIAS)
          }

          values.headOption.map(_.copy(flags = allFlags.distinct))
        }
      })
      .map(n => {
        if (n.lang =? "ro") {
          n.copy(
            name = fixRomanianName(n.name)
          )
        } else {
          n
        }
      })

    // Lately geonames has these stupid JP aliases, like "Katsuura Gun" for "Katsuura-gun"
    if (ccOption.has("JP") || ccOption.has("TH")) {
      def isPossiblyBad(s: String): Boolean = {
        s.contains(" ") && s.split(" ").forall(_.headOption.exists(Character.isUpperCase))
      }

      def makeBetterName(s: String): String = {
        val parts = s.split(" ")
        val head = parts.headOption
        val rest = parts.drop(1)
        (head.toList ++ rest.map(_.toLowerCase)).mkString("-")
      }

      val enNames = finalNames.filter(_.lang =? "en")
      enNames.foreach(n => {
        if (isPossiblyBad(n.name)) {
          finalNames = finalNames.filterNot(_.name =? makeBetterName(n.name))
        }
      })
    }

    val feature = GeocodeFeature.newBuilder
      .cc(ccOption)
      .geometry(geometryBuilder.result)
      .woeType(woeTypeOption)
      .ids(featureIds.map(_.thriftFeatureId))
      .id(featureIds.headOption.map(_.humanReadableString))
      .longId(featureIds.headOption.map(_.longId))
      .slug(slugOption)
      .names(finalNames.toVector)
      .attributes(attributes)
      .result

    val scoringBuilder = ScoringFeatures.newBuilder
      .boost(boostOption)
      .population(populationOption)
      .parentIds(parents)
      .applyIf(extraRelations.nonEmpty, _.extraRelationIds(extraRelations))
      .applyIf(hasPoly, _.hasPoly(hasPoly))

    if (!canGeocode) {
      scoringBuilder.canGeocode(false)
    }

    val scoring = scoringBuilder.result

    val servingFeature = GeocodeServingFeature.newBuilder
      .longId(featureId.longId)
      .scoringFeatures(scoring)
      .feature(feature)
      .result

    servingFeature
  }

  def isCountry = woeTypeOption.has(YahooWoeType.COUNTRY)
  def isPostalCode = woeTypeOption.has(YahooWoeType.POSTAL_CODE)

  def debugString(): String = {
    "%s - %s %s - %s,%s".format(
      featureId,
      displayNames.headOption.flatMap(_.nameOption).getOrElse("????"),
      ccOption.getOrElse("??"),
      lat,
      lng
    )
  }

  def cc = ccOption.getOrElse("")
}
