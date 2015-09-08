package com.unchartedsoftware.mosaic.core.generation.accumulator

import com.unchartedsoftware.mosaic.core.analytic.{Aggregator, ValueExtractor}
import com.unchartedsoftware.mosaic.core.projection.Projection
import com.unchartedsoftware.mosaic.core.generation.output.TileData
import com.unchartedsoftware.mosaic.core.generation.ActiveTileGenerator
import com.unchartedsoftware.mosaic.core.generation.request.TileRequest
import org.apache.spark.{Accumulable, SparkContext}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.{Row, DataFrame}
import scala.reflect.ClassTag
import scala.util.Try
import scala.collection.Map
import scala.collection.mutable.{HashMap, ListBuffer}

/**
 * Tile Generator for a batch of tiles, using accumulators
 * @param projection the  projection from data to some space (i.e. 2D or 1D)
 * @param extractor a mechanism for grabbing the "value" column from a source record
 * @param binAggregator the desired bin analytic strategy
 * @param tileAggregator the desired tile analytic strategy
 * @tparam TC the abstract type representing a tile coordinate. Must feature a zero-arg constructor.
 * @tparam T Input data type for bin aggregators
 * @tparam U Intermediate data type for bin aggregators
 * @tparam V Output data type for bin aggregators, and input for tile aggregator
 * @tparam W Intermediate data type for tile aggregators
 * @tparam X Output data type for tile aggregators
 */
class AccumulatorTileGenerator[TC: ClassTag, T, U: ClassTag, V, W, X](
  sc: SparkContext,
  projection: Projection[TC],
  extractor: ValueExtractor[T],
  binAggregator: Aggregator[T, U, V],
  tileAggregator: Aggregator[V, W, X])
extends ActiveTileGenerator[TC, T, U, V, W, X](sc, projection, extractor, binAggregator, tileAggregator) {

  //TODO find a way to eliminate inCoords by using reflection to locate a zero-arg constructor and invoke it.
  //then we can remove this from the superclass as well
  def generate(dataFrame: DataFrame, request: TileRequest[TC]): Seq[TileData[TC, V, X]] = {
    dataFrame.cache //ensure data is cached

    //broadcast stuff we'll use on the workers throughout our tilegen process
    val bProjection = sc.broadcast(projection)
    val bExtractor = sc.broadcast(extractor)
    val bBinAggregator = sc.broadcast(binAggregator)
    val bTileAggregator = sc.broadcast(tileAggregator)
    val bRequest = sc.broadcast(request)

    //create an accumulator for this tile batch
    val param = new TileGenerationAccumulableParam[TC, T, U, V](bProjection, bExtractor, bBinAggregator)
    val initialValue = new HashMap[TC, Array[U]]
    val accumulator = sc.accumulable(initialValue)(param)

    _sanitizedClosureGenerate(bProjection, dataFrame, bRequest, accumulator)

    //finish tile by computing tile-level statistics
    //TODO parallelize on workers (if the number of tiles is heuristically large) to avoid memory overloading on the master?
    val result = accumulator.value.toSeq.map( e => {
      val key = e._1
      val bins = e._2
      val binAggregator = bBinAggregator.value
      val tileAggregator = bTileAggregator.value
      val projection = bProjection.value
      var tile: W = tileAggregator.default
      var binsTouched = 0

      val finishedBins = bins.map(a => {
        if (!a.equals(binAggregator.default)) binsTouched+=1
        val bin = binAggregator.finish(a)
        tile = tileAggregator.add(tile, Some(bin))
        bin
      })
      new TileData[TC, V, X](key, finishedBins, binsTouched, binAggregator.finish(binAggregator.default), tileAggregator.finish(tile), projection)
    })

    bRequest.unpersist
    bProjection.unpersist
    bExtractor.unpersist
    bBinAggregator.unpersist
    bTileAggregator.unpersist

    result
  }

  /**
   * Since spark serializes closures, everything within the closure must be serializable
   * This does the real work of generate, excluding the handling of anything that isn't
   * serializable and needs to be dealt with on the master.
   */
  private def _sanitizedClosureGenerate(
    bProjection: Broadcast[Projection[TC]],
    dataFrame: DataFrame,
    bRequest: Broadcast[TileRequest[TC]],
    accumulator: Accumulable[HashMap[TC, Array[U]], (TC, Int, Row)]
  ): Unit = {
    val bLevels = sc.broadcast(bRequest.value.levels)

    //generate bin data by iterating over each row of the source data frame
    dataFrame.foreach(row => {
      val projection = bProjection.value
      val request = bRequest.value
      Try({
        bLevels.value.foreach(l => {
          val coord = projection.rowToCoords(row, l)
          //bin is defined when we are in the bounds of the projection
          if (coord.isDefined && request.inRequest(coord.get._1)) {
            Try(accumulator += (coord.get._1, coord.get._2, row))
          }
        })
      })
    })

    bLevels.unpersist
  }
}
