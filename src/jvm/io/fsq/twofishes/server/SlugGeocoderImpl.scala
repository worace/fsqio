//  Copyright 2012 Foursquare Labs Inc. All Rights Reserved
package io.fsq.twofishes.server

import org.locationtech.jts.geom.Geometry
import io.fsq.common.scala.Lists.Implicits._
import io.fsq.twofishes.gen.{
  BulkSlugLookupRequest,
  BulkSlugLookupResponse,
  CommonGeocodeRequestParams,
  GeocodeInterpretation,
  GeocodeRequest,
  GeocodeResponse,
  GeocodeServingFeature,
  ResponseIncludes
}
import io.fsq.twofishes.util.StoredFeatureId
import scala.collection.JavaConverters._

class SlugGeocoderImpl(
  store: GeocodeStorageReadService,
  req: GeocodeRequest,
  logger: MemoryLogger
) extends AbstractGeocoderImpl[GeocodeResponse] {
  override val implName = "slug"

  val commonParams = GeocodeRequestUtils.geocodeRequestToCommonRequestParams(req)
  val responseProcessor = new ResponseProcessor(commonParams, store, logger)

  def doGeocodeImpl(): GeocodeResponse = {
    val slug = req.slugOption.getOrElse("")

    val parseParams = ParseParams()

    val featureMap: Map[String, GeocodeServingFeature] = store.getBySlugOrFeatureIds(List(slug))

    // a parse is still a seq of featurematch, bleh
    val parse = Parse[Sorted](
      featureMap
        .get(slug)
        .map(servingFeature => {
          FeatureMatch(0, 0, "", servingFeature)
        })
        .toList
    )

    responseProcessor.buildFinalParses(List(parse), parseParams, 0, requestGeom = None)
  }
}

class BulkSlugLookupImpl(
  store: GeocodeStorageReadService,
  req: BulkSlugLookupRequest
) extends AbstractGeocoderImpl[BulkSlugLookupResponse]
  with BulkImplHelpers {
  override val implName = "bulk_slug"

  val params = req.paramsOption.getOrElse(CommonGeocodeRequestParams.newBuilder.result)
  val logger = new MemoryLogger(params)

  val responseProcessor = new ResponseProcessor(params, store, logger)

  def doGeocodeImpl(): BulkSlugLookupResponse = {
    val parseParams = ParseParams()

    val featureMap: Map[String, GeocodeServingFeature] = store.getBySlugOrFeatureIds(req.slugs)
    //val inputToIdxes: Map[String, Seq[Int]] = req.slugs.zipWithIndex.groupBy(_._1).mapValues(_.map(_._2))

    val polygonMap: Map[StoredFeatureId, Geometry] = if (GeocodeRequestUtils.shouldFetchPolygon(params)) {
      store.getPolygonByFeatureIds(featureMap.values.flatMap(f => StoredFeatureId.fromLong(f.longId)).toSeq)
    } else {
      Map.empty
    }

    val s2CoveringMap: Map[StoredFeatureId, Seq[Long]] =
      if (GeocodeRequestUtils.responseIncludes(params, ResponseIncludes.S2_COVERING)) {
        store.getS2CoveringByFeatureIds(featureMap.values.flatMap(f => StoredFeatureId.fromLong(f.longId)).toSeq)
      } else {
        Map.empty
      }

    val s2InteriorMap: Map[StoredFeatureId, Seq[Long]] =
      if (GeocodeRequestUtils.responseIncludes(params, ResponseIncludes.S2_INTERIOR)) {
        store.getS2InteriorByFeatureIds(featureMap.values.flatMap(f => StoredFeatureId.fromLong(f.longId)).toSeq)
      } else {
        Map.empty
      }

    val parses =
      featureMap.values.map(servingFeature => Parse[Sorted](Seq(FeatureMatch(0, 0, "", servingFeature)))).toSeq

    val interps: Seq[GeocodeInterpretation] = responseProcessor.hydrateParses(
      parses,
      parseParams,
      polygonMap,
      s2CoveringMap,
      s2InteriorMap,
      fixAmbiguousNames = true,
      dedupByMatchedName = false
    )

    val (interpIdxs, retInterps, parents) = makeBulkReply(
      req.slugs,
      featureMap.mappedValues(servingFeature => StoredFeatureId.fromLong(servingFeature.feature.longId).toList),
      interps
    )

    val respBuilder = BulkSlugLookupResponse.newBuilder
      .interpretations(interps)
      // map from input idx to interpretation indexes
      .interpretationIndexes(interpIdxs)
      .parentFeatures(parents)

    if (params.debug > 0) {
      respBuilder.debugLines(logger.getLines)
    }
    respBuilder.result
  }
}
