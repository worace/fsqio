// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.
package io.fsq.twofishes.indexer.importers.geonames

import com.cybozu.labs.langdetect.DetectorFactory
import com.ibm.icu.text.Transliterator
import com.mongodb.MongoException
import com.twitter.ostrich.stats.Stats
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.{WKBReader, WKBWriter}
import io.fsq.common.scala.Identity._
import io.fsq.common.scala.Lists.Implicits._
import io.fsq.geo.quadtree.CountryRevGeo
import io.fsq.rogue.Iter
import io.fsq.twofishes.country.CountryInfo
import io.fsq.twofishes.gen._
import io.fsq.twofishes.indexer.mongo.{GeocodeStorageWriteService, IndexerQueryExecutor, PolygonIndex}
import io.fsq.twofishes.indexer.mongo.RogueImplicits._
import io.fsq.twofishes.indexer.util.{
  DisplayName,
  FsqSimpleFeature,
  GeoJsonIterator,
  GeocodeRecord,
  JaroWinkler,
  ShapeIterator,
  ShapefileIterator
}
import io.fsq.twofishes.model.gen.{ThriftGeocodeRecord, ThriftPolygonIndex, ThriftRevGeoIndex}
import io.fsq.twofishes.util.{
  AdHocId,
  DurationUtils,
  FeatureNamespace,
  GeonamesNamespace,
  Helpers,
  NameNormalizer,
  StoredFeatureId
}
import io.fsq.twofishes.util.Helpers._
import java.io.{File, FileWriter, Writer}
import java.nio.charset.Charset
import org.bson.types.ObjectId
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.collection.JavaConverters._

object LanguageDetector {
  DetectorFactory.loadProfile("./src/jvm/io/fsq/twofishes/indexer/data/langdetect/")

  val asciiEncoder = Charset.forName("US-ASCII").newEncoder()

  def isPureAscii(v: String) = asciiEncoder.canEncode(v)

  def detectLang(cc: String, s: String) = {
    val detector = DetectorFactory.create()
    detector.append(s)
    try {
      val lang = detector.detect
      if (!CountryInfo.getCountryInfo(cc).exists(_.isLocalLanguage(lang))) {
        if (isPureAscii(s)) {
          "en"
        } else {
          "unk"
        }
      } else {
        lang
      }
    } catch {
      case e: Exception => "unk"
    }
  }

}

object PolygonLoader {
  var adHocIdCounter = 0

  def getAdHocId() = {
    PolygonLoader.adHocIdCounter += 1
    AdHocId(PolygonLoader.adHocIdCounter)
  }
}

case class PolygonMappingConfig(
  nameFields: Map[String, List[String]],
  woeTypes: List[List[String]],
  idField: String,
  source: Option[String]
) {
  private val _woeTypes: List[List[YahooWoeType]] = {
    woeTypes.map(
      _.map(
        s => Option(YahooWoeType.findByNameOrNull(s)).getOrElse(throw new Exception("Unknown woetype: %s".format(s)))
      )
    )
  }

  def getWoeTypes() = _woeTypes
  def getAllWoeTypes() = getWoeTypes.flatten
}

object PolygonMappingConfig {
  val mappingExtension = ".mapping.json"

  def isMappingFile(f: File) = f.getName().endsWith(mappingExtension)

  def getMappingForFile(f: File): Option[PolygonMappingConfig] = {
    implicit val formats = DefaultFormats
    val file = new File(f.getPath() + mappingExtension)
    if (file.exists()) {
      val json = scala.io.Source.fromFile(file).getLines.mkString("")
      Some(
        parse(json).extract[PolygonMappingConfig]
      )
    } else {
      None
    }
  }
}

