// Copyright 2014 Foursquare Labs Inc. All Rights Reserved.
package io.fsq.twofishes.server

import org.locationtech.jts.geom.Geometry
import io.fsq.twofishes.gen.{CellGeometry, GeocodeServingFeature}
import io.fsq.twofishes.util.StoredFeatureId

trait HotfixStorageService {
  def getIdsToAddByName(name: String): Seq[StoredFeatureId]
  def getIdsToRemoveByName(name: String): Seq[StoredFeatureId]
  def getIdsToAddByNamePrefix(name: String): Seq[StoredFeatureId]
  def getIdsToRemoveByNamePrefix(name: String): Seq[StoredFeatureId]

  def getAddedOrModifiedFeatureLongIds(): Seq[Long]
  def getDeletedFeatureLongIds(): Seq[Long]

  def getByFeatureId(id: StoredFeatureId): Option[GeocodeServingFeature]

  def getAddedOrModifiedPolygonFeatureLongIds(): Seq[Long]
  def getDeletedPolygonFeatureLongIds(): Seq[Long]

  def getCellGeometriesByS2CellId(id: Long): Seq[CellGeometry]
  def getPolygonByFeatureId(id: StoredFeatureId): Option[Geometry]
  def getS2CoveringByFeatureId(id: StoredFeatureId): Option[Seq[Long]]
  def getS2InteriorByFeatureId(id: StoredFeatureId): Option[Seq[Long]]

  def resolveNewSlugToLongId(slug: String): Option[Long]

  def refresh(): Unit
}
