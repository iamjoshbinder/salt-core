package com.unchartedsoftware.mosaic.core.projection

import org.apache.spark.sql.Row

/**
 * @param minZoom the minimum zoom level which will be passed into rowToCoords()
 * @param maxZoom the maximum zoom level which will be passed into rowToCoords()
 * @tparam DC the abstract type representing a data-space coordinate
 * @tparam TC the abstract type representing a tile coordinate. Must feature a
 *            zero-arg constructor.
 * @tparam BC the abstract type representing a bin coordinate. Must feature a zero-arg
 *            constructor and should be something that can be represented in 1 dimension.
 */
abstract class Projection[DC, TC, BC](
  val minZoom: Int,
  val maxZoom: Int
) extends Serializable {

  /**
   * @param c a tile coordinate
   * @return the zoom level of the coordinate
   */
  def getZoomLevel(c: TC): Int

  /**
   * Project a data-space coordinate into the corresponding tile coordinate and bin coordinate
   * @param dc the data-space coordinate
   * @param z the zoom level
   * @param maxBin the size of a tile
   * @return Some[(TC, Int)] representing the tile coordinate and bin index if the given row is within the bounds of the viz. None otherwise.
   */
  def project(dc: Option[DC], z: Int, maxBin: BC): Option[(TC, BC)]

  /**
   * Project a bin index BC into 1 dimension for easy storage of bin values in an array
   * @param bin A bin index
   * @param maxBin The maximum possible bin index
   * @return the bin index converted into its one-dimensional representation
   */
  def binTo1D(bin: BC, maxBin: BC): Int
}