class PolygonLoader(
  parser: GeonamesParser,
  store: GeocodeStorageWriteService,
  parserConfig: GeonamesImporterConfig
) extends DurationUtils {
  val wkbWriter = new WKBWriter()

  lazy val executor = IndexerQueryExecutor.instance

  val matchExtension = ".match.tsv"

  val coverOptions = CoverOptions(
    forS2CoveringIndex = parserConfig.outputS2Covering,
    forS2InteriorIndex = parserConfig.outputS2Interior,
    parserConfig.outputRevgeo
  )

  def getMatchingForFile(
    f: File,
    defaultNamespace: FeatureNamespace
  ): Map[String, Seq[StoredFeatureId]] = {
    val file = new File(f.getPath() + matchExtension)
    if (file.exists()) {
      val lines = scala.io.Source.fromFile(file).getLines
      lines
        .map(line => {
          val parts = line.split("\t")
          if (parts.size != 2) {
            throw new Exception("broken line in %s:\n%s".format(file.getPath(), line))
          }
          (
            parts(0) -> parts(1)
              .split(",")
              .map(geoid => {
                StoredFeatureId
                  .fromHumanReadableString(geoid, Some(defaultNamespace))
                  .getOrElse(
                    throw new Exception(
                      "broken geoid %s in file %s on line: %s".format(
                        geoid,
                        file.getPath(),
                        line
                      )
                    )
                  )
              })
              .toList
          )
        })
        .toMap
    } else {
      Map.empty
    }
  }

  def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    if (these != null) {
      (these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles)).filterNot(_.isDirectory)
    } else {
      Array()
    }
  }

  var recordsUpdated = 0

  def indexPolygon(
    polyId: ObjectId,
    geom: Geometry,
    source: String
  ) {
    Stats.incr("PolygonLoader.indexPolygon")
    val geomBytes = wkbWriter.write(geom)
    executor.save(PolygonIndex(polyId, geomBytes, source))
    parser.s2CoveringMaster.foreach(_ ! CalculateCover(polyId, geomBytes, coverOptions))
  }

  def updateRecord(
    store: GeocodeStorageWriteService,
    geoids: List[StoredFeatureId],
    geom: Geometry,
    source: String
  ): Unit = {
    if (geoids.isEmpty) {
      return
    }

    val polyId = new ObjectId()
    indexPolygon(polyId, geom, source)

    for {
      geoid <- geoids
    } {
      try {
        log.debug("adding poly %s to %s %s".format(polyId, geoid, geoid.longId))
        recordsUpdated += 1
        store.addPolygonToRecord(geoid, polyId)
      } catch {
        case e: Exception => {
          throw new Exception("couldn't write poly to %s".format(geoid), e)
        }
      }
    }
  }

  def load(defaultNamespace: FeatureNamespace): Unit = {
    val polygonDirs = List(
      new File("src/jvm/io/fsq/twofishes/indexer/data/computed/polygons"),
      new File("src/jvm/io/fsq/twofishes/indexer/data/private/polygons")
    )
    val polygonFiles = polygonDirs.flatMap(recursiveListFiles).sorted

    for {
      (f, index) <- polygonFiles.zipWithIndex
    } {
      if (index % 1000 == 0) {
        System.gc();
      }
      load(defaultNamespace, f)
    }
    log.info("post processing, looking for bad poly matches")

    val wkbReader = new WKBReader()
    executor.iterate(
      Q(ThriftPolygonIndex).orderAsc(_.id),
      0
    )((index: Int, event: Iter.Event[ThriftPolygonIndex]) => {
      event match {
        case Iter.Item(polyRecord) => {
          for {
            featureRecord <- getFeaturesByPolyId(polyRecord.id)
            polyData = polyRecord.polygonOrThrow
          } {
            val polygon = wkbReader.read(polyData.array())
            val point = featureRecord.center
            if (!polygon.contains(point) && polygon.distance(point) > 0.03) {
              log.info("bad poly on %s -- %s too far from %s".format(featureRecord.featureId, polygon, point))

              executor.updateOne(
                Q(ThriftGeocodeRecord)
                  .where(_.id eqs featureRecord.featureId.longId)
                  .modify(_.hasPoly setTo false)
              )

              Stats.incr("PolygonLoader.removeBadPolygon")
            }
          }
          Iter.Continue(index + 1)
        }
        case Iter.EOF => Iter.Return(index)
        case Iter.Error(e) => throw e
      }
    })
    log.info("done reading in polys")
    parser.s2CoveringMaster.foreach(_ ! Done())
  }

  private def getFeaturesByPolyId(id: ObjectId) = {
    executor.fetch(Q(ThriftGeocodeRecord).where(_.polyId eqs id)).map(new GeocodeRecord(_))
  }

  def rebuildRevGeoIndex {
    IndexerQueryExecutor.dropCollection(ThriftRevGeoIndex)
    executor.iterateBatch(
      Q(ThriftPolygonIndex),
      1000,
      ()
    )((_: Unit, event: Iter.Event[Seq[ThriftPolygonIndex]]) => {
      event match {
        case Iter.Item(group) => {
          parser.s2CoveringMaster.foreach(_ ! CalculateCoverFromMongo(group.map(_.id).toList, coverOptions))
          Iter.Continue(())
        }
        case Iter.EOF => Iter.Return(())
        case Iter.Error(e) => throw e
      }
    })
    log.info("done reading in polys")
    parser.s2CoveringMaster.foreach(_ ! Done())
  }

  def findMatchCandidates(geometry: Geometry, woeTypes: List[YahooWoeType]): Iterator[GeocodeRecord] = {
    // This was using a query over a "loc" field that was never set anywhere, so it would never
    // return anything anyway.
    log.warn("findMatchCandidates doesn't return anything!!")
    Iterator.empty
  }

  def removeDiacritics(text: String) = {
    NameNormalizer.normalize(text)
  }

  // really, we should apply all the normalizations to shape names that we do to
  // geonames
  val spaceRegex = " +".r
  val transliterator = Transliterator.getInstance("Any-Latin; NFD;")
  def applyHacks(originalText: String): List[String] = {
    val text = originalText
    val translit = transliterator.transform(text)

    val names = (List(text, translit) ++ parser.doDelete(text)).toSet
    (parser.doRewrites(names.toList) ++ names).toSet.toList.map(removeDiacritics)
  }

  def bestNameScore(namesFromFeature: Seq[String], namesFromShape: Seq[String]): Int = {
    val namesFromShape_Modified = namesFromShape.flatMap(applyHacks)
    val namesFromFeature_Modified = namesFromFeature.flatMap(applyHacks)

    val scores = namesFromShape_Modified.flatMap(ns => {
      namesFromFeature_Modified.map(ng => {
        if (ng.nonEmpty && ns.nonEmpty) {
          if (ng == ns) {
            return 100
          } else {
            val jaroScore = JaroWinkler.score(ns, ng)
            jaroScore * 100
          }
        } else {
          0
        }
      })
    })

    if (scores.nonEmpty) {
      scores.max.toInt
    } else {
      0
    }
  }

  def hasName(config: PolygonMappingConfig, feature: FsqSimpleFeature): Boolean = {
    getName(config, feature).nonEmpty
  }

  def getName(config: PolygonMappingConfig, feature: FsqSimpleFeature): Option[String] = {
    getNames(config, feature).headOption.map(_.name)
  }

  def getNames(config: PolygonMappingConfig, feature: FsqSimpleFeature): Seq[DisplayName] = {
    config.nameFields.view
      .flatMap({
        case (lang, nameFields) => {
          nameFields
            .flatMap(nameField => feature.propMap.get(nameField))
            .filterNot(_.isEmpty)
            .map(name => DisplayName(lang, name))
        }
      })
      .toSeq
  }

  def getFixedNames(config: PolygonMappingConfig, feature: FsqSimpleFeature) = {
    val originalFeatureNames = getNames(config, feature)

    var featureNames: Seq[DisplayName] = originalFeatureNames

    // some OSM feature names look like A (B), make that into two extra names
    featureNames ++= originalFeatureNames.flatMap(n => {
      // If the name looks like "A (B)"
      parenNameRegex
        .findFirstIn(n.name)
        .toList
        .flatMap(_ => {
          // split it into "A" and "B"
          List(
            DisplayName(n.lang, parenNameRegex.replaceAllIn(n.name, "$1")),
            DisplayName(n.lang, parenNameRegex.replaceAllIn(n.name, "$2"))
          )
        })
    })

    // Additionally, some names are actually multiple comma separated names,
    // so split those into individual names too
    featureNames = featureNames.flatMap(n => {
      n.name.split(",").map(_.trim).filterNot(_.isEmpty).map(namePart => DisplayName(n.lang, namePart))
    })

    // Additionally, some names are actually multiple slash separated names
    // so split those into individual names too
    featureNames = featureNames.flatMap(n => {
      n.name.split("/").map(_.trim).filterNot(_.isEmpty).map(namePart => DisplayName(n.lang, namePart))
    })

    featureNames.filterNot(_.name.isEmpty)
  }

  // Formats a feature from a shapefile if we know a little about it based on a mapping config
  def debugFeature(config: PolygonMappingConfig, feature: FsqSimpleFeature): String = {
    val names = getNames(config, feature)

    val firstName = names.headOption.map(_.name).getOrElse("????")

    val id = getId(config, feature)

    "%s: %s (%s)".format(id, firstName, names.map(_.name).toSet.filterNot(_ =? firstName).mkString(", "))
  }

  // Formats a feature from a shapefile if we know nothing about it based on a mapping config
  def debugFeature(feature: FsqSimpleFeature): String = {
    feature.propMap.filterNot(_._1 == "the_geom").toString
  }

  // Formats a geonames record
  def debugFeature(r: GeocodeRecord): String = r.debugString

  case class PolygonMatch(
    candidate: GeocodeRecord,
    nameScore: Int = 0
  )

  val parenNameRegex = "^(.*) \\((.*)\\)$".r
  def scoreMatch(
    feature: FsqSimpleFeature,
    config: PolygonMappingConfig,
    candidate: GeocodeRecord,
    withLogging: Boolean = false
  ): PolygonMatch = {
    val featureNames = getFixedNames(config, feature)

    val candidateNames = candidate.displayNames.map(new DisplayName(_)).map(_.name).filterNot(_.isEmpty)
    val polygonMatch = PolygonMatch(
      candidate,
      nameScore = bestNameScore(featureNames.map(_.name), candidateNames)
    )

    if (withLogging) {
      if (!isGoodEnoughMatch(polygonMatch)) {
        log.debug(
          "%s vs %s -- %s vs %s".format(
            candidate.featureId.humanReadableString,
            feature.propMap.get(config.idField).getOrElse("0"),
            featureNames.map(_.name).flatMap(applyHacks).toSet,
            candidateNames.flatMap(applyHacks).toSet
          )
        )
      }
    }

    polygonMatch
  }

  def getId(polygonMappingConfig: PolygonMappingConfig, feature: FsqSimpleFeature) = {
    feature.propMap.get(polygonMappingConfig.idField).getOrElse("????")
  }

  def isGoodEnoughMatch(polygonMatch: PolygonMatch) = {
    polygonMatch.nameScore > 95
  }

  def maybeMatchFeature(
    config: PolygonMappingConfig,
    feature: FsqSimpleFeature,
    geometry: Geometry,
    outputMatchWriter: Option[Writer]
  ): List[StoredFeatureId] = {
    var candidatesSeen = 0

    if (!hasName(config, feature)) {
      log.info("no names on " + debugFeature(config, feature))
      return Nil
    }

    def matchAtWoeType(woeTypes: List[YahooWoeType], withLogging: Boolean = false): Seq[GeocodeRecord] = {
      try {
        val candidates = findMatchCandidates(geometry, woeTypes)
        val scores: Seq[PolygonMatch] = candidates
          .map(candidate => {
            candidatesSeen += 1
            // println(debugFeature(candidate))
            scoreMatch(feature, config, candidate, withLogging)
          })
          .filter(isGoodEnoughMatch)
          .toList

        val scoresMap = scores.groupBy(_.nameScore)

        if (scoresMap.nonEmpty) {
          scoresMap(scoresMap.keys.max).map(_.candidate)
        } else {
          Nil
        }
      } catch {
        // sometimes our bounding box wraps around the world and mongo throws an error
        case e: MongoException => Nil
      }
    }

    val matchingFeatures: Seq[GeocodeRecord] = {
      // woeTypes are a list of lists, in descening order of preference
      // each list is taken as equal prefence, but higher precedence than the
      // next list. If woeTypes looks like [[ADMIN3], [ADMIN2]], and we find
      // an admin3 match, we won't even bother looking for an admin2 match.
      // If it was [[ADMIN3, ADMIN2]], we would look for both, and if we
      // found two matches, take them both
      val acceptableCandidates: List[GeocodeRecord] =
        config.getWoeTypes.view
          .map(woeTypes => matchAtWoeType(woeTypes))
          .find(_.nonEmpty)
          .toList
          .flatten

      if (candidatesSeen == 0) {
        log.debug("failed couldn't find any candidates for " + debugFeature(config, feature))
      } else if (acceptableCandidates.isEmpty) {
        log.debug("failed to match: %s".format(debugFeature(config, feature)))
        matchAtWoeType(config.getAllWoeTypes, withLogging = true)
      } else {
        log.debug(
          "matched %s %s to %s".format(
            debugFeature(config, feature),
            getId(config, feature),
            acceptableCandidates.map(debugFeature).mkString(", ")
          )
        )
      }

      acceptableCandidates
    }

    if (matchingFeatures.isEmpty) {
      Nil
    } else {
      val idStr = matchingFeatures.map(_.featureId.humanReadableString).mkString(",")
      outputMatchWriter.foreach(
        _.write(
          "%s\t%s\n".format(
            feature.propMap.get(config.idField).getOrElse(throw new Exception("missing id")),
            idStr
          )
        )
      )
      matchingFeatures.map(_.featureId).toList
    }
  }

  def maybeMakeFeature(
    polygonMappingConfig: Option[PolygonMappingConfig],
    feature: FsqSimpleFeature
  ): Option[StoredFeatureId] = {
    // If it's been prematched, like the neighborhood geojson files
    lazy val featureOpt = for {
      adminCode1 <- feature.propMap.get("adminCode1")
      countryCode <- feature.propMap.get("countryCode")
      name <- feature.propMap.get("name")
      lat <- feature.propMap.get("lat")
      lng <- feature.propMap.get("lng")
    } yield {
      val id = PolygonLoader.getAdHocId()

      val attribMap: Map[GeonamesFeatureColumns.Value, String] = Map(
        (GeonamesFeatureColumns.GEONAMEID -> id.humanReadableString),
        (GeonamesFeatureColumns.NAME -> name),
        (GeonamesFeatureColumns.LATITUDE -> lat),
        (GeonamesFeatureColumns.LONGITUDE -> lng),
        (GeonamesFeatureColumns.COUNTRY_CODE -> countryCode),
        (GeonamesFeatureColumns.ADMIN1_CODE -> adminCode1)
      )

      val supplementalAttrubs: Map[GeonamesFeatureColumns.Value, String] = List(
        feature.propMap.get("adminCode2").map(c => (GeonamesFeatureColumns.ADMIN2_CODE -> c)),
        feature.propMap.get("adminCode3").map(c => (GeonamesFeatureColumns.ADMIN3_CODE -> c)),
        feature.propMap.get("adminCode4").map(c => (GeonamesFeatureColumns.ADMIN4_CODE -> c)),
        feature.propMap.get("fcode").map(c => (GeonamesFeatureColumns.FEATURE_CODE -> c)),
        feature.propMap.get("fclass").map(c => (GeonamesFeatureColumns.FEATURE_CLASS -> c))
      ).flatMap(x => x).toMap

      PolygonLoader.adHocIdCounter += 1
      val gnfeature = new GeonamesFeature(attribMap ++ supplementalAttrubs)
      log.debug("making adhoc feature: " + id + " " + id.longId)
      parser.insertGeocodeRecords(List(parser.parseFeature(gnfeature)))
      id
    }

    // This will get fixed up at output time based on revgeo
    lazy val toFixFeature = for {
      config <- polygonMappingConfig
      if (parserConfig.createUnmatchedFeatures)
      name <- getName(config, feature)
      geometry <- feature.geometry
      centroid = geometry.getCentroid()
      if (config.getWoeTypes.headOption.exists(_.size == 1))
      woeType <- config.getWoeTypes.headOption.flatMap(_.headOption)
      cc <- CountryRevGeo.getNearestCountryCode(centroid.getY, centroid.getX)
    } yield {
      val id = (for {
        ns <- config.source.flatMap(FeatureNamespace.fromNameOpt)
        lid <- TryO { getId(config, feature).toLong }
      } yield { StoredFeatureId(ns, lid) }).getOrElse({
        PolygonLoader.getAdHocId()
      })
      val basicFeature = new BasicFeature(
        centroid.getY,
        centroid.getX,
        cc,
        name,
        id,
        woeType
      )
      log.info(
        "creating feature for %s in %s".format(
          debugFeature(config, feature),
          cc
        )
      )
      val geocodeRecord = parser.parseFeature(basicFeature)
      val allNames = getFixedNames(config, feature)
      val langIdDisplayNames = allNames
        .map(n => {
          if (n.lang =? "unk") {
            n.copy(lang = LanguageDetector.detectLang(cc, n.name))
          } else {
            n
          }
        })
        .toList

      parser.insertGeocodeRecords(List(new GeocodeRecord(geocodeRecord.copy(displayNames = langIdDisplayNames))))
      id
    }

    featureOpt
      .orElse(toFixFeature)
      .orElse({
        log.error("no id on %s".format(debugFeature(feature)))
        None
      })
  }

  def processFeatureIterator(
    defaultNamespace: FeatureNamespace,
    features: ShapeIterator,
    polygonMappingConfig: Option[PolygonMappingConfig],
    matchingTable: Map[String, Seq[StoredFeatureId]]
  ) {
    val fparts = features.file.getName().split("\\.")
    lazy val outputMatchWriter = polygonMappingConfig.map(c => {
      val filename = features.file.getPath() + matchExtension
      new FileWriter(filename, true);
    })
    for {
      (rawFeature, index) <- features.zipWithIndex
      feature = new FsqSimpleFeature(rawFeature)
      geom <- feature.geometry
    } {
      if (index % 100 == 0) {
        log.info("processing feature %d in %s".format(index, features.file.getName))
      }

      val fidsFromFileName = fparts
        .lift(0)
        .flatMap(p => Helpers.TryO(p.toInt.toString))
        .flatMap(i => StoredFeatureId.fromHumanReadableString(i, Some(defaultNamespace)))
        .toList

      val source = polygonMappingConfig.flatMap(_.source).getOrElse(features.file.getName())
      val sourceKey = "polygonMatching." + source

      val geoidColumnNameToNamespaceMapping =
        List("qs_gn_id" -> GeonamesNamespace, "gn_id" -> GeonamesNamespace) ++
          FeatureNamespace.values.map(namespace => (namespace.name -> namespace))
      val geoidsFromFile = for {
        (columnName, namespace) <- geoidColumnNameToNamespaceMapping
        values <- feature.propMap.get(columnName).toList
        value <- values.split(",")
        if value.nonEmpty
      } yield {
        // if value already contains ':' it was human-readable to begin with
        if (value.indexOf(':') != -1) {
          value
        } else {
          "%s:%s".format(namespace.name, value)
        }
      }
      val fidsFromFile = geoidsFromFile
        .filterNot(_.isEmpty)
        .map(_.replace(".0", ""))
        .flatMap(i => StoredFeatureId.fromHumanReadableString(i, Some(defaultNamespace)))

      val fids: List[StoredFeatureId] = if (fidsFromFileName.nonEmpty) {
        fidsFromFileName
      } else if (fidsFromFile.nonEmpty) {
        fidsFromFile
      } else {
        val matches: List[StoredFeatureId] = polygonMappingConfig.toList.flatMap(config => {
          val idOpt: Option[String] = feature.propMap.get(config.idField)
          val priorMatches: List[StoredFeatureId] = idOpt.toList.flatMap(id => { matchingTable.get(id).getOrElse(Nil) })
          if (priorMatches.nonEmpty && !parserConfig.redoPolygonMatching) {
            priorMatches
          } else if (!parserConfig.skipPolygonMatching) {
            logDuration("polygonMatching") {
              logDuration(sourceKey) {
                maybeMatchFeature(config, feature, geom, outputMatchWriter).toList
              }
            }
          } else {
            Nil
          }
        })

        if (matches.nonEmpty) {
          matches
        } else {
          maybeMakeFeature(polygonMappingConfig, feature).toList
        }
      }

      updateRecord(store, fids, geom, source)
    }
    outputMatchWriter.foreach(_.close())
  }

  def load(defaultNamespace: FeatureNamespace, f: File): Unit = {
    val previousRecordsUpdated = recordsUpdated
    log.info("processing %s".format(f))
    val fparts = f.getName().split("\\.")
    val extension = fparts.lastOption.getOrElse("")
    val shapeFileExtensions = List("shx", "dbf", "prj", "xml", "cpg")

    if ((extension == "json" || extension == "geojson") && !PolygonMappingConfig.isMappingFile(f)) {
      processFeatureIterator(
        defaultNamespace,
        new GeoJsonIterator(f),
        PolygonMappingConfig.getMappingForFile(f),
        getMatchingForFile(f, defaultNamespace)
      )
      log.info("%d records updated by %s".format(recordsUpdated - previousRecordsUpdated, f))
    } else if (extension == "shp") {
      processFeatureIterator(
        defaultNamespace,
        new ShapefileIterator(f),
        PolygonMappingConfig.getMappingForFile(f),
        getMatchingForFile(f, defaultNamespace)
      )
      log.info("%d records updated by %s".format(recordsUpdated - previousRecordsUpdated, f))
    } else if (shapeFileExtensions.has(extension)) {
      // do nothing, shapefile aux file
      Nil
    }
  }
}
